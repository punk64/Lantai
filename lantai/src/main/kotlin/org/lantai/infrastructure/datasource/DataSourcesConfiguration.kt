package org.lantai.infrastructure.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.lantai.infrastructure.configuration.LantaiProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import javax.sql.DataSource

@Configuration
class DataSourcesConfiguration(
  private val lantaiProperties: LantaiProperties,
) {
  @Bean("sqliteDataSourceRW")
  @Primary
  fun sqliteDataSourceRW(): DataSource =
    buildDataSource("SqliteMainPoolRW", SqliteUdfDataSource::class.java, lantaiProperties.database)
      .apply {
        // force pool size to 1 if the pool is only used for writes
        if (lantaiProperties.database.shouldSeparateReadFromWrites()) this.maximumPoolSize = 1
      }

  @Bean("sqliteDataSourceRO")
  fun sqliteDataSourceRO(): DataSource =
    if (lantaiProperties.database.shouldSeparateReadFromWrites())
      buildDataSource("SqliteMainPoolRO", SqliteUdfDataSource::class.java, lantaiProperties.database)
    else
      sqliteDataSourceRW()

  @Bean("tasksDataSourceRW")
  fun tasksDataSourceRW(): DataSource =
    buildDataSource("SqliteTasksPoolRW", SQLiteDataSource::class.java, lantaiProperties.tasksDb)
      .apply {
        // pool size is always 1:
        // - if there's only 1 pool for read and writes, size should be 1
        // - if there's a separate read pool, the write pool size should be 1
        this.maximumPoolSize = 1
      }

  @Bean("tasksDataSourceRO")
  fun tasksDataSourceRO(): DataSource =
    if (lantaiProperties.tasksDb.shouldSeparateReadFromWrites())
      buildDataSource("SqliteTasksPoolRO", SQLiteDataSource::class.java, lantaiProperties.tasksDb)
    else
      tasksDataSourceRW()

  @ConditionalOnProperty(
    value = ["lantai.jdbc-session-enabled"],
    havingValue = "true",
    matchIfMissing = false,
  )
  @Bean("sessionsDataSource")
  @SpringSessionDataSource
  fun sessionsDataSource(): DataSource = buildDataSource("SqliteSessionsPool", SQLiteDataSource::class.java, lantaiProperties.sessionsDb)

  private fun buildDataSource(
    poolName: String,
    dataSourceClass: Class<out SQLiteDataSource>,
    databaseProps: LantaiProperties.Database,
  ): HikariDataSource {
    val extraPragmas =
      databaseProps.pragmas.let {
        if (it.isEmpty()) {
          ""
        } else {
          (if (databaseProps.file.contains("?")) "&" else "?") + it.map { (key, value) -> "$key=$value" }.joinToString(separator = "&")
        }
      }

    val dataSource =
      DataSourceBuilder
        .create()
        .driverClassName("org.sqlite.JDBC")
        .url("jdbc:sqlite:${databaseProps.file}$extraPragmas")
        .type(dataSourceClass)
        .build()

    with(dataSource) {
      setEnforceForeignKeys(true)
      setGetGeneratedKeys(false)
    }
    with(databaseProps) {
      journalMode?.let { dataSource.setJournalMode(it.name) }
      busyTimeout?.let { dataSource.config.busyTimeout = it.toMillis().toInt() }
    }

    val poolSize =
      if (databaseProps.isMemory())
        1
      else if (databaseProps.poolSize != null)
        databaseProps.poolSize!!
      else
        Runtime.getRuntime().availableProcessors().coerceAtMost(databaseProps.maxPoolSize)

    return HikariDataSource(
      HikariConfig().apply {
        this.dataSource = dataSource
        this.poolName = poolName
        this.maximumPoolSize = poolSize
        this.connectionTimeout = CONNECTION_TIMEOUT_MS
        this.validationTimeout = VALIDATION_TIMEOUT_MS
        this.leakDetectionThreshold = LEAK_DETECTION_MS
      },
    )
  }

  fun LantaiProperties.Database.isMemory() = file.contains(":memory:") || file.contains("mode=memory")

  fun LantaiProperties.Database.shouldSeparateReadFromWrites(): Boolean = !isMemory() && journalMode == SQLiteConfig.JournalMode.WAL

  private companion object {
    const val CONNECTION_TIMEOUT_MS = 5_000L
    const val VALIDATION_TIMEOUT_MS = 3_000L
    const val LEAK_DETECTION_MS = 20_000L
  }
}
