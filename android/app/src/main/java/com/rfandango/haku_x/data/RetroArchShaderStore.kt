package com.rfandango.haku_x.data

import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Installs a shader pack ZIP into the app-private RetroArch shader library.
 *
 * Import is deliberately separate from execution: installing a preset does not
 * imply that its shader language/backend is supported by the renderer.
 */
object RetroArchShaderStore {
  private const val MAX_ENTRIES = 8_000
  private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
  private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

  data class InstalledPreset(
    val relativePath: String,
    val format: RetroArchShaderPreset.Format,
    val passCount: Int,
    val requiresSlangCompiler: Boolean,
  )

  data class ImportResult(
    val directory: File,
    val presets: List<InstalledPreset>,
    val fileCount: Int,
    val totalBytes: Long,
  )

  /**
   * Extracts a ZIP into a new library directory, validates every preset and
   * verifies its declared shader and texture files before publishing it.
   * The destination is untouched if extraction or validation fails.
   */
  fun importZip(input: InputStream, displayName: String, libraryRoot: File): ImportResult {
    require(displayName.isNotBlank()) { "Nome do pacote vazio" }
    require(libraryRoot.exists() || libraryRoot.mkdirs()) { "Não foi possível criar a biblioteca de shaders" }

    val safeName = displayName
      .substringBeforeLast('.', displayName)
      .replace(Regex("[^A-Za-z0-9._-]+"), "_")
      .trim('.', '_', '-', ' ')
      .take(64)
      .ifBlank { "shader-pack" }
    val target = File(libraryRoot, safeName)
    require(!target.exists()) { "Já existe um pacote chamado '$safeName'" }

    val staging = File(libraryRoot, ".import-${UUID.randomUUID()}")
    require(staging.mkdir()) { "Não foi possível preparar a importação" }

    var count = 0
    var total = 0L
    try {
      ZipInputStream(input).use { zip ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val entry = zip.nextEntry ?: break
          if (entry.isDirectory) {
            zip.closeEntry()
            continue
          }
          count++
          require(count <= MAX_ENTRIES) { "ZIP contém arquivos demais (limite: $MAX_ENTRIES)" }

          val relative = normalizeEntryPath(entry.name)
          val output = File(staging, relative).canonicalFile
          require(output.path.startsWith(staging.canonicalPath + File.separator)) {
            "Caminho inseguro no ZIP: ${entry.name}"
          }
          require(!output.exists()) { "Arquivo duplicado no ZIP: ${entry.name}" }
          output.parentFile?.let { require(it.exists() || it.mkdirs()) { "Falha ao criar pasta do pacote" } }

          var entryBytes = 0L
          output.outputStream().buffered().use { stream ->
            while (true) {
              val read = zip.read(buffer)
              if (read < 0) break
              entryBytes += read
              total += read
              require(entryBytes <= MAX_ENTRY_BYTES) { "Arquivo muito grande no ZIP: ${entry.name}" }
              require(total <= MAX_TOTAL_BYTES) { "Pacote excede o limite de 256 MiB" }
              stream.write(buffer, 0, read)
            }
          }
          zip.closeEntry()
        }
      }

      val presetFiles = staging.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("glslp", "slangp") }.toList()
      require(presetFiles.isNotEmpty()) { "O ZIP não contém presets .glslp ou .slangp" }

      // Official RetroArch packs include reference-only presets and chains whose
      // base presets may contain more passes than HakuX can currently execute.
      // Installation must preserve those files instead of treating them as a
      // standalone runtime preset. Resolve references only for library indexing.
      val installed = presetFiles.mapNotNull { file ->
        runCatching {
          val passCount = inspectPreset(file, staging, linkedSetOf(), 0)
          InstalledPreset(
            relativePath = file.relativeTo(staging).invariantSeparatorsPath,
            format = if (file.extension.equals("slangp", ignoreCase = true))
              RetroArchShaderPreset.Format.SLANG else RetroArchShaderPreset.Format.GLSL,
            passCount = passCount,
            requiresSlangCompiler = file.extension.equals("slangp", ignoreCase = true),
          )
        }.getOrNull()
      }
      require(installed.isNotEmpty()) { "Nenhum preset RetroArch válido foi encontrado no pacote" }

