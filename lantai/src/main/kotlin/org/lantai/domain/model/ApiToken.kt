package org.lantai.domain.model

import com.github.f4b6a3.tsid.TsidCreator
import java.time.LocalDateTime

data class ApiToken(
  val name: String,
  val ownerId: String,
  val tokenValue: String,
  val scopes: Set<Scope> = setOf(Scope.LIBRARY_READ),
  val enabled: Boolean = true,
  val expiresDate: LocalDateTime? = null,
  val lastUsedDate: LocalDateTime? = null,
  val lastUsedIp: String? = null,
  val useCount: Int = 0,
  val id: String = TsidCreator.getTsid256().toString(),
  override val createdDate: LocalDateTime = LocalDateTime.now(),
  override val lastModifiedDate: LocalDateTime = createdDate,
) : Auditable {
  fun isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean = expiresDate != null && expiresDate.isBefore(now)

  fun isUsable(now: LocalDateTime = LocalDateTime.now()): Boolean = enabled && !isExpired(now)

  enum class Scope(val label: String, val display: String) {
    LIBRARY_READ("library:read", "浏览书库与书籍信息"),
    BOOKS_DOWNLOAD("books:download", "下载书籍原文件"),
    PAGES_STREAM("pages:stream", "读取页图与阅读资源"),
    METADATA_READ("metadata:read", "读取元数据与批量清单"),
    METADATA_WRITE("metadata:write", "写入单册与批量元数据"),
    PROGRESS_WRITE("progress:write", "写入阅读进度与标注"),
    OPDS_READ("opds:read", "访问 OPDS 目录"),
    KOBO_SYNC("kobo:sync", "Kobo 设备同步"),
    KOREADER_SYNC("koreader:sync", "KOReader 同步"),
    SCRAPE_READ("scrape:read", "读取刮削名单"),
    SCRAPE_SUBMIT("scrape:submit", "提交刮削结果"),
    ADMIN("admin", "管理权限，包含全部操作"),
    ;

    val isScrapeOnly: Boolean get() = this == SCRAPE_READ || this == SCRAPE_SUBMIT

    companion object {
      fun parse(values: Iterable<String>): Set<Scope> =
        values
          .mapNotNull {
            val value = it.trim()
            entries.firstOrNull { scope -> scope.label.equals(value, ignoreCase = true) || scope.name.equals(value, ignoreCase = true) }
          }.toSet()

      fun fromStored(stored: String): Set<Scope> = parse(stored.split(','))
    }
  }
}
