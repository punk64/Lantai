package org.lantai.interfaces.api.rest.dto

import com.fasterxml.jackson.annotation.JsonInclude
import org.lantai.domain.model.AgeRestriction
import org.lantai.domain.model.AllowExclude
import org.lantai.domain.model.LantaiUser
import org.lantai.infrastructure.security.LantaiPrincipal

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserDto(
  val id: String,
  val username: String,
  val email: String? = null,
  val roles: Set<String>,
  val sharedAllLibraries: Boolean,
  val sharedLibrariesIds: Set<String>,
  val labelsAllow: Set<String>,
  val labelsExclude: Set<String>,
  val ageRestriction: AgeRestrictionDto?,
)

data class AgeRestrictionDto(
  val age: Int,
  val restriction: AllowExclude,
)

fun AgeRestriction.toDto() = AgeRestrictionDto(age, restriction)

fun LantaiUser.toDto() =
  UserDto(
    id = id,
    username = username,
    email = email,
    roles = roles.map { it.name }.toSet() + "USER",
    sharedAllLibraries = sharedAllLibraries,
    sharedLibrariesIds = sharedLibrariesIds,
    labelsAllow = restrictions.labelsAllow,
    labelsExclude = restrictions.labelsExclude,
    ageRestriction = restrictions.ageRestriction?.toDto(),
  )

fun LantaiPrincipal.toDto() = user.toDto()
