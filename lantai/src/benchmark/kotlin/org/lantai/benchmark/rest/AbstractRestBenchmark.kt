package org.lantai.benchmark.rest

import org.lantai.benchmark.AbstractBenchmark
import org.lantai.domain.persistence.LantaiUserRepository
import org.lantai.infrastructure.security.LantaiPrincipal
import org.lantai.interfaces.api.rest.BookController
import org.lantai.interfaces.api.rest.SeriesController
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Setup
import org.springframework.beans.factory.annotation.Autowired

internal const val DEFAULT_PAGE_SIZE = 20

@Suppress("SpringJavaInjectionPointsAutowiringInspection")
abstract class AbstractRestBenchmark : AbstractBenchmark() {
  @Autowired
  protected lateinit var userRepository: LantaiUserRepository

  @Autowired
  protected lateinit var seriesController: SeriesController

  @Autowired
  protected lateinit var bookController: BookController

  protected lateinit var principal: LantaiPrincipal

  @Setup(Level.Trial)
  open fun prepareData() {
    principal =
      LantaiPrincipal(
        userRepository.findByEmailIgnoreCaseOrNull("admin@example.org")
          ?: throw IllegalStateException("no user found"),
      )
  }
}
