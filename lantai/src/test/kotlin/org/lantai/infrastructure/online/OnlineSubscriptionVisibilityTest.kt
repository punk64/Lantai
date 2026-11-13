package org.lantai.infrastructure.online

import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.lantai.infrastructure.jooq.main.LantaiNavCountsDao
import org.lantai.jooq.main.Tables
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("memorydb")
class OnlineSubscriptionVisibilityTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val dsl: DSLContext,
  @Autowired private val countsDao: LantaiNavCountsDao,
) {
  private val mapper = ObjectMapper()
  private val admin = user("admin").roles("ADMIN")
  private val now: LocalDateTime = LocalDateTime.now()

  @Test
  fun disabledPluginHidesItsSubscriptions() {
    reset()

    seedPlugin("vis.enabled", enabled = true, instanceId = "VISINST0001", isDefault = true)
    seedPlugin("vis.disabled", enabled = false, instanceId = "VISINST0002", isDefault = false)
    seedSubscription("VISMAGNA0001", "visseries0001", "vis.enabled", "启用插件的作品")
    seedSubscription("VISMAGNA0002", "visseries0002", "vis.disabled", "停用插件的作品")

    assertEquals(1, counts())
    assertEquals(listOf("启用插件的作品"), subscriptionTitles())

    dsl.update(Tables.PLUGIN_INSTANCE)
      .set(Tables.PLUGIN_INSTANCE.ENABLED, true)
      .where(Tables.PLUGIN_INSTANCE.ID.eq("VISINST0002"))
      .execute()

    assertEquals(2, counts())
    assertEquals(2, subscriptionTitles().size)

    mockMvc.perform(delete("/api/lantai/v1/plugins/VISINST0002").with(admin)).andExpect(status().isNoContent)

    assertEquals(1, counts())
    assertEquals(listOf("启用插件的作品"), subscriptionTitles())
    assertEquals(
      0,
      dsl.fetchCount(Tables.ONLINE_MANGA, Tables.ONLINE_MANGA.SOURCE_KIND.eq("plugin:vis.disabled")),
    )
  }

  private fun reset() {
    dsl.deleteFrom(Tables.ONLINE_CHAPTER).execute()
    dsl.deleteFrom(Tables.ONLINE_MANGA).execute()
    dsl.deleteFrom(Tables.PLUGIN_GRANT).execute()
    dsl.deleteFrom(Tables.PLUGIN_INSTANCE).execute()
    dsl.deleteFrom(Tables.SERIES).execute()
    dsl.deleteFrom(Tables.LIBRARY).execute()
    val l = Tables.LIBRARY
    dsl.insertInto(l)
      .columns(l.ID, l.NAME, l.ROOT, l.CREATED_DATE, l.LAST_MODIFIED_DATE)
      .values("VISLIBRARY0001", "vis", "file:/vis/", now, now)
      .execute()
  }

  private fun seedPlugin(
    pluginId: String,
    enabled: Boolean,
    instanceId: String,
    isDefault: Boolean,
  ) {
    val p = Tables.PLUGIN_INSTANCE
    dsl.insertInto(p)
      .columns(
        p.ID,
        p.PLUGIN_ID,
        p.NAME,
        p.VERSION,
        p.API_VERSION,
        p.MODE,
        p.RUNTIME_KIND,
        p.ENTRY,
        p.STATE,
        p.CAPABILITIES,
        p.MANIFEST,
        p.HOME_DIR,
        p.MEMORY_MB,
        p.ENABLED,
        p.CREATED_DATE,
        p.LAST_MODIFIED_DATE,
      )
      .values(
        instanceId,
        pluginId,
        pluginId,
        "1.0.0",
        1,
        "EXTERNAL",
        "PROCESS",
        "plugin.jar",
        "STOPPED",
        "[\"source.provide\"]",
        "{}",
        "/tmp/$instanceId",
        0,
        enabled,
        now,
        now,
      )
      .execute()
    val g = Tables.PLUGIN_GRANT
    dsl.insertInto(g)
      .columns(g.ID, g.PLUGIN_ID, g.CAPABILITY, g.IS_DEFAULT, g.CREATED_DATE, g.LAST_MODIFIED_DATE)
      .values("VISGRANT$instanceId", pluginId, "source.provide", isDefault, now, now)
      .execute()
  }

  private fun seedSubscription(
    mangaId: String,
    seriesId: String,
    pluginId: String,
    title: String,
  ) {
    val s = Tables.SERIES
    dsl.insertInto(s)
      .columns(s.ID, s.FILE_LAST_MODIFIED, s.NAME, s.URL, s.LIBRARY_ID, s.CREATED_DATE, s.LAST_MODIFIED_DATE)
      .values(seriesId, now, title, "file:/vis/$seriesId", "VISLIBRARY0001", now, now)
      .execute()
    val m = Tables.ONLINE_MANGA
    dsl.insertInto(m)
      .columns(
        m.ID,
        m.SERIES_ID,
        m.SOURCE_KIND,
        m.REMOTE_MANGA_ID,
        m.TITLE,
        m.STATUS,
        m.CREATED_DATE,
        m.LAST_MODIFIED_DATE,
      )
      .values(mangaId, seriesId, "plugin:$pluginId", mangaId, title, "ONGOING", now, now)
      .execute()
  }

  private fun counts(): Int = countsDao.counts("admin").onlineSeries

  private fun subscriptionTitles(): List<String> {
    val body =
      mockMvc
        .perform(get("/api/lantai/v1/online/subscriptions").with(admin))
        .andExpect(status().isOk)
        .andReturn()
        .response
        .contentAsString
    return mapper.readTree(body).map { it.path("title").asText() }
  }
}
