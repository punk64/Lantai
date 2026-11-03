package org.lantai.application.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.infrastructure.configuration.LantaiSettingsProvider
import org.lantai.infrastructure.configuration.SettingChangedEvent
import org.lantai.infrastructure.jooq.tasks.TASK_HISTORY_STATUS_DONE
import org.lantai.infrastructure.jooq.tasks.TASK_HISTORY_STATUS_FAILED
import org.lantai.infrastructure.jooq.tasks.TaskHistoryRow
import org.lantai.infrastructure.jooq.tasks.TasksHistoryDao
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.context.event.EventListener
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private val logger = KotlinLogging.logger {}
private const val MAX_ERROR_LENGTH = 500

@Service
class TaskProcessor(
  private val tasksRepository: TasksRepository,
  private val taskHandler: TaskHandler,
  private val tasksHistoryDao: TasksHistoryDao,
  private val settingsProvider: LantaiSettingsProvider,
  taskExecutorBuilder: ThreadPoolTaskExecutorBuilder,
) : InitializingBean,
  DisposableBean {
  val executor: ThreadPoolTaskExecutor =
    taskExecutorBuilder
      .threadNamePrefix("taskProcessor-")
      .corePoolSize(settingsProvider.taskPoolSize)
      .build()
      .apply { initialize() }

  var processTasks = false

  fun pause() {
    processTasks = false
  }

  fun resume() {
    if (!processTasks) {
      processTasks = true
      processAvailableTask()
    }
  }

  override fun afterPropertiesSet() {
    val disowned = tasksRepository.disown()
    if (disowned > 0)
      logger.info { "Reset $disowned tasks that were not finished" }
    processTasks = true
  }

  override fun destroy() {
    processTasks = false
    executor.destroy()
  }

  @EventListener(SettingChangedEvent.TaskPoolSize::class)
  fun taskPoolSizeChanged() {
    executor.corePoolSize = settingsProvider.taskPoolSize
  }

  @EventListener(TaskAddedEvent::class, ApplicationReadyEvent::class)
  fun processAvailableTask() {
    if (processTasks) {
      logger.debug { "Active count: ${executor.activeCount}, Core Pool Size: ${executor.corePoolSize}, Pool Size: ${executor.poolSize}" }
      if (executor.corePoolSize == 1) {
        executor.execute { takeAndProcess() }
      } else {
        // fan out while threads are available
        while (tasksRepository.hasAvailable() && executor.activeCount < executor.corePoolSize) {
          executor.execute { takeAndProcess() }
        }
      }
    } else {
      logger.debug { "Not processing tasks" }
    }
  }

  private fun takeAndProcess() {
    logger.debug { "Try to process first available task" }
    val task = tasksRepository.takeFirst()
    if (task != null) {
      logger.debug { "Found task to process: $task" }
      val startedAt = LocalDateTime.now(ZoneId.of("Z"))
      val createdDate = tasksHistoryDao.createdDate(task.uniqueId) ?: startedAt
      val failure =
        try {
          taskHandler.handleTask(task)
          null
        } catch (e: Exception) {
          "${e.javaClass.name}: ${e.message}".take(MAX_ERROR_LENGTH)
        }
      val finishedAt = LocalDateTime.now(ZoneId.of("Z"))
      try {
        tasksHistoryDao.save(
          TaskHistoryRow(
            id = UUID.randomUUID().toString(),
            type = task.javaClass.simpleName,
            priority = task.priority,
            groupId = task.groupId,
            status = if (failure == null) TASK_HISTORY_STATUS_DONE else TASK_HISTORY_STATUS_FAILED,
            createdDate = createdDate,
            finishedDate = finishedAt,
            durationMs = Duration.between(startedAt, finishedAt).toMillis(),
            error = failure,
          ),
        )
      } catch (e: Exception) {
        logger.warn(e) { "Could not record history for task $task" }
      }
      logger.debug { "Task processed, remove it from the queue: $task" }
      tasksRepository.delete(task.uniqueId)
      processAvailableTask()
    } else {
      logger.debug { "No available task found" }
    }
  }
}
