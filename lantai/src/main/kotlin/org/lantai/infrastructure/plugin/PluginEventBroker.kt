package org.lantai.infrastructure.plugin

import org.lantai.domain.model.DomainEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class PluginEvent(
  val seq: Long,
  val type: String,
  val id: String,
  val at: String,
)

@Service
class PluginEventBroker {
  private val ring = ArrayDeque<PluginEvent>()
  private val sequence = AtomicLong()
  private val subscribers = ConcurrentHashMap<String, MutableList<SseEmitter>>()

  @EventListener
  fun onDomainEvent(event: DomainEvent) {
    val mapped = map(event) ?: return
    publish(mapped.first, mapped.second)
  }

  fun publish(
    type: String,
    id: String,
  ) {
    val event =
      synchronized(ring) {
        val created = PluginEvent(sequence.incrementAndGet(), type, id, Instant.now().toString())
        ring.addLast(created)
        while (ring.size > BUFFER_SIZE) ring.removeFirst()
        created
      }
    subscribers.keys.forEach { dispatch(it, event) }
  }

  fun open(
    pluginId: String,
    lastEventId: Long?,
  ): SseEmitter {
    val emitter = SseEmitter(TIMEOUT_MS)
    synchronized(ring) {
      val list = subscribers.computeIfAbsent(pluginId) { CopyOnWriteArrayList() }
      if (list.size >= MAX_SUBSCRIBERS) throw PluginSubscriptionException("同一插件最多 $MAX_SUBSCRIBERS 路事件订阅")
      list.add(emitter)
      val oldest = ring.firstOrNull()?.seq
      when {
        lastEventId == null -> Unit
        oldest == null -> Unit
        lastEventId < oldest - 1 ->
          send(emitter, SseEmitter.event().name("reset").data("""{"reason":"buffer_overflow","oldest":$oldest}"""))
        else -> ring.filter { it.seq > lastEventId }.forEach { replay(emitter, it) }
      }
    }
    emitter.onCompletion { detach(pluginId, emitter) }
    emitter.onTimeout { detach(pluginId, emitter) }
    emitter.onError { detach(pluginId, emitter) }
    return emitter
  }

  fun subscriberCount(pluginId: String): Int = subscribers[pluginId]?.size ?: 0

  fun oldestSeq(): Long? = synchronized(ring) { ring.firstOrNull()?.seq }

  fun latestSeq(): Long = sequence.get()

  @Scheduled(fixedRate = 30_000)
  fun heartbeat() {
    subscribers.forEach { (pluginId, list) ->
      list.forEach { emitter ->
        try {
          emitter.send(SseEmitter.event().comment("heartbeat"))
        } catch (ex: Exception) {
          detach(pluginId, emitter)
        }
      }
    }
  }

  private fun dispatch(
    pluginId: String,
    event: PluginEvent,
  ) {
    val list = subscribers[pluginId] ?: return
    list.forEach { emitter ->
      if (!replay(emitter, event)) detach(pluginId, emitter)
    }
  }

  private fun replay(
    emitter: SseEmitter,
    event: PluginEvent,
  ): Boolean = send(emitter, SseEmitter.event().id(event.seq.toString()).name("domain").data(toJson(event)))

  private fun send(
    emitter: SseEmitter,
    event: SseEmitter.SseEventBuilder,
  ): Boolean =
    try {
      emitter.send(event)
      true
    } catch (ex: IOException) {
      false
    } catch (ex: IllegalStateException) {
      false
    }

  private fun detach(
    pluginId: String,
    emitter: SseEmitter,
  ) {
    val list = subscribers[pluginId] ?: return
    list.remove(emitter)
    if (list.isEmpty()) subscribers.remove(pluginId, list)
  }

  private fun toJson(event: PluginEvent): String = """{"seq":${event.seq},"type":"${event.type}","id":"${escape(event.id)}","at":"${event.at}"}"""

  private fun escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

  private fun map(event: DomainEvent): Pair<String, String>? =
    when (event) {
      is DomainEvent.LibraryAdded -> "library.added" to event.library.id
      is DomainEvent.LibraryUpdated -> "library.updated" to event.library.id
      is DomainEvent.LibraryDeleted -> "library.deleted" to event.library.id
      is DomainEvent.LibraryScanned -> "library.scanned" to event.library.id
      is DomainEvent.SeriesAdded -> "series.added" to event.series.id
      is DomainEvent.SeriesUpdated -> "series.updated" to event.series.id
      is DomainEvent.SeriesDeleted -> "series.deleted" to event.series.id
      is DomainEvent.BookAdded -> "book.added" to event.book.id
      is DomainEvent.BookUpdated -> "book.updated" to event.book.id
      is DomainEvent.BookDeleted -> "book.deleted" to event.book.id
      is DomainEvent.BookImported -> event.book?.let { "book.imported" to it.id }
      is DomainEvent.CollectionAdded -> "collection.added" to event.collection.id
      is DomainEvent.CollectionUpdated -> "collection.updated" to event.collection.id
      is DomainEvent.CollectionDeleted -> "collection.deleted" to event.collection.id
      is DomainEvent.ReadListAdded -> "readlist.added" to event.readList.id
      is DomainEvent.ReadListUpdated -> "readlist.updated" to event.readList.id
      is DomainEvent.ReadListDeleted -> "readlist.deleted" to event.readList.id
      is DomainEvent.ReadProgressChanged -> "progress.changed" to event.progress.bookId
      is DomainEvent.ReadProgressDeleted -> "progress.deleted" to event.progress.bookId
      is DomainEvent.ReadProgressSeriesChanged -> "progress.series.changed" to event.seriesId
      is DomainEvent.ReadProgressSeriesDeleted -> "progress.series.deleted" to event.seriesId
      else -> null
    }

  companion object {
    const val BUFFER_SIZE: Int = 512
    const val MAX_SUBSCRIBERS: Int = 2
    private const val TIMEOUT_MS: Long = 3_600_000L
  }
}

class PluginSubscriptionException(message: String) : RuntimeException(message)
