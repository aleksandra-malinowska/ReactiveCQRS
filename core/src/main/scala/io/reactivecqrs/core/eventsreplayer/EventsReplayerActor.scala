package io.reactivecqrs.core.eventsreplayer

import java.time.Instant

import akka.pattern.ask
import akka.actor.{Actor, ActorContext, ActorRef, Props}
import akka.util.Timeout
import io.reactivecqrs.api.{AggregateContext, AggregateType, AggregateVersion, Event}
import io.reactivecqrs.api.id.{AggregateId, UserId}
import io.reactivecqrs.core.aggregaterepository.{IdentifiableEvent, ReplayAggregateRepositoryActor}
import io.reactivecqrs.core.aggregaterepository.ReplayAggregateRepositoryActor.ReplayEvent
import io.reactivecqrs.core.backpressure.BackPressureActor
import io.reactivecqrs.core.backpressure.BackPressureActor.{ProducerAllowMore, ProducerAllowedMore, Start, Stop}
import io.reactivecqrs.core.eventsreplayer.EventsReplayerActor.{EventsReplayed, ReplayAllEvents}
import io.reactivecqrs.core.eventstore.EventStoreState

import scala.concurrent.Await
import scala.reflect.runtime.universe._
import scala.reflect.ClassTag
import scala.concurrent.duration._

object ReplayerRepositoryActorFactory {
  def apply[AGGREGATE_ROOT: TypeTag:ClassTag](aggregateContext: AggregateContext[AGGREGATE_ROOT]): ReplayerRepositoryActorFactory[AGGREGATE_ROOT] = {
    new ReplayerRepositoryActorFactory[AGGREGATE_ROOT](aggregateContext)
  }
}

class ReplayerRepositoryActorFactory[AGGREGATE_ROOT: TypeTag:ClassTag](aggregateContext: AggregateContext[AGGREGATE_ROOT]) {

  def aggregateRootType = typeOf[AGGREGATE_ROOT]

  def create(context: ActorContext, aggregateId: AggregateId, aggregateVersion: Option[AggregateVersion], eventStore: EventStoreState, eventsBus: ActorRef, actorName: String): ActorRef = {
    context.actorOf(Props(new ReplayAggregateRepositoryActor[AGGREGATE_ROOT](aggregateId, eventStore, eventsBus, aggregateContext.eventHandlers,
      () => aggregateContext.initialAggregateRoot, aggregateVersion)), actorName)
  }

}

object EventsReplayerActor {
  case object ReplayAllEvents
  case class EventsReplayed(eventsCount: Long)
}


class EventsReplayerActor(eventStore: EventStoreState,
                          val eventsBus: ActorRef,
                          actorsFactory: List[ReplayerRepositoryActorFactory[_]]) extends Actor {

  import context.dispatcher

  val timeoutDuration: FiniteDuration = 300.seconds
  implicit val timeout = Timeout(timeoutDuration)

  val factories: Map[String, ReplayerRepositoryActorFactory[_]] = actorsFactory.map(f => f.aggregateRootType.toString -> f).toMap

  var messagesToProduceAllowed = 0L

  var backPressureActor: ActorRef = context.actorOf(Props(new BackPressureActor(eventsBus)), "BackPressure")

  override def receive: Receive = {
    case ReplayAllEvents => replayAllEvents(sender)
  }

  private def replayAllEvents(respondTo: ActorRef) {
    backPressureActor ! Start
    val allEvents: Int = eventStore.countAllEvents()
    var eventsSent: Long = 0
    eventStore.readAndProcessAllEvents((eventId: Long, event: Event[_], aggregateId: AggregateId, version: AggregateVersion, aggregateType: AggregateType, userId: UserId, timestamp: Instant) => {
      if(messagesToProduceAllowed == 0) {
        // Ask is a way to block during fetching data from db
        messagesToProduceAllowed = Await.result((backPressureActor ? ProducerAllowMore).mapTo[ProducerAllowedMore].map(_.count), timeoutDuration)
      }

      val actor = getOrCreateReplayRepositoryActor(aggregateId, version, aggregateType)
      actor ! ReplayEvent(IdentifiableEvent(eventId, aggregateType, aggregateId, version, event, userId, timestamp))
      messagesToProduceAllowed -= 1

      eventsSent += 1
      if(eventsSent % 100 == 0) {
        println("Replayed "+eventsSent+"/"+allEvents)
      }
    })
    backPressureActor ! Stop
    respondTo ! EventsReplayed(eventsSent)
  }

  // Assumption - we replay events from first event so there is not need to have more than one actor for each event
  // QUESTION - cleanup?
  private def getOrCreateReplayRepositoryActor(aggregateId: AggregateId, aggregateVersion: AggregateVersion, aggregateType: AggregateType): ActorRef = {
    context.child(aggregateType.typeName + "_AggregateRepositorySimulator_" + aggregateId.asLong).getOrElse(
      factories(aggregateType.typeName).create(context,aggregateId, None, eventStore, eventsBus,
        aggregateType.typeName + "_AggregateRepositorySimulator_" + aggregateId.asLong)
    )
  }
}