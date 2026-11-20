package com.rfandango.haku_x.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RetroArchPresetParserTest {
  @Test fun parsesMultiPassGlslPresetAndOptions() {
    val preset = RetroArchPresetParser.parse("crt.glslp", """
      shaders = 2
      shader0 = shaders/linearize.glsl
      filter_linear0 = false
      wrap_mode0 = clamp_to_edge
      frame_count0 = 2
      shader1 = shaders/crt.glsl
      filter_linear1 = true
      scale_type1 = absolute
      scale1 = 2.0
      textures = "LUT"
      LUT = textures/slot-mask.png
      parameters = "MASK_SIZE;SCANLINE"
      MASK_SIZE = 2.0
      SCANLINE = 0.35
    """.trimIndent())
    assertEquals(RetroArchShaderPreset.Format.GLSL, preset.format)
    assertEquals(2, preset.passes.size)
    assertEquals("shaders/linearize.glsl", preset.passes[0].shaderPath)
    assertEquals(2, preset.passes[0].frameCount)
    assertTrue(preset.passes[1].filterLinear)
    assertEquals("absolute", preset.passes[1].scaleType)
    assertEquals(2.0f, preset.passes[1].scale!!)
    assertEquals("textures/slot-mask.png", preset.texturePaths["LUT"])
    assertEquals("0.35", preset.parameterValues["SCANLINE"])
    assertFalse(preset.requiresSlangCompiler)
  }

  @Test fun identifiesSlangPresetsWithoutClaimingTheyAreExecutable() {
    val preset = RetroArchPresetParser.parse("test.slangp", "shaders = 1\nshader0 = crt.slang")
    assertEquals(RetroArchShaderPreset.Format.SLANG, preset.format)
    assertTrue(preset.requiresSlangCompiler)
  }

  @Test fun infersPassesWhenPresetDeclaresZeroButContainsShaderEntries() {
    val preset = RetroArchPresetParser.parse(
      "zero-count.glslp",
      "shaders = 0\\nshader0 = shaders/valid.glsl",
    )
    assertEquals(1, preset.passes.size)
    assertEquals("shaders/valid.glsl", preset.passes.single().shaderPath)
  }

  @Test fun acceptsParentRelativeReferencesUsedByOfficialPacks() {
    val preset = RetroArchPresetParser.parse(
      "test.glslp",
      "shaders = 1\nshader0 = ../stock.glsl",
    )
    assertEquals("../stock.glsl", preset.passes.single().shaderPath)
  }

  @Test fun rejectsAbsoluteShaderPaths() {
    assertInvalid("shaders = 1\nshader0 = /outside.glsl")
    assertInvalid("shaders = 1\nshader0 = C:\\outside.glsl")
  }

  @Test fun rejectsMissingPassAndInvalidPassCount() {
    assertInvalid("shaders = 2\nshader0 = one.glsl")
    assertInvalid("shaders = 33\nshader0 = one.glsl")
  }

  private fun assertInvalid(source: String) {
    try {
      RetroArchPresetParser.parse("test.glslp", source)
      fail("Era esperado um preset inválido")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
  }
}
