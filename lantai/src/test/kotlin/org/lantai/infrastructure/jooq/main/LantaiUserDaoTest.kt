package org.lantai.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.lantai.domain.model.AgeRestriction
import org.lantai.domain.model.AllowExclude
import org.lantai.domain.model.ContentRestrictions
import org.lantai.domain.model.LantaiUser
import org.lantai.domain.model.UserRoles
import org.lantai.domain.model.makeLibrary
import org.lantai.domain.persistence.LibraryRepository
import org.lantai.infrastructure.jooq.offset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDateTime

@SpringBootTest
class LantaiUserDaoTest(
  @Autowired private val lantaiUserDao: LantaiUserDao,
  @Autowired private val libraryRepository: LibraryRepository,
) {
  private val library = makeLibrary()

  @BeforeAll
  fun setup() {
    libraryRepository.insert(library)
  }

  @AfterEach
  fun deleteUsers() {
    lantaiUserDao.deleteAll()
    assertThat(lantaiUserDao.count()).isEqualTo(0)
  }

  @AfterAll
  fun tearDown() {
    libraryRepository.deleteAll()
  }

  @Test
  fun `given a user when saving it then it is persisted`() {
    val now = LocalDateTime.now()
    val user =
      LantaiUser(
        email = "user@example.org",
        password = "password",
        sharedLibrariesIds = setOf(library.id),
        sharedAllLibraries = false,
      )

    lantaiUserDao.insert(user)
    val created = lantaiUserDao.findByIdOrNull(user.id)!!

    with(created) {
      assertThat(id).isNotEqualTo(0)
      assertThat(createdDate).isCloseTo(now, offset)
      assertThat(lastModifiedDate).isCloseTo(now, offset)
      assertThat(email).isEqualTo("user@example.org")
      assertThat(password).isEqualTo("password")
      assertThat(roles).containsExactlyInAnyOrder(UserRoles.FILE_DOWNLOAD, UserRoles.PAGE_STREAMING)
      assertThat(sharedLibrariesIds).containsExactly(library.id)
      assertThat(sharedAllLibraries).isFalse
      assertThat(restrictions.ageRestriction).isNull()
      assertThat(restrictions.labelsAllow).isEmpty()
      assertThat(restrictions.labelsExclude).isEmpty()
    }
  }

  @Test
  fun `given existing user when modifying and saving it then it is persisted`() {
    val user =
      LantaiUser(
        email = "user@example.org",
        password = "password",
        sharedLibrariesIds = setOf(library.id),
        sharedAllLibraries = false,
        restrictions =
          ContentRestrictions(
            ageRestriction = AgeRestriction(10, AllowExclude.ALLOW_ONLY),
            labelsAllow = setOf("allow"),
            labelsExclude = setOf("exclude"),
          ),
      )

    lantaiUserDao.insert(user)
    val created = lantaiUserDao.findByIdOrNull(user.id)!!
    with(created) {
      assertThat(restrictions.ageRestriction).isNotNull
      assertThat(restrictions.ageRestriction!!.age).isEqualTo(10)
      assertThat(restrictions.ageRestriction.restriction).isEqualTo(AllowExclude.ALLOW_ONLY)
      assertThat(restrictions.labelsAllow).containsExactly("allow")
      assertThat(restrictions.labelsExclude).containsExactly("exclude")
    }

    val modified =
      created.copy(
        email = "user2@example.org",
        password = "password2",
        roles = setOf(UserRoles.ADMIN, UserRoles.FILE_DOWNLOAD, UserRoles.PAGE_STREAMING, UserRoles.KOBO_SYNC),
        sharedLibrariesIds = emptySet(),
        sharedAllLibraries = true,
        restrictions =
          ContentRestrictions(
            ageRestriction = AgeRestriction(16, AllowExclude.EXCLUDE),
            labelsAllow = setOf("allow2"),
            labelsExclude = setOf("exclude2"),
          ),
      )
    val modifiedDate = LocalDateTime.now()
    lantaiUserDao.update(modified)
    val modifiedSaved = lantaiUserDao.findByIdOrNull(modified.id)!!

    with(modifiedSaved) {
      assertThat(id).isEqualTo(created.id)
      assertThat(createdDate).isEqualTo(created.createdDate)
      assertThat(lastModifiedDate)
        .isCloseTo(modifiedDate, offset)
        .isNotEqualTo(modified.createdDate)
      assertThat(email).isEqualTo("user2@example.org")
      assertThat(password).isEqualTo("password2")
      assertThat(roles).containsExactlyInAnyOrder(UserRoles.ADMIN, UserRoles.FILE_DOWNLOAD, UserRoles.PAGE_STREAMING, UserRoles.KOBO_SYNC)
      assertThat(sharedLibrariesIds).isEmpty()
      assertThat(sharedAllLibraries).isTrue
      assertThat(restrictions.ageRestriction).isNotNull
      assertThat(restrictions.ageRestriction!!.age).isEqualTo(16)
      assertThat(restrictions.ageRestriction.restriction).isEqualTo(AllowExclude.EXCLUDE)
      assertThat(restrictions.labelsAllow).containsExactly("allow2")
      assertThat(restrictions.labelsExclude).containsExactly("exclude2")
    }

    lantaiUserDao.update(modifiedSaved.copy(restrictions = ContentRestrictions()))
    with(lantaiUserDao.findByIdOrNull(modified.id)!!) {
      assertThat(restrictions.ageRestriction).isNull()
      assertThat(restrictions.labelsAllow).isEmpty()
      assertThat(restrictions.labelsExclude).isEmpty()
    }
  }

  @Test
  fun `given multiple users when saving then they are persisted`() {
    lantaiUserDao.insert(LantaiUser("user1@example.org", "p"))
    lantaiUserDao.insert(LantaiUser("user2@example.org", "p"))

    val users = lantaiUserDao.findAll()

    assertThat(users).hasSize(2)
    assertThat(users.map { it.email }).containsExactlyInAnyOrder(
      "user1@example.org",
      "user2@example.org",
    )
  }

  @Test
  fun `given some users when counting then proper count is returned`() {
    lantaiUserDao.insert(LantaiUser("user1@example.org", "p"))
    lantaiUserDao.insert(LantaiUser("user2@example.org", "p"))

    val count = lantaiUserDao.count()

    assertThat(count).isEqualTo(2)
  }

  @Test
  fun `given existing user when finding by id then user is returned`() {
    val existing = LantaiUser("user1@example.org", "p")
    lantaiUserDao.insert(existing)

    val user = lantaiUserDao.findByIdOrNull(existing.id)

    assertThat(user).isNotNull
  }

  @Test
  fun `given non-existent user when finding by id then null is returned`() {
    val user = lantaiUserDao.findByIdOrNull("38473")

    assertThat(user).isNull()
  }

  @Test
  fun `given existing user when deleting then user is deleted`() {
    val existing = LantaiUser("user1@example.org", "p")
    lantaiUserDao.insert(existing)

    lantaiUserDao.delete(existing.id)

    assertThat(lantaiUserDao.count()).isEqualTo(0)
  }

  @Test
  fun `given users when checking if exists by email then return true or false`() {
    lantaiUserDao.insert(
      LantaiUser("user1@example.org", "p"),
    )

    val exists = lantaiUserDao.existsByEmailIgnoreCase("USER1@EXAMPLE.ORG")
    val notExists = lantaiUserDao.existsByEmailIgnoreCase("USER2@EXAMPLE.ORG")

    assertThat(exists).isTrue()
    assertThat(notExists).isFalse()
  }

  @Test
  fun `given users when finding by email then return user`() {
    lantaiUserDao.insert(
      LantaiUser("user1@example.org", "p"),
    )

    val found = lantaiUserDao.findByEmailIgnoreCaseOrNull("USER1@EXAMPLE.ORG")
    val notFound = lantaiUserDao.findByEmailIgnoreCaseOrNull("USER2@EXAMPLE.ORG")

    assertThat(found).isNotNull
    assertThat(found?.email).isEqualTo("user1@example.org")
    assertThat(notFound).isNull()
  }

  @Test
  fun `given user when saving announcement as read then it works`() {
    val user = LantaiUser("user1@example.org", "p")
    lantaiUserDao.insert(user)

    assertThat(lantaiUserDao.findAnnouncementIdsReadByUserId(user.id)).isEmpty()

    lantaiUserDao.saveAnnouncementIdsRead(user, setOf("1"))
    assertThat(lantaiUserDao.findAnnouncementIdsReadByUserId(user.id)).containsExactlyInAnyOrder("1")

    lantaiUserDao.saveAnnouncementIdsRead(user, setOf("2"))
    assertThat(lantaiUserDao.findAnnouncementIdsReadByUserId(user.id)).containsExactlyInAnyOrder("1", "2")

    lantaiUserDao.saveAnnouncementIdsRead(user, setOf("2"))
    assertThat(lantaiUserDao.findAnnouncementIdsReadByUserId(user.id)).containsExactlyInAnyOrder("1", "2")
  }
}
