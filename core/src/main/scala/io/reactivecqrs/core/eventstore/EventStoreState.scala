package io.reactivecqrs.core.eventstore

import io.reactivecqrs.api.Event
import io.reactivecqrs.api.id.AggregateId
import io.reactivecqrs.core.aggregaterepository.AggregateRepositoryActor.PersistEvents
import io.reactivecqrs.core.aggregaterepository.{EventIdentifier, IdentifiableEventNoAggregateType}

abstract class EventStoreState {
  def persistEvents[AGGREGATE_ROOT](aggregateId: AggregateId, eventsEnvelope: PersistEvents[AGGREGATE_ROOT]): Unit
  def readAndProcessAllEvents[AGGREGATE_ROOT](aggregateId: AggregateId)(eventHandler: (Event[AGGREGATE_ROOT], AggregateId, Boolean) => Unit)
  def deletePublishedEventsToPublish(events: Seq[EventIdentifier]): Unit

  def readAggregatesWithEventsToPublish(aggregateHandler: AggregateId => Unit): Unit
  def readEventsToPublishForAggregate[AGGREGATE_ROOT](aggregateId: AggregateId): List[IdentifiableEventNoAggregateType[AGGREGATE_ROOT]]
}

