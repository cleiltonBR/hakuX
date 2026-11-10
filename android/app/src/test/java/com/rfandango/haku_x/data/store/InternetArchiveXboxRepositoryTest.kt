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
  fun queryIsScopedToSoftwareAndXboxAndExcludesNewerGenerations() {
    val query = InternetArchiveXboxRepository.buildSearchQuery("Halo")
    assertTrue(query.startsWith("mediatype:software"))
    assertTrue(query.contains("subject:xbox"))
    assertTrue(query.contains("title:\"Halo\""))
    assertTrue(query.contains("description:\"Xbox 360\""))
    assertTrue(query.contains("subject:\"Xbox One\""))
    assertTrue(query.contains("title:\"Xbox Series\""))
  }

  @Test
  fun luceneOperatorsInUserInputAreEscaped() {
    val query = InternetArchiveXboxRepository.buildSearchQuery("Halo OR Doom")
    assertFalse(query.contains("title:\"Halo OR Doom\""))
    assertTrue(query.contains("Halo \\OR Doom"))
  }
}