      require(staging.renameTo(target)) { "Não foi possível finalizar a instalação do pacote" }
      return ImportResult(target, installed, count, total)
    } catch (error: Throwable) {
      staging.deleteRecursively()
      throw error
    }
  }


  /** Presets already installed in the private library, validated before being shown. */
  fun listPresets(libraryRoot: File): List<InstalledPreset> {
    if (!libraryRoot.isDirectory) return emptyList()
    return libraryRoot.walkTopDown()
      .filter { it.isFile && it.extension.lowercase() in setOf("glslp", "slangp") }
      .mapNotNull { file ->
        runCatching {
          val passCount = inspectPreset(file, libraryRoot, linkedSetOf(), 0)
          InstalledPreset(
            relativePath = file.relativeTo(libraryRoot).invariantSeparatorsPath,
            format = if (file.extension.equals("slangp", ignoreCase = true))
              RetroArchShaderPreset.Format.SLANG else RetroArchShaderPreset.Format.GLSL,
            passCount = passCount,
            requiresSlangCompiler = file.extension.equals("slangp", ignoreCase = true),
          )
        }.getOrNull()
      }.toList()
  }

  /**
   * Inspects an installable preset independently of the runtime parser's
   * execution limits. RetroArch #reference chains are resolved relative to
   * each preset file; .params references are intentionally not shader passes.
   */
  private fun inspectPreset(file: File, packageRoot: File, active: MutableSet<String>, depth: Int): Int {
    require(depth <= 16) { "Cadeia #reference excede 16 níveis: ${file.name}" }
    val root = packageRoot.canonicalFile
    val canonical = file.canonicalFile
    require(canonical.path.startsWith(root.path + File.separator) && canonical.isFile) {
      "Referência de preset fora do pacote: ${file.name}"
    }
    require(active.add(canonical.path)) { "Ciclo de #reference detectado em ${file.name}" }
    try {
      require(canonical.length() in 1..(1024L * 1024L)) { "Preset vazio ou grande demais: ${file.name}" }
      val lines = canonical.readLines(Charsets.UTF_8)
      val declared = lines.firstNotNullOfOrNull { line ->
        Regex("""^\s*shaders\s*=\s*(\d+)\s*$""", RegexOption.IGNORE_CASE)
          .matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
      }
      val shaderEntries = lines.mapNotNull { line ->
        Regex("""^\s*shader(\d+)\s*=\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)
          .matchEntire(line)?.let { match ->
            val raw = match.groupValues[2].trim().removeSurrounding("\"")
            match.groupValues[1].toIntOrNull()?.let { it to raw }
          }
      }.toMap()
      val directCount = declared ?: ((shaderEntries.keys.maxOrNull() ?: -1) + 1)
      require(directCount in 0..256) { "Quantidade de passes inválida em ${file.name}" }
      if (directCount > 0) {
        for (index in 0 until directCount) {
          val relative = shaderEntries[index]
            ?: throw IllegalArgumentException("Preset ${file.name} sem shader$index")
          require(relative.isNotBlank() && !relative.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(relative)) {
            "Caminho de shader inválido em ${file.name}: $relative"
          }
          val dependency = File(canonical.parentFile, relative).canonicalFile
          require(dependency.path.startsWith(root.path + File.separator) && dependency.isFile) {
            "Shader ausente para ${file.name}: $relative"
          }
        }
      }
      val references = lines.mapNotNull { line ->
        Regex("""^\s*#reference\s+"([^"]+)"\s*$""", RegexOption.IGNORE_CASE)
          .matchEntire(line)?.groupValues?.get(1)
      }.filter { it.substringAfterLast('.', "").lowercase() in setOf("glslp", "slangp") }
      val inheritedCount = references.sumOf { reference ->
        require(!reference.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(reference)) {
          "Caminho #reference absoluto em ${file.name}"
        }
        val target = File(canonical.parentFile, reference).canonicalFile
        require(target.path.startsWith(root.path + File.separator) && target.isFile) {
          "Preset referenciado não encontrado em ${file.name}: $reference"
        }
        inspectPreset(target, root, active, depth + 1)
      }
      require(directCount > 0 || inheritedCount > 0) {
        "Preset sem passes nem preset-base referenciado: ${file.name}"
      }
      return directCount + inheritedCount
    } finally {
      active.remove(canonical.path)
    }
  }

  private fun normalizeEntryPath(raw: String): String {
    val normalized = raw.replace('\\', '/').trim()
    require(normalized.isNotBlank() && !normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
      "Caminho absoluto inválido no ZIP: $raw"
    }
    val parts = normalized.split('/')
    require(parts.none { it.isBlank() || it == ".." }) { "Caminho inseguro no ZIP: $raw" }
    val safe = parts.filterNot { it == "." }.joinToString("/")
    require(safe.isNotBlank()) { "Entrada vazia no ZIP" }
    return safe
  }
}
