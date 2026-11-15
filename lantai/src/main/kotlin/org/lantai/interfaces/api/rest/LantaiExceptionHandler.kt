package org.lantai.interfaces.api.rest

import org.lantai.domain.model.MediaUnsupportedException
import org.lantai.infrastructure.metadata.scrape.ScrapeException
import org.lantai.infrastructure.metadata.scrape.ScrapeQuotaExceededException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class LantaiExceptionHandler {
  @ExceptionHandler(MediaUnsupportedException::class)
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  fun onMediaUnsupported(e: MediaUnsupportedException): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.message ?: "media does not support this operation")
      .apply {
        title = "Media unsupported"
        setProperty("code", e.code)
      }

  @ExceptionHandler(ScrapeException::class)
  @ResponseStatus(HttpStatus.BAD_GATEWAY)
  fun onScrape(e: ScrapeException): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.message ?: "metadata source request failed")
      .apply { title = "Metadata source failed" }

  @ExceptionHandler(ScrapeQuotaExceededException::class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  fun onScrapeQuota(e: ScrapeQuotaExceededException): ProblemDetail =
    ProblemDetail
      .forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.message ?: "daily quota exceeded")
      .apply { title = "Metadata source quota exceeded" }
}
