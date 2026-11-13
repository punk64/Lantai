package org.lantai.infrastructure.datasource

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

@Component
class LantaiFlywayMigrationStrategy : FlywayMigrationStrategy {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun migrate(flyway: Flyway) {
    realignLegacyChecksums(flyway.configuration.dataSource)
    flyway.migrate()
  }

  private fun realignLegacyChecksums(dataSource: DataSource) {
    dataSource.connection.use { connection ->
      if (!historyTableExists(connection)) return
      connection.autoCommit = true
      for (entry in LEGACY_CHECKSUMS) {
        val updated =
          connection
            .prepareStatement(
              "UPDATE flyway_schema_history SET checksum = ?, description = ? WHERE version = ? AND checksum = ?",
            )
            .use { statement ->
              statement.setInt(1, entry.currentChecksum)
              statement.setString(2, entry.currentDescription)
              statement.setString(3, entry.version)
              statement.setInt(4, entry.legacyChecksum)
              statement.executeUpdate()
            }
        if (updated > 0) log.info("已对齐历史迁移校验和：version={} checksum={}", entry.version, entry.currentChecksum)
      }
    }
  }

  private fun historyTableExists(connection: Connection): Boolean =
    connection.metaData.getTables(null, null, HISTORY_TABLE, arrayOf("TABLE")).use { it.next() }

  private data class LegacyChecksum(
    val version: String,
    val legacyChecksum: Int,
    val currentChecksum: Int,
    val currentDescription: String,
  )

  companion object {
    private const val HISTORY_TABLE = "flyway_schema_history"

    private val LEGACY_CHECKSUMS =
      listOf(
        LegacyChecksum("20261031071500", -1596119447, -1930239595, "lantai online library"),
        LegacyChecksum("20261112093000", 1640805236, 570569561, "lantai online drop legacy sources"),
      )
  }
}
