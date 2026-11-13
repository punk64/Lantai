package org.lantai.infrastructure.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lantai.domain.model.PluginCapabilities

class PluginP7VerificationTest {
  @Test
  fun `ui panel capability is released`() {
    assertTrue(PluginCapabilities.isKnown("ui.panel"))
    assertFalse(PluginCapabilities.isKnown("ui.panel.evil"))
  }

  @Test
  fun `declared panel entries are parsed`() {
    val manifest = """{"panels":[{"id":"console","title":"控制台","icon":"globe","group":"library","path":"/","roles":["ADMIN"],"titleI18n":{"en":"Console"}}]}"""
    val specs = PluginPanelSpecs.parse(manifest)
    assertEquals(1, specs.size)
    val spec = specs.single()
    assertEquals("console", spec.id)
    assertEquals("控制台", spec.title)
    assertEquals("globe", spec.icon)
    assertEquals("library", spec.group)
    assertEquals("/", spec.path)
    assertEquals(listOf("ADMIN"), spec.roles)
    assertEquals("Console", spec.titleI18n["en"])
  }

  @Test
  fun `malformed declarations never become entries`() {
    assertTrue(PluginPanelSpecs.parse("").isEmpty())
    assertTrue(PluginPanelSpecs.parse("not json").isEmpty())
    assertTrue(PluginPanelSpecs.parse("""{"panels":{}}""").isEmpty())
    assertTrue(PluginPanelSpecs.parse("""{"panels":[{"id":"../bad","title":"x"}]}""").isEmpty())
    assertTrue(PluginPanelSpecs.parse("""{"panels":[{"id":"ok","title":""}]}""").isEmpty())
    assertTrue(PluginPanelSpecs.parse("""{"panels":[{"id":"ok","title":"x","path":"https://evil"}]}""").isEmpty())
    assertTrue(PluginPanelSpecs.parse("""{"panels":[{"id":"ok","title":"x","path":"/../etc"}]}""").isEmpty())
  }

  @Test
  fun `unknown group falls back and duplicates are dropped`() {
    val manifest = """{"panels":[{"id":"a","title":"A","group":"plugins"},{"id":"a","title":"A"},{"id":"b","title":"B"}]}"""
    val specs = PluginPanelSpecs.parse(manifest)
    assertEquals(listOf("a", "b"), specs.map { it.id })
    assertEquals("system", specs[0].group)
    assertEquals(emptyList<String>(), specs[0].roles)
    assertEquals("plug", specs[0].icon)
  }

  @Test
  fun `panel count is bounded`() {
    val entries = (1..12).joinToString(",") { """{"id":"p$it","title":"P$it"}""" }
    assertEquals(PluginPanelSpecs.MAX_PANELS, PluginPanelSpecs.parse("""{"panels":[$entries]}""").size)
  }

  @Test
  fun `panel path rejects traversal and encoded bypasses`() {
    val specs = PluginPanelSpecs.parse("""{"panels":[{"id":"main","title":"Main","path":"/"}]}""")
    assertEquals("/", PluginPanelSpecs.sanitizePath("/", specs))
    assertEquals("/index.html", PluginPanelSpecs.sanitizePath("/index.html", specs))
    assertNull(PluginPanelSpecs.sanitizePath("/../admin", specs))
    assertNull(PluginPanelSpecs.sanitizePath("/%2e%2e/admin", specs))
    assertNull(PluginPanelSpecs.sanitizePath("/a//b", specs))
    assertNull(PluginPanelSpecs.sanitizePath("//evil.example", specs))
    assertNull(PluginPanelSpecs.sanitizePath("/a\\b", specs))
    assertNull(PluginPanelSpecs.sanitizePath("http://evil.example/x", specs))
    assertNull(PluginPanelSpecs.sanitizePath("/%252e%252e/x", specs))
    assertNull(PluginPanelSpecs.sanitizePath("index.html", specs))
  }

  @Test
  fun `panel path stays inside declared base`() {
    val specs = PluginPanelSpecs.parse("""{"panels":[{"id":"app","title":"App","path":"/app"}]}""")
    assertEquals("/app", PluginPanelSpecs.sanitizePath("/app", specs))
    assertEquals("/app/index.html", PluginPanelSpecs.sanitizePath("/app/index.html", specs))
    assertNull(PluginPanelSpecs.sanitizePath("/other", specs))
    assertNull(PluginPanelSpecs.sanitizePath("/application", specs))
  }

  @Test
  fun `panel ticket is single use and bound to session and panel`() {
    val service = PluginPanelTicketService()
    val ticket = service.issue("inst1", "main", "sess1")
    assertNotNull(service.consume(ticket, "inst1", "sess1", "main"))
    assertNull(service.consume(ticket, "inst1", "sess1", "main"))
    assertNull(service.consume(service.issue("inst1", "main", "sess1"), "inst1", "sess2", "main"))
    assertNull(service.consume(service.issue("inst1", "main", "sess1"), "inst1", "sess1", "other"))
    assertNull(service.consume(service.issue("inst1", "main", "sess1"), "inst2", "sess1", "main"))
  }

  @Test
  fun `panel session is bound to instance and revocable`() {
    val service = PluginPanelTicketService()
    val grant = service.consume(service.issue("inst1", "main", "sess1"), "inst1", "sess1", "main")
    assertNotNull(grant)
    val session = service.openSession(grant!!)
    assertNotNull(service.verify(session, "inst1", "sess1"))
    assertNull(service.verify(session, "inst2", "sess1"))
    assertNull(service.verify(session, "inst1", "sess2"))
    service.revokeInstance("inst1")
    assertNull(service.verify(session, "inst1", "sess1"))
  }
}
