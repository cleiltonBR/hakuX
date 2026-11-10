package com.rfandango.haku_x.data.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetArchiveXboxRepositoryTest {
  @Test
  fun queryRequiresAnActualSearchTerm() {
    try {
      InternetArchiveXboxRepository.buildSearchQuery(" ")
      throw AssertionError("A pesquisa vazia deveria ser rejeitada")
    } catch (_: IllegalArgumentException) {
      // expected
    }
    try {
      InternetArchiveXboxRepository.buildSearchQuery("A")
      throw AssertionError("Termos com um caractere deveriam ser rejeitados")
    } catch (_: IllegalArgumentException) {
      // expected
    }
  }

  @Test
  fun queryIsScopedToOriginalXboxSoftwareAndExcludesOtherPlatformsAndHomebrew() {
    val query = InternetArchiveXboxRepository.buildSearchQuery("Halo")
    assertTrue(query.startsWith("mediatype:software"))
    assertTrue(query.contains("subject:xbox"))
    assertTrue(query.contains("title:\"Halo\""))
    assertTrue(query.contains("description:\"Xbox 360\""))
    assertTrue(query.contains("subject:\"Xbox One\""))
    assertTrue(query.contains("title:\"Xbox Series\""))
    assertTrue(query.contains("title:homebrew"))
    assertTrue(query.contains("description:homebrew"))
    assertTrue(query.contains("subject:homebrew"))
  }

  @Test
  fun paginationReportsWhetherMoreResultsExist() {
    val firstPage = ArchiveXboxSearchPage(emptyList(), total = 75, page = 1, rows = 40)
    val secondPage = ArchiveXboxSearchPage(emptyList(), total = 75, page = 2, rows = 40)
    val finalPage = ArchiveXboxSearchPage(emptyList(), total = 75, page = 3, rows = 40)

    assertTrue(firstPage.hasMore)
    assertFalse(secondPage.hasMore)
    assertFalse(finalPage.hasMore)
  }

  @Test
  fun luceneSpecialCharactersInUserInputAreEscaped() {
    val query = InternetArchiveXboxRepository.buildSearchQuery("Halo: Doom")
    assertTrue(query.contains("title:\"Halo\\: Doom\""))
    assertFalse(query.contains("title:\"Halo: Doom\""))
  }
}
