package org.lantai.infrastructure.search

interface LuceneCommitter {
  fun commitAndMaybeRefresh()
}
