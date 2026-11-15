package org.lantai.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.interfaces.api.rest.dto.ClientSettingDto
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ClientSettingsDtoDaoTest(
  @Autowired private val clientSettingsDtoDao: ClientSettingsDtoDao,
  @Autowired private val userRepository: LantaiUserRepository,
) {
  private val user1 = LantaiUser("user1@example.org", "")
  private val user2 = LantaiUser("user2@example.org", "")

  @BeforeAll
  fun setup() {
    userRepository.insert(user1)
    userRepository.insert(user2)
  }

  @AfterEach
  fun cleanup() {
    clientSettingsDtoDao.deleteAll()
  }

  @AfterAll
  fun tearDown() {
    userRepository.deleteAll()
  }

  @Nested
  inner class Global {
    @Test
    fun `when saving global setting then it is persisted`() {
      clientSettingsDtoDao.saveGlobal("setting1", "value1", true)
      clientSettingsDtoDao.saveGlobal("setting2", "value2", false)

      val fetch = clientSettingsDtoDao.findAllGlobal()

      assertThat(fetch).containsAllEntriesOf(
        mapOf(
          "setting1" to ClientSettingDto("value1", true),
          "setting2" to ClientSettingDto("value2", false),
        ),
      )
    }

    @Test
    fun `given existing global setting when saving global setting then it is updated`() {
      clientSettingsDtoDao.saveGlobal("setting1", "value1", true)
      clientSettingsDtoDao.saveGlobal("setting1", "updated", true)
      val fetch = clientSettingsDtoDao.findAllGlobal()

      assertThat(fetch).containsAllEntriesOf(
        mapOf(
          "setting1" to ClientSettingDto("updated", true),
        ),
      )
    }

    @Test
    fun `given existing global setting when deleting global setting then it is deleted`() {
      clientSettingsDtoDao.saveGlobal("setting1", "value1", true)

      clientSettingsDtoDao.deleteGlobalByKeys(listOf("setting1"))

      val fetch = clientSettingsDtoDao.findAllGlobal()

      assertThat(fetch).isEmpty()
    }
  }

  @Nested
  inner class User {
    @Test
    fun `when saving user setting then it is persisted`() {
      clientSettingsDtoDao.saveForUser(user1.id, "setting1", "value1")
      clientSettingsDtoDao.saveForUser(user2.id, "setting2", "value2")

      val fetch = clientSettingsDtoDao.findAllUser(user1.id)

      assertThat(fetch).containsAllEntriesOf(
        mapOf(
          "setting1" to ClientSettingDto("value1", null),
        ),
      )
    }

    @Test
    fun `given existing user setting when saving user setting then it is updated`() {
      clientSettingsDtoDao.saveForUser(user1.id, "setting1", "value1")
      clientSettingsDtoDao.saveForUser(user1.id, "setting1", "updated")

      val fetch = clientSettingsDtoDao.findAllUser(user1.id)

      assertThat(fetch).containsAllEntriesOf(
        mapOf(
          "setting1" to ClientSettingDto("updated", null),
        ),
      )
    }

    @Test
    fun `given existing user setting when deleting user setting then it is deleted`() {
      clientSettingsDtoDao.saveForUser(user1.id, "setting1", "value1")

      clientSettingsDtoDao.deleteByUserIdAndKeys(user1.id, listOf("setting1"))

      val fetch = clientSettingsDtoDao.findAllUser(user1.id)

      assertThat(fetch).isEmpty()
    }
  }
}
