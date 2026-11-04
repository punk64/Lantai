package org.lantai.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import org.lantai.infrastructure.metadata.bulk.BulkPackageResult
import org.lantai.infrastructure.metadata.bulk.BulkPackageService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder

@RestController
@RequestMapping("api/lantai/v1/series/metadata", produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
class LantaiBulkPackageController(
  private val bulkPackageService: BulkPackageService,
) {
  @PostMapping("package", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(summary = "Import a series metadata package")
  fun upload(
    @RequestParam("file") file: MultipartFile,
    @RequestParam(name = "dryRun", required = false, defaultValue = "true") dryRun: Boolean,
  ): BulkPackageResult {
    if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "压缩包为空")
    val name = file.originalFilename ?: "package.zip"
    if (!name.lowercase().endsWith(".zip")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 .zip 压缩包")
    return runCatching {
      file.inputStream.use { bulkPackageService.submit(name, it, dryRun) }
    }.getOrElse {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, it.message ?: "压缩包解析失败")
    }
  }

  @GetMapping("package/template")
  @Operation(summary = "Download the metadata package template")
  fun template(): ResponseEntity<ByteArray> {
    val bytes = bulkPackageService.template()
    val fileName = URLEncoder.encode("lantai-metadata-template.zip", Charsets.UTF_8)
    return ResponseEntity
      .ok()
      .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''$fileName")
      .contentType(MediaType.APPLICATION_OCTET_STREAM)
      .body(bytes)
  }
}
