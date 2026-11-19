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
  private const val MAX_ENTRIES = 2_000
  private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
  private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

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

      val installed = presetFiles.map { file ->
        val text = file.readText(Charsets.UTF_8)
        val preset = RetroArchPresetParser.parse(file.name, text)
        val presetParent = file.parentFile ?: staging
        val declaredFiles = preset.passes.map { it.shaderPath } + preset.texturePaths.values
        declaredFiles.forEach { relative ->
          val dependency = File(presetParent, relative).canonicalFile
          require(dependency.path.startsWith(staging.canonicalPath + File.separator) && dependency.isFile) {
            "Dependência ausente para ${file.name}: $relative"
          }
        }
        InstalledPreset(
          relativePath = file.relativeTo(staging).invariantSeparatorsPath,
          format = preset.format,
          passCount = preset.passes.size,
          requiresSlangCompiler = preset.requiresSlangCompiler,
        )
      }

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
          val preset = RetroArchPresetParser.parse(file.name, file.readText(Charsets.UTF_8))
          val root = libraryRoot.canonicalFile
          val canonical = file.canonicalFile
          require(canonical.path.startsWith(root.path + File.separator))
          val base = file.parentFile ?: libraryRoot
          (preset.passes.map { it.shaderPath } + preset.texturePaths.values).forEach { relative ->
            val dependency = File(base, relative).canonicalFile
            require(dependency.path.startsWith(root.path + File.separator) && dependency.isFile)
          }
          InstalledPreset(
            relativePath = file.relativeTo(libraryRoot).invariantSeparatorsPath,
            format = preset.format,
            passCount = preset.passes.size,
            requiresSlangCompiler = preset.requiresSlangCompiler,
          )
        }.getOrNull()
      }.toList()
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
