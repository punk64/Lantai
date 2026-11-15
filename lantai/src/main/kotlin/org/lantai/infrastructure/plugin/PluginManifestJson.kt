package org.lantai.infrastructure.plugin

import com.fasterxml.jackson.databind.JsonNode

internal fun readCapabilityNames(node: JsonNode): List<String> =
  when {
    node.isArray -> node.mapNotNull { capabilityNameOf(it) }
    node.isObject -> node.fieldNames().asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    else -> emptyList()
  }.distinct()

internal fun readCapabilityEndpoint(
  node: JsonNode,
  capability: String,
): String? =
  node.path(capability).asText("").trim()
    .takeIf { it.startsWith("/") && !it.startsWith("//") && !it.contains("..") }

private fun capabilityNameOf(node: JsonNode): String? =
  node.textValue()?.trim()?.takeIf { it.isNotEmpty() }
    ?: node.path("name").asText("").trim().takeIf { it.isNotEmpty() }
