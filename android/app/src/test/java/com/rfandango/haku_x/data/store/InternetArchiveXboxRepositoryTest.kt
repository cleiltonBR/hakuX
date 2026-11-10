package com.rfandango.haku_x.data.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetArchiveXboxRepositoryTest {
  @Test
  fun blankSearchReturnsTheRequestedArchiveCollectionCatalogQuery() {
    val query = InternetArchiveXboxRepository.buildSearchQuery(" ")
    assertTrue(query.startsWith("collection:Xbox_classico_Velho_BX_Retrogamer"))
    assertTrue(query.contains("mediatype:software"))
    assertTrue(query.contains("title:"))
    assertFalse(query.contains("subject:xbox"))
  }

  @Test
  fun shortSearchTermsCanStillBeScopedToTheRequestedCollection() {
    val query = InternetArchiveXboxRepository.buildSearchQuery("A")
    assertTrue(query.contains("collection:Xbox_classico_Velho_BX_Retrogamer"))
    assertTrue(query.contains("A"))
    assertTrue(query.contains("title:"))
  }

  @Test
  fun queryIsScopedToRequestedCollectionAndExcludesOtherPlatformsAndHomebrew() {
    val query = InternetArchiveXboxRepository.buildSearchQuery("Halo")
    assertTrue(query.startsWith("collection:Xbox_classico_Velho_BX_Retrogamer"))
    assertTrue(query.contains("mediatype:software"))
    assertTrue(query.contains("Halo"))
    assertTrue(query.contains("description:"))
    assertTrue(query.contains("Xbox 360"))
    assertTrue(query.contains("subject:homebrew"))
    assertTrue(query.contains("title:homebrew"))
    assertTrue(query.contains("description:homebrew"))
    assertFalse(query.contains("subject:xbox"))
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
    assertTrue(query.contains("Halo"))
    assertTrue(query.contains("Doom"))
    assertFalse(query.contains("Halo: Doom"))
  }
}
