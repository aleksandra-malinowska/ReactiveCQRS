package io.reactivecqrs.core.eventstore

import io.mpjsons.MPJsons
import io.reactivecqrs.api.id.AggregateId
import io.reactivecqrs.api.{DuplicationEvent, UndoEvent, AggregateVersion, Event}
import io.reactivecqrs.core.aggregaterepository.AggregateRepositoryActor.PersistEvents
import io.reactivecqrs.core.aggregaterepository.{EventIdentifier, IdentifiableEventNoAggregateType}
import scalikejdbc._

class PostgresEventStoreState(mpjsons: MPJsons) extends EventStoreState {

  def initSchema(): Unit = {
    (new EventStoreSchemaInitializer).initSchema()
  }

  override def persistEvents[AGGREGATE_ROOT](aggregateId: AggregateId, eventsEnvelope: PersistEvents[AGGREGATE_ROOT]): Unit = {
    var versionsIncreased = 0
    DB.autoCommit { implicit session =>
      eventsEnvelope.events.foreach(event => {

        val eventSerialized = mpjsons.serialize(eventsEnvelope.events.head, event.getClass.getName)

        val query = event match {
          case undoEvent: UndoEvent[_] =>
            sql"""SELECT add_undo_event(?, ?, ?, ? ,? , ?, ?, ?, ?)""".bind(
              eventsEnvelope.commandId.asLong,
              eventsEnvelope.userId.asLong,
              aggregateId.asLong,
              eventsEnvelope.expectedVersion.asInt + versionsIncreased,
              event.aggregateRootType.typeSymbol.fullName,
              event.getClass.getName,
              0,
              eventSerialized,
              undoEvent.eventsCount
            ).execute().apply()
          case duplicationEvent: DuplicationEvent[_] =>
            sql"""SELECT add_duplication_event(?, ?, ?, ? ,? , ?, ?, ?, ?, ?)""".bind(
              eventsEnvelope.commandId.asLong,
              eventsEnvelope.userId.asLong,
              aggregateId.asLong,
              eventsEnvelope.expectedVersion.asInt + versionsIncreased,
              event.aggregateRootType.typeSymbol.fullName,
              event.getClass.getName,
              0,
              eventSerialized,
              duplicationEvent.baseAggregateId.asLong,
              duplicationEvent.baseAggregateVersion.asInt
            ).execute().apply()
          case _ =>
            sql"""SELECT add_event(?, ?, ?, ? ,? , ?, ? ,?)""".bind(
              eventsEnvelope.commandId.asLong,
              eventsEnvelope.userId.asLong,
              aggregateId.asLong,
              eventsEnvelope.expectedVersion.asInt + versionsIncreased,
              event.aggregateRootType.typeSymbol.fullName,
              event.getClass.getName,
              0,
              eventSerialized).execute().apply()

        }

        versionsIncreased += 1
      })
    }

  }

  override def readAndProcessEvents[AGGREGATE_ROOT](aggregateId: AggregateId, version: Option[AggregateVersion])(eventHandler: (Event[AGGREGATE_ROOT], AggregateId, Boolean) => Unit): Unit = {

    DB.readOnly { implicit session =>

      val query = version match {
        case Some(v) =>
          sql"""SELECT event_type, event, events.version, events.aggregate_id,
                noop_events.id IS NOT NULL AND (events.aggregate_id != ? AND noop_events.from_version <= aggregates.base_version OR events.aggregate_id = ? AND noop_events.from_version <= ?) as noop
             FROM events
             JOIN aggregates ON events.aggregate_id = aggregates.base_id AND (events.aggregate_id != ? AND events.version <= aggregates.base_version OR events.aggregate_id = ? AND events.version <= ?)
             LEFT JOIN noop_events ON events.id = noop_events.id AND noop_events.from_version <= aggregates.base_version
             WHERE aggregates.id = ? ORDER BY aggregates.base_order, version""".stripMargin.bind(aggregateId.asLong, aggregateId.asLong, v.asInt, aggregateId.asLong, aggregateId.asLong, v.asInt, aggregateId.asLong)
        case None =>
          sql"""SELECT event_type, event, events.version, events.aggregate_id, noop_events.id IS NOT NULL AND noop_events.from_version <= aggregates.base_version as noop
             FROM events
             JOIN aggregates ON events.aggregate_id = aggregates.base_id AND events.version <= aggregates.base_version
             LEFT JOIN noop_events ON events.id = noop_events.id AND noop_events.from_version <= aggregates.base_version
             WHERE aggregates.id = ? ORDER BY aggregates.base_order, version""".stripMargin.bind(aggregateId.asLong)
      }

      query.foreach { rs =>

        val event = mpjsons.deserialize[Event[AGGREGATE_ROOT]](rs.string(2), rs.string(1))
        val id = AggregateId(rs.long(4))
        val eventVersion = rs.long(3)
        if(version.isEmpty || id != aggregateId || eventVersion <= version.get.asInt) {
          eventHandler(event, id, rs.boolean(5))
        } // otherwise it's to new event, TODO optimise as it reads all events from database, also those not needed here
      }
    }
  }

  def readAndProcessAllEventsForVersion[AGGREGATE_ROOT](aggregateId: AggregateId, version: AggregateVersion)(eventHandler: Event[AGGREGATE_ROOT] => Unit): Unit = {

    DB.readOnly { implicit session =>
      sql"""SELECT event_type, event
           | FROM events
           | LEFT JOIN noop_events ON events.id = noop_events.id AND noop_events.from_version <= ?
           | WHERE aggregate_id = ? AND version <= ? AND noop_events.id IS NULL
           | ORDER BY version""".stripMargin.bind(version.asInt, aggregateId.asLong, version.asInt).foreach { rs =>

        val event = mpjsons.deserialize[Event[AGGREGATE_ROOT]](rs.string(2), rs.string(1))
        eventHandler(event)
      }
    }
  }
  

  override def deletePublishedEventsToPublish(events: Seq[EventIdentifier]): Unit = {
    // TODO optimize SQL query so it will be one query
    DB.autoCommit { implicit session =>
      events.foreach {event =>
        sql"""DELETE FROM events_to_publish WHERE aggregate_id = ? AND version = ?"""
          .bind(event.aggregateId.asLong, event.version.asInt)
          .executeUpdate().apply()
      }
    }
  }

  override def readAggregatesWithEventsToPublish(aggregateHandler: AggregateId => Unit): Unit = {
    DB.readOnly { implicit session =>
      sql"""SELECT DISTINCT aggregate_id
           | FROM events_to_publish
           """.stripMargin.foreach { rs =>
        aggregateHandler(AggregateId(rs.int(1)))
      }
    }
  }

  override def readEventsToPublishForAggregate[AGGREGATE_ROOT](aggregateId: AggregateId): List[IdentifiableEventNoAggregateType[AGGREGATE_ROOT]] = {
    var result = List[IdentifiableEventNoAggregateType[AGGREGATE_ROOT]]()
    DB.readOnly { implicit session =>
      sql"""SELECT events_to_publish.version, events.event_type, events.event_type_version, events.event
           | FROM events_to_publish
           | JOIN events on events_to_publish.event_id = events.id
           | WHERE events_to_publish.aggregate_id = ?
           | ORDER BY events_to_publish.version""".stripMargin.bind(aggregateId.asLong).foreach { rs =>

        val event = mpjsons.deserialize[Event[AGGREGATE_ROOT]](rs.string(4), rs.string(2))

        result ::= IdentifiableEventNoAggregateType[AGGREGATE_ROOT](aggregateId, AggregateVersion(rs.int(1)), event)

      }
    }
    result.reverse
  }



}
