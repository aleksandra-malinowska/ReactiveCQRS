package io.reactivecqrs.testdomain.spec

import akka.actor.{ActorRef, Props}
import akka.serialization.SerializationExtension
import io.mpjsons.MPJsons
import io.reactivecqrs.api._
import io.reactivecqrs.api.id.UserId
import io.reactivecqrs.core.commandhandler.AggregateCommandBusActor
import io.reactivecqrs.core.documentstore.MemoryDocumentStore
import io.reactivecqrs.core.eventbus.{EventsBusActor, PostgresEventBusState}
import io.reactivecqrs.core.eventstore.PostgresEventStoreState
import io.reactivecqrs.core.uid.{PostgresUidGenerator, UidGeneratorActor}
import io.reactivecqrs.testdomain.shoppingcart._
import io.reactivecqrs.testdomain.spec.utils.CommonSpec
import scalikejdbc.{ConnectionPool, ConnectionPoolSettings}

import scala.util.Try


class ReactiveTestDomainSpec extends CommonSpec {

  val settings = ConnectionPoolSettings(
    initialSize = 5,
    maxSize = 20,
    connectionTimeoutMillis = 3000L)

  Class.forName("org.postgresql.Driver")
  ConnectionPool.singleton("jdbc:postgresql://localhost:5432/reactivecqrs", "reactivecqrs", "reactivecqrs", settings)



  def Fixture = new {
    val eventStoreState = new PostgresEventStoreState(new MPJsons) // or MemoryEventStore
    eventStoreState.initSchema()
    val userId = UserId(1L)
    val serialization = SerializationExtension(system)
    val eventBusState = new PostgresEventBusState(serialization) // or MemoryEventBusState
    eventBusState.initSchema()

    val aggregatesUidGenerator = new PostgresUidGenerator("aggregates_uids_seq") // or MemoryUidGenerator
    val commandsUidGenerator = new PostgresUidGenerator("commands_uids_seq") // or MemoryUidGenerator
    val uidGenerator = system.actorOf(Props(new UidGeneratorActor(aggregatesUidGenerator, commandsUidGenerator)), "uidGenerator")
    val eventBusActor = system.actorOf(Props(new EventsBusActor(eventBusState)), "eventBus")
    val shoppingCartCommandBus: ActorRef = system.actorOf(
      AggregateCommandBusActor(new ShoppingCartAggregateContext, uidGenerator, eventStoreState, eventBusActor), "ShoppingCartCommandBus")

    val shoppingCartsListProjectionEventsBased = system.actorOf(Props(new ShoppingCartsListProjectionEventsBased(eventBusActor, new MemoryDocumentStore[String, AggregateVersion])), "ShoppingCartsListProjectionEventsBased")
    val shoppingCartsListProjectionAggregatesBased = system.actorOf(Props(new ShoppingCartsListProjectionAggregatesBased(eventBusActor, new MemoryDocumentStore[String, AggregateVersion])), "ShoppingCartsListProjectionAggregatesBased")

    Thread.sleep(100) // Wait until all subscriptions in place


    def shoppingCartCommand(command: AnyRef): Aggregate[ShoppingCart]= {
      val result: CommandResponse = shoppingCartCommandBus ?? command
      val success = result.asInstanceOf[SuccessResponse]

      val shoppingCartTry:Try[Aggregate[ShoppingCart]] = shoppingCartCommandBus ?? GetAggregate(success.aggregateId)
      shoppingCartTry.get
    }
  }



  feature("Aggregate storing and getting with event sourcing") {

    scenario("Creation and modification of shopping cart aggregate") {

      val fixture = Fixture
      import fixture._

      step("Create shopping cart")

      var shoppingCart = shoppingCartCommand(CreateShoppingCart(userId,"Groceries"))
      shoppingCart mustBe Aggregate(shoppingCart.id, AggregateVersion(1), Some(ShoppingCart("Groceries", Vector())))

      step("Add items to cart")

      shoppingCart = shoppingCartCommand(AddItem(userId, shoppingCart.id, AggregateVersion(1), "apples"))
      shoppingCart = shoppingCartCommand(AddItem(userId, shoppingCart.id, AggregateVersion(2), "oranges"))
      shoppingCart mustBe Aggregate(shoppingCart.id, AggregateVersion(3), Some(ShoppingCart("Groceries", Vector(Item(1, "apples"), Item(2, "oranges")))))

      step("Remove items from cart")

      shoppingCart = shoppingCartCommand(RemoveItem(userId, shoppingCart.id, AggregateVersion(3), 1))
      shoppingCart mustBe Aggregate(shoppingCart.id, AggregateVersion(4), Some(ShoppingCart("Groceries", Vector(Item(2, "oranges")))))


      Thread.sleep(300) // Projections are eventually consistent, so let's wait until they are consistent

      var cartsNames: Vector[String] = shoppingCartsListProjectionEventsBased ?? ShoppingCartsListProjection.GetAllCartsNames()
      cartsNames must have size 1

      cartsNames = shoppingCartsListProjectionAggregatesBased ?? ShoppingCartsListProjection.GetAllCartsNames()
      cartsNames must have size 1


      step("Undo removing items from cart")

      shoppingCart = shoppingCartCommand(UndoShoppingCartChange(userId, shoppingCart.id, AggregateVersion(4), 1))
      shoppingCart mustBe Aggregate(shoppingCart.id, AggregateVersion(5), Some(ShoppingCart("Groceries", Vector(Item(1, "apples"), Item(2, "oranges")))))

      step("Remove different items from cart")

      shoppingCart = shoppingCartCommand(RemoveItem(userId, shoppingCart.id, AggregateVersion(5), 2))
      shoppingCart mustBe Aggregate(shoppingCart.id, AggregateVersion(6), Some(ShoppingCart("Groceries", Vector(Item(1, "apples")))))

    }


  }

}
