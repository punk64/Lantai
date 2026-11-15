package org.lantai.infrastructure.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.lantai.infrastructure.plugin.PluginRiskGate
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class PluginRiskGateInterceptor(
  private val gate: PluginRiskGate,
) : HandlerInterceptor {
  override fun preHandle(
    request: HttpServletRequest,
    response: HttpServletResponse,
    handler: Any,
  ): Boolean {
    if (gate.acknowledged()) return true
    if (request.requestURI.endsWith(RISK_ACK_PATH)) return true
    response.status = HttpServletResponse.SC_FORBIDDEN
    response.contentType = "application/json"
    response.writer.write("""{"code":"PLUGIN_RISK_ACK_REQUIRED"}""")
    return false
  }

  companion object {
    const val RISK_ACK_PATH = "/api/lantai/v1/plugins/risk-ack"
  }
}
