package org.lantai.domain.persistence

import org.lantai.domain.model.TransientBook

interface TransientBookRepository {
  fun findByIdOrNull(transientBookId: String): TransientBook?

  fun save(transientBook: TransientBook)

  fun save(transientBooks: Collection<TransientBook>)
}
