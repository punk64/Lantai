package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.lantai.infrastructure.configuration.LantaiProperties
import org.lantai.infrastructure.jooq.main.LantaiUserProfileDao
import org.lantai.infrastructure.jooq.main.LantaiUserProfileRow
import org.lantai.infrastructure.security.LantaiPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId
import java.util.Base64
import javax.imageio.ImageIO

@RestController
@RequestMapping("api/lantai/v1/user-profile", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Lantai User Profile")
class LantaiUserProfileController(
  private val lantaiProperties: LantaiProperties,
  private val lantaiUserProfileDao: LantaiUserProfileDao,
) {
  @GetMapping("me")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Retrieve the profile of the current user")
  fun me(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): LantaiUserProfileDto = dto(principal.user.id)

  @PutMapping("me")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Update the nickname of the current user")
  fun updateNickname(
    @RequestBody body: LantaiProfileUpdateDto,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): LantaiUserProfileDto = applyNickname(principal.user.id, body)

  @PutMapping("{userId}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Update the nickname of a user")
  fun updateNicknameOf(
    @PathVariable userId: String,
    @RequestBody body: LantaiProfileUpdateDto,
  ): LantaiUserProfileDto {
    if (!SAFE_ID.matches(userId)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    return applyNickname(userId, body)
  }

  @PutMapping("me/avatar")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Replace the avatar of the current user")
  fun updateAvatar(
    @RequestBody body: LantaiAvatarUpdateDto,
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): LantaiUserProfileDto = applyAvatar(principal.user.id, body)

  @PutMapping("{userId}/avatar")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Replace the avatar of a user")
  fun updateAvatarOf(
    @PathVariable userId: String,
    @RequestBody body: LantaiAvatarUpdateDto,
  ): LantaiUserProfileDto {
    if (!SAFE_ID.matches(userId)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    return applyAvatar(userId, body)
  }

  @DeleteMapping("me/avatar")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Remove the avatar of the current user")
  fun deleteAvatar(
    @AuthenticationPrincipal principal: LantaiPrincipal,
  ): LantaiUserProfileDto {
    val userId = principal.user.id
    Files.deleteIfExists(avatarPath(userId))
    lantaiUserProfileDao.clearAvatar(userId)
    return dto(userId)
  }

  @GetMapping("map")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Retrieve the profiles of several users")
  fun map(
    @RequestParam ids: List<String>,
  ): Map<String, LantaiUserProfileDto> =
    lantaiUserProfileDao.users(ids.take(MAX_MAP_SIZE)).mapValues { (id, row) -> row.toDto(id) }

  @GetMapping("{userId}/avatar", produces = [MediaType.IMAGE_PNG_VALUE])
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Retrieve the avatar image of a user")
  fun avatar(
    @PathVariable userId: String,
  ): ResponseEntity<ByteArray> {
    if (!SAFE_ID.matches(userId)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val file = avatarPath(userId)
    if (!Files.exists(file)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("Cache-Control", "private, max-age=300").body(Files.readAllBytes(file))
  }

  private fun applyNickname(userId: String, body: LantaiProfileUpdateDto): LantaiUserProfileDto {
    val nickname = body.nickname?.trim().orEmpty()
    if (nickname.length > 32) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "昵称最多 32 个字符")
    lantaiUserProfileDao.saveNickname(userId, nickname)
    return dto(userId)
  }

  private fun applyAvatar(userId: String, body: LantaiAvatarUpdateDto): LantaiUserProfileDto {
    val raw = decodeImage(body.data)
    val target = avatarPath(userId)
    Files.createDirectories(target.parent)
    Files.write(target, normalize(raw))
    lantaiUserProfileDao.saveAvatar(userId, target.fileName.toString())
    return dto(userId)
  }

  private fun dto(userId: String): LantaiUserProfileDto =
    lantaiUserProfileDao.user(userId)?.toDto(userId)
      ?: LantaiUserProfileDto(userId = userId, username = "", nickname = "", hasAvatar = false, avatarUpdated = null)

  private fun LantaiUserProfileRow.toDto(userId: String): LantaiUserProfileDto =
    LantaiUserProfileDto(
      userId = userId,
      username = username,
      nickname = nickname.ifBlank { username },
      hasAvatar = avatarFile.isNotBlank() && Files.exists(avatarPath(userId)),
      avatarUpdated = updated?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
    )

  private fun configDir(): Path = Paths.get(lantaiProperties.configDir ?: ".").toAbsolutePath().normalize()

  private fun avatarPath(userId: String): Path = configDir().resolve("profile").resolve("avatar").resolve("$userId.png")

  private fun decodeImage(data: String): ByteArray {
    val payload = data.substringAfter("base64,", data).trim()
    if (payload.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少图片数据")
    val bytes =
      try {
        Base64.getDecoder().decode(payload)
      } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "图片数据不是合法的 Base64")
      }
    if (bytes.size > MAX_AVATAR_BYTES) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "头像不能超过 2 MB")
    return bytes
  }

  private fun normalize(bytes: ByteArray): ByteArray {
    val source =
      ImageIO.read(ByteArrayInputStream(bytes)) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "无法识别的图片格式")
    val side = minOf(source.width, source.height)
    val x = (source.width - side) / 2
    val y = (source.height - side) / 2
    val out = BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(source, 0, 0, AVATAR_SIZE, AVATAR_SIZE, x, y, x + side, y + side, null)
    g.dispose()
    val buffer = ByteArrayOutputStream()
    ImageIO.write(out, "png", buffer)
    return buffer.toByteArray()
  }

  companion object {
    private const val AVATAR_SIZE = 256
    private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
    private const val MAX_MAP_SIZE = 200
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,32}")
  }
}

data class LantaiProfileUpdateDto(
  val nickname: String? = null,
)

data class LantaiAvatarUpdateDto(
  val data: String,
)

data class LantaiUserProfileDto(
  val userId: String,
  val username: String,
  val nickname: String,
  val hasAvatar: Boolean,
  val avatarUpdated: Long?,
)
