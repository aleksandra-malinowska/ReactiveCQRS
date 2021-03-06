package io.reactivecqrs.core

import java.util.concurrent.atomic.AtomicLong
import io.reactivecqrs.api.id.AggregateId

class PostgresSequentialAggregateIdGenerator extends AggregateIdGenerator {

  private val id = new AtomicLong(0L)

  override def nextAggregateId = AggregateId(id.getAndIncrement)

}
