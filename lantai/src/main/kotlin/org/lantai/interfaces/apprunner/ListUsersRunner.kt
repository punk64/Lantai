package org.lantai.interfaces.apprunner

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.persistence.LantaiUserRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Profile("!test")
@Component
class ListUsersRunner(
  private val userRepository: LantaiUserRepository,
) : ApplicationRunner {
  override fun run(args: ApplicationArguments) {
    if (args.getOptionValues("list-users") != null) {
      val logins = userRepository.findAll().map { it.username }
      if (logins.isNotEmpty())
        logger.info { "Here is a list of all users: $logins" }
      else
        logger.info { "No users exist yet" }
    }
  }
}
