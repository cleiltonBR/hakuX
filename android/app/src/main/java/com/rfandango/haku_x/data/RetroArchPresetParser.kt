package com.rfandango.haku_x.data

import java.io.StringReader
import java.util.Properties

/** Parsed metadata for a RetroArch-style shader preset. */
data class RetroArchShaderPass(
  val index: Int,
  val shaderPath: String,
  val filterLinear: Boolean,
  val wrapMode: String,
  val frameCount: Int,
  val scaleType: String,
  val scale: Float?,
)

data class RetroArchShaderPreset(
  val format: Format,
  val passes: List<RetroArchShaderPass>,
  val texturePaths: Map<String, String>,
  val parameterValues: Map<String, String>,
  val sourceValues: Map<String, String>,
) {
  enum class Format { GLSL, SLANG }
  /** Slang source needs a Slang-to-SPIR-V compiler before Vulkan execution. */
  val requiresSlangCompiler: Boolean get() = format == Format.SLANG
}

/**
 * Parses the common key/value metadata used by .glslp and .slangp files.
 * It does not compile shaders or claim that a parsed preset is executable.
 */
object RetroArchPresetParser {
  private const val MAX_PASSES = 32
  private const val MAX_FRAME_COUNT = 32
  private val passKey = Regex("shader(\\d+)")

  fun parse(fileName: String, text: String): RetroArchShaderPreset {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val format = when (extension) {
      "glslp" -> RetroArchShaderPreset.Format.GLSL
      "slangp" -> RetroArchShaderPreset.Format.SLANG
      else -> throw IllegalArgumentException("Formato de preset não suportado: .$extension")
    }

    val properties = Properties()
    try {
      properties.load(StringReader(text))
    } catch (error: Exception) {
      throw IllegalArgumentException("Preset inválido: " + (error.message ?: "erro de leitura"), error)
    }
    val values = properties.stringPropertyNames().associateWith { key -> unquote(properties.getProperty(key).trim()) }
    val declaredCount = values["shaders"]?.toIntOrNull()
    val indexedPasses = values.keys.mapNotNull { key ->
      passKey.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
    }
    // Some third-party/official-pack metadata declares shaders=0 while still
    // providing indexed shaderN entries. Prefer the actual pass declarations
    // in that case instead of rejecting an otherwise usable preset.
    val inferredCount = (indexedPasses.maxOrNull() ?: -1) + 1
    val count = declaredCount?.takeIf { it > 0 } ?: inferredCount
    require(count in 1..MAX_PASSES) {
      "Quantidade de passes inválida: $count (limite: $MAX_PASSES) em $fileName"
    }

    val passes = (0 until count).map { index ->
      val shader = values["shader$index"]
        ?: throw IllegalArgumentException("Preset sem shader$index")
      val frameCount = values["frame_count$index"]?.toIntOrNull() ?: 1
      require(frameCount in 1..MAX_FRAME_COUNT) {
        "frame_count$index fora do intervalo 1..$MAX_FRAME_COUNT"
      }
      val scaleType = values["scale_type$index"]?.lowercase() ?: "source"
      require(scaleType in setOf("source", "absolute", "viewport")) {
        "scale_type$index não suportado: $scaleType"
      }
      val scale = values["scale$index"]?.toFloatOrNull()
      if (scaleType == "absolute") {
        require(scale != null && scale > 0f) { "scale$index precisa ser positivo para escala absolute" }
      }
      RetroArchShaderPass(
        index = index,
        shaderPath = safeRelativePath(shader, "shader$index"),
        filterLinear = values["filter_linear$index"]?.toBooleanStrictOrNull() ?: false,
        wrapMode = values["wrap_mode$index"]?.lowercase() ?: "clamp_to_border",
        frameCount = frameCount,
        scaleType = scaleType,
        scale = scale,
      )
    }

    val textureNames = values["textures"].orEmpty()
      .split(';').map(String::trim).filter(String::isNotEmpty)
    val textures = textureNames.associateWith { name ->
      val path = values[name] ?: throw IllegalArgumentException("Textura '$name' declarada, mas sem caminho")
      safeRelativePath(path, "textura $name")
    }
    val parameterNames = values["parameters"].orEmpty()
      .split(';').map(String::trim).filter(String::isNotEmpty)
    val parameters = parameterNames.associateWith { name ->
      values[name] ?: throw IllegalArgumentException("Parâmetro '$name' declarado, mas sem valor")
    }

    return RetroArchShaderPreset(format, passes, textures, parameters, values)
  }

  private fun unquote(value: String): String =
    if (value.length >= 2 && value.first() == '"' && value.last() == '"') value.substring(1, value.length - 1) else value

  private fun safeRelativePath(raw: String, key: String): String {
    val normalized = raw.replace('\\', '/').trim()
    require(normalized.isNotEmpty()) { "$key tem caminho vazio" }
    require(!normalized.startsWith('/') && !normalized.startsWith("//")) {
      "$key precisa usar caminho relativo ao preset"
    }
    require(!Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
      "$key não pode usar caminho absoluto do Windows"
    }
    // RetroArch's official presets legitimately reference sibling shader files
    // (for example: shader1 = ../stock.glsl). Keep relative parent segments here;
    // the installer and installed-library scanner resolve the final canonical
    // path and reject anything that escapes the package/library root.
    require(normalized.split('/').none { it.isEmpty() }) {
      "$key contém caminho inseguro"
    }
    return normalized.split('/').filterNot { it == "." }.joinToString("/")
  }
}
