package org.lantai.infrastructure.i18n

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jooq.DSLContext
import org.jooq.impl.DSL.count
import org.lantai.jooq.main.Tables
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}

@Component
class SeriesFirstLetterBackfill(
  private val dsl: DSLContext,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun schedule() {
    CompletableFuture.runAsync {
      try {
        run()
      } catch (e: Exception) {
        logger.warn(e) { "Series first letter backfill failed" }
      }
    }
  }

  fun run(force: Boolean = false): Int {
    val t = Tables.SERIES_METADATA
    if (force) dsl.update(t).setNull(t.FIRST_LETTER).execute()
    var total = 0
    while (true) {
      val rows =
        dsl
          .select(t.SERIES_ID, t.TITLE_SORT)
          .from(t)
          .where(t.FIRST_LETTER.isNull)
          .limit(500)
          .fetch()
      if (rows.isEmpty()) break
      val updates =
        rows.map { r ->
          dsl
            .update(t)
            .set(t.FIRST_LETTER, SeriesFirstLetter.of(r[t.TITLE_SORT]))
            .where(t.SERIES_ID.eq(r[t.SERIES_ID]))
        }
      dsl.batch(updates).execute()
      total += rows.size
    }
    if (total > 0) logger.info { "Series first letter backfilled: $total" }
    return total
  }
}
