package org.lantai.infrastructure.online

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.lantai.domain.model.OnlineCacheItem
import org.lantai.domain.model.OnlineCacheJob
import org.lantai.domain.persistence.OnlineCacheJobRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

private val logger = KotlinLogging.logger {}

@Service
class OnlineCacheJobService(
  private val repository: OnlineCacheJobRepository,
  private val registry: OnlineSourceRegistry,
) {
  private val worker = Semaphore(1)
  private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "online-cache-job").apply { isDaemon = true } }

  @EventListener(ApplicationReadyEvent::class)
  fun resumeInterrupted() {
    repository.findJobsByStatus(OnlineCacheJob.Status.RUNNING).forEach { job ->
      repository.resetRunningItems(job.id)
      repository.updateJob(job.copy(status = OnlineCacheJob.Status.PENDING))
    }
    kick()
  }

  @PreDestroy
  fun shutdown() {
    executor.shutdownNow()
  }

  fun create(
    seriesId: String,
    bookIds: Collection<String>?,
  ): OnlineCacheJob {
    val active = repository.findJobsBySeriesId(seriesId).firstOrNull {
      it.status == OnlineCacheJob.Status.PENDING || it.status == OnlineCacheJob.Status.RUNNING || it.status == OnlineCacheJob.Status.PAUSED
    }
    if (active != null) throw OnlineException("该系列已有缓存任务在进行中，请先暂停或取消")

    val chapters = registry.default().chapters(seriesId)
    if (chapters.isEmpty()) throw OnlineException("该系列没有可缓存的章节")

    val wanted =
      if (bookIds.isNullOrEmpty()) {
        chapters.filter { !it.downloaded }
      } else {
        val wantedIds = bookIds.toSet()
        chapters.filter { it.bookId in wantedIds }
      }
    if (wanted.isEmpty()) throw OnlineException("没有需要缓存的章节")

    val cached = wanted.count { it.downloaded }
    val job =
      OnlineCacheJob(
        seriesId = seriesId,
        status = OnlineCacheJob.Status.PENDING,
        total = wanted.size,
        done = cached,
        failed = 0,
      )
    repository.insertJob(job)
    repository.insertItems(
      wanted.map { info ->
        OnlineCacheItem(
          jobId = job.id,
          bookId = info.bookId,
          chapIndex = info.index,
          title = info.title,
          status = if (info.downloaded) OnlineCacheItem.Status.SKIPPED else OnlineCacheItem.Status.PENDING,
        )
      },
    )
    kick()
    return job
  }

  fun list(seriesId: String): Collection<OnlineCacheJob> = repository.findJobsBySeriesId(seriesId)

  fun get(jobId: String): OnlineCacheJob = repository.findJobByIdOrNull(jobId) ?: throw OnlineException("缓存任务不存在：$jobId")

  fun items(
    jobId: String,
    page: Int,
    size: Int,
  ): Pair<Long, Collection<OnlineCacheItem>> {
    val job = get(jobId)
    val total = repository.countItems(job.id)
    return total to repository.pageItems(job.id, page.toLong() * size, size)
  }

  fun pause(jobId: String) {
    val job = get(jobId)
    if (job.status != OnlineCacheJob.Status.PENDING && job.status != OnlineCacheJob.Status.RUNNING) {
      throw OnlineException("当前状态无法暂停")
    }
    repository.updateJob(job.copy(status = OnlineCacheJob.Status.PAUSED))
  }

  fun resume(jobId: String) {
    val job = get(jobId)
    if (job.status != OnlineCacheJob.Status.PAUSED && job.status != OnlineCacheJob.Status.CANCELLED) {
      throw OnlineException("当前状态无法继续")
    }
    repository.updateJob(job.copy(status = OnlineCacheJob.Status.PENDING))
    kick()
  }

  fun cancel(jobId: String) {
    val job = get(jobId)
    if (job.status == OnlineCacheJob.Status.DONE) throw OnlineException("任务已完成")
    repository.updateJob(job.copy(status = OnlineCacheJob.Status.CANCELLED))
  }

  fun retry(jobId: String) {
    val job = get(jobId)
    if (job.status == OnlineCacheJob.Status.RUNNING) throw OnlineException("任务正在运行")
    repository.resetFailedItems(job.id)
    val items = repository.findItemsByJobId(job.id)
    repository.updateJob(
      job.copy(
        status = OnlineCacheJob.Status.PENDING,
        done = items.count { it.status == OnlineCacheItem.Status.DONE || it.status == OnlineCacheItem.Status.SKIPPED },
        failed = 0,
      ),
    )
    kick()
  }

  fun deleteBySeries(seriesId: String) {
    repository.deleteBySeriesId(seriesId)
  }

  fun kick() {
    if (!worker.tryAcquire()) return
    executor.submit {
      try {
        drain()
      } finally {
        worker.release()
      }
    }
  }

  private fun drain() {
    while (true) {
      val job = repository.findRunnableJob() ?: return
      try {
        runJob(job)
      } catch (e: Exception) {
        logger.warn(e) { "Online cache job ${job.id} aborted" }
        repository.updateJob(job.copy(status = OnlineCacheJob.Status.CANCELLED))
      }
    }
  }

  private fun runJob(job: OnlineCacheJob) {
    repository.updateJob(job.copy(status = OnlineCacheJob.Status.RUNNING))
    var done = job.done
    var failed = job.failed
    while (true) {
      val live = repository.findJobByIdOrNull(job.id) ?: return
      if (live.status != OnlineCacheJob.Status.RUNNING) return
      val item = repository.findNextPendingItem(job.id) ?: break
      repository.updateItem(item.copy(status = OnlineCacheItem.Status.RUNNING))
      try {
        val result = registry.default().download(item.bookId)
        repository.updateItem(
          item.copy(
            status = OnlineCacheItem.Status.DONE,
            pageCount = result.pageCount,
            sizeBytes = result.sizeBytes,
            error = "",
          ),
        )
        done++
      } catch (e: Exception) {
        logger.warn(e) { "Online cache failed for ${item.bookId}" }
        repository.updateItem(
          item.copy(
            status = OnlineCacheItem.Status.FAILED,
            error = (e.message ?: "下载失败").take(500),
          ),
        )
        failed++
      }
      repository.updateJob(live.copy(done = done, failed = failed))
    }
    val finished = repository.findJobByIdOrNull(job.id) ?: return
    if (finished.status == OnlineCacheJob.Status.RUNNING) {
      val items = repository.findItemsByJobId(job.id)
      repository.updateJob(
        finished.copy(
          status = OnlineCacheJob.Status.DONE,
          done = items.count { it.status == OnlineCacheItem.Status.DONE || it.status == OnlineCacheItem.Status.SKIPPED },
          failed = items.count { it.status == OnlineCacheItem.Status.FAILED },
        ),
      )
    }
  }
}
