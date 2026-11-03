package org.lantai.interfaces.apprunner

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.domain.service.LantaiUserLifecycle
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Profile("!test")
@Component
class PasswordResetRunner(
  private val userRepository: LantaiUserRepository,
  private val userLifecycle: LantaiUserLifecycle,
) : ApplicationRunner {
  private val resetFor = "reset"
  private val resetTo = "newpassword"

  override fun run(args: ApplicationArguments) {
    val newPassword = args.getOptionValues(resetTo)?.firstOrNull()
    val resetFor = args.getOptionValues(resetFor)?.toSet() ?: emptySet()

    if (resetFor.isEmpty() xor (newPassword == null))
      return logger.warn { "You need to specify both '--${this.resetFor}=username' and '--$resetTo=YourNewPassword'" }

    if (resetFor.isEmpty()) return

    if (newPassword.isNullOrBlank())
      return logger.warn { "The new password must not be blank" }

    resetFor
      .forEach { arg ->
        userRepository.findByLoginOrNull(arg)?.let { user ->
          logger.info { "Reset password for user: ${user.username}" }
          userLifecycle.updatePassword(user, newPassword, true)
        } ?: logger.warn { "User does not exist: $arg" }
      }
  }
}
