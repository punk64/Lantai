package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserRoles
import org.lantai.domain.service.LantaiUserLifecycle
import org.lantai.infrastructure.openapi.OpenApiConfiguration
import org.lantai.interfaces.api.rest.dto.UserDto
import org.lantai.interfaces.api.rest.dto.toDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/v1/claim", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = OpenApiConfiguration.TagNames.CLAIM)
@Validated
@SecurityRequirements
class ClaimController(
  private val userDetailsLifecycle: LantaiUserLifecycle,
) {
  @GetMapping
  @Operation(summary = "Retrieve claim status", description = "Check whether this server has already been claimed.")
  fun getClaimStatus() = ClaimStatus(userDetailsLifecycle.countUsers() > 0)

  @PostMapping
  @Operation(summary = "Claim server", description = "Creates an admin user with the provided credentials.")
  fun claimServer(
    @Email(regexp = ".+@.+\\..+")
    @RequestHeader("X-Lantai-Email", required = false)
    email: String?,
    @RequestHeader("X-Lantai-Username", required = false)
    username: String?,
    @NotBlank
    @RequestHeader("X-Lantai-Password")
    password: String,
  ): UserDto {
    if (userDetailsLifecycle.countUsers() > 0)
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This server has already been claimed")

    val login = username?.trim()?.takeIf { it.isNotEmpty() }
    val mail = email?.trim()?.takeIf { it.isNotEmpty() }
    if (login == null && mail == null)
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A username or an email is required")

    return userDetailsLifecycle
      .createUser(
        LantaiUser(
          email = mail,
          password = password,
          roles = UserRoles.entries.toSet(),
          username = login ?: mail!!.substringBefore('@'),
        ),
      ).toDto()
  }

  data class ClaimStatus(
    val isClaimed: Boolean,
  )
}
