package com.rfandango.haku_x.data.store

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetArchiveXboxRepositoryTest {
  private fun manifest(): String = JSONObject().apply {
    put("metadata", JSONObject().put("title", "Xbox Clássico"))
    put("files", JSONArray().put(JSONObject().put("name", "Halo Combat Evolved.iso"))
      .put(JSONObject().put("name", "Burnout 3 Takedown.iso"))
      .put(JSONObject().put("name", "manual.pdf"))
      .put(JSONObject().put("name", "Fable The Lost Chapters.iso")))
  }.toString()

  @Test
  fun parsesGameFilesFromTheProvidedArchiveItemAutomatically() {
    val page = InternetArchiveXboxRepository.parseArchiveFiles(manifest(), page = 1, rows = 10)
    assertEquals(3, page.total)
    assertEquals("Burnout 3 Takedown", page.items.first().title)
    assertEquals("Xbox_classico_Velho_BX_Retrogamer", page.items.first().identifier)
    assertEquals("Burnout 3 Takedown.iso", page.items.first().archiveFileName)
  }

  @Test
  fun filtersByGameTitleAndPaginatesTheArchiveManifest() {
    val filtered = InternetArchiveXboxRepository.parseArchiveFiles(manifest(), rawQuery = "halo")
    assertEquals(1, filtered.total)
    assertEquals("Halo Combat Evolved", filtered.items.single().title)
    val first = InternetArchiveXboxRepository.parseArchiveFiles(manifest(), page = 1, rows = 2)
    val second = InternetArchiveXboxRepository.parseArchiveFiles(manifest(), page = 2, rows = 2)
    assertTrue(first.hasMore)
    assertFalse(second.hasMore)
    assertEquals(1, second.items.size)
  }

  @Test
  fun ignoresNonGameFilesAndReportsUnknownRegionWhenNotExplicit() {
    val page = InternetArchiveXboxRepository.parseArchiveFiles(manifest())
    assertTrue(page.items.none { it.title.endsWith(".pdf") })
    assertEquals("Região não identificada", page.items.first { it.title.startsWith("Burnout") }.region)
  }
}
