package org.lantai.interfaces.mvc

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class IndexController(
  servletContext: ServletContext,
  private val buildProperties: ObjectProvider<BuildProperties>,
) {
  private val baseUrl: String = "${servletContext.contextPath}/"

  @GetMapping("/")
  @Operation(summary = "Serve the web interface")
  fun index(
    model: Model,
    response: HttpServletResponse,
  ): String {
    response.setHeader("Cache-Control", "no-store, must-revalidate")
    response.setHeader("Pragma", "no-cache")
    model.addAttribute("baseUrl", baseUrl)
    model.addAttribute("version", buildProperties.getIfAvailable()?.version ?: "unknown")

    return "index-lantai"
  }
}
