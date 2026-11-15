package org.lantai.domain.persistence

import org.lantai.domain.model.ReadListRequestBook
import org.lantai.domain.model.ReadListRequestBookMatches

interface ReadListRequestRepository {
  fun matchBookRequests(requests: Collection<ReadListRequestBook>): Collection<ReadListRequestBookMatches>
}
