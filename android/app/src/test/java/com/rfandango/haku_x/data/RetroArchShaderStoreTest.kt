package com.rfandango.haku_x.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class RetroArchShaderStoreTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun installsPresetAndItsShaderDependencies() {
    val archive = zip(
      "crt/crt.glslp" to "shaders = 1\nshader0 = shaders/crt.glsl\ntextures = \"MASK\"\nMASK = textures/mask.png",
      "crt/shaders/crt.glsl" to "void main() {}",
      "crt/textures/mask.png" to "image",
      "crt/README.md" to "pack",
    )
    val result = RetroArchShaderStore.importZip(
      ByteArrayInputStream(archive), "CRT Pack.zip", temporary.root,
    )

    assertTrue(result.directory.isDirectory)
    assertEquals(4, result.fileCount)
    assertEquals(1, result.presets.single().passCount)
    assertEquals("crt/crt.glslp", result.presets.single().relativePath)
    assertTrue(File(result.directory, "crt/shaders/crt.glsl").isFile)
    assertTrue(File(result.directory, "crt/textures/mask.png").isFile)
  }

  @Test fun rejectsZipPathTraversalAndCleansStagingDirectory() {
    val archive = zip("../outside.txt" to "bad")
    try {
      RetroArchShaderStore.importZip(ByteArrayInputStream(archive), "unsafe.zip", temporary.root)
      throw AssertionError("A importação deveria rejeitar traversal")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
    assertFalse(File(temporary.root, "outside.txt").exists())
    assertEquals(0, temporary.root.listFiles()?.count { it.name.startsWith(".import-") } ?: 0)
  }

  @Test fun rejectsPresetWithMissingShaderDependency() {
    val archive = zip("pack/test.glslp" to "shaders = 1\nshader0 = missing.glsl")
    try {
      RetroArchShaderStore.importZip(ByteArrayInputStream(archive), "missing.zip", temporary.root)
      throw AssertionError("A importação deveria rejeitar dependência ausente")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
    assertFalse(File(temporary.root, "missing").exists())
  }

  private fun zip(vararg files: Pair<String, String>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      files.forEach { (name, content) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return output.toByteArray()
  }
}
