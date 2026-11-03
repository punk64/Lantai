package org.lantai.interfaces.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.lang3.RandomStringUtils
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserRoles
import org.lantai.domain.service.LantaiUserLifecycle
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Profile("!test")
@Component
class InitialUserController(
  private val userLifecycle: LantaiUserLifecycle,
  private val initialUsers: List<LantaiUser>,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun createInitialUserOnStartupIfNoneExist() {
    if (userLifecycle.countUsers() == 0L) {
      logger.info { "No users exist in database, creating initial users" }

      initialUsers
        .forEach {
          userLifecycle.createUser(it)
          logger.info {
            """
            |
            |============================================================
            |  兰台已生成管理员账号（仅首次启动生成，请立即记录）
            |  登录名：${it.username}
            |  密  码：${it.password}
            |  登录后请尽快在「账户」中修改密码
            |============================================================
            """.trimMargin()
          }
        }
    }
  }
}

@Configuration
@Profile("dev")
class InitialUsersDevConfiguration {
  @Bean
  fun initialUsers(): List<LantaiUser> =
    listOf(
      LantaiUser("admin@example.org", "admin", roles = UserRoles.entries.toSet()),
      LantaiUser("user@example.org", "user"),
    )
}

@Configuration
@Profile("!dev")
class InitialUsersProdConfiguration {
  @Bean
  fun initialUsers(): List<LantaiUser> =
    listOf(
      LantaiUser(
        password = RandomStringUtils.secure().nextAlphanumeric(24),
        roles = UserRoles.entries.toSet(),
        username = "admin-" + RandomStringUtils.secure().nextAlphanumeric(8).lowercase(),
      ),
    )
}
