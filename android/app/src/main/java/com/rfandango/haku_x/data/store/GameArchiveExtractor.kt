package com.rfandango.haku_x.data.store

import com.github.junrar.Junrar
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Extracts downloaded game archives into a private staging directory.
 * Only the extracted game image is later exposed through the user's SAF folder.
 */
internal object GameArchiveExtractor {
  private val gameExtensions = setOf("iso", "xiso", "cso", "cci")

  fun extract(archive: File, destination: File) {
    check(destination.mkdirs() || destination.isDirectory) { "Não foi possível criar a pasta temporária de extração." }
    when (archive.extension.lowercase(Locale.ROOT)) {
      "zip" -> extractZip(archive, destination)
      "rar" -> Junrar.extract(archive, destination)
      "7z" -> extractSevenZip(archive, destination)
      else -> error("Formato de arquivo compactado não suportado.")
    }
    check(containsGameImage(destination)) {
      "O arquivo foi extraído, mas não contém uma imagem de jogo compatível (.ISO, .XISO, .CSO ou .CCI)."
    }
  }

  private fun extractZip(archive: File, destination: File) {
    ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
      val buffer = ByteArray(128 * 1024)
      while (true) {
        val entry = zip.nextEntry ?: break
        val output = safeOutput(destination, entry.name)
        if (entry.isDirectory) {
          check(output.mkdirs() || output.isDirectory) { "Não foi possível criar uma pasta extraída." }
        } else {
          output.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
          FileOutputStream(output).use { stream ->
            while (true) {
              val count = zip.read(buffer)
              if (count < 0) break
              stream.write(buffer, 0, count)
            }
          }
        }
        zip.closeEntry()
      }
    }
  }

  private fun extractSevenZip(archive: File, destination: File) {
    SevenZFile(archive).use { seven ->
      val buffer = ByteArray(128 * 1024)
      while (true) {
        val entry = seven.nextEntry ?: break
        val output = safeOutput(destination, entry.name)
        if (entry.isDirectory) {
          check(output.mkdirs() || output.isDirectory) { "Não foi possível criar uma pasta extraída." }
        } else {
          output.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
          FileOutputStream(output).use { stream ->
            while (true) {
              val count = seven.read(buffer)
              if (count < 0) break
              stream.write(buffer, 0, count)
            }
          }
        }
      }
    }
  }

  private fun safeOutput(root: File, entryName: String): File {
    val normalized = entryName.replace('\\\\', '/').trim()
    require(normalized.isNotBlank() && !normalized.startsWith('/')) { "O arquivo compactado contém um caminho inválido." }
    val target = File(root, normalized).canonicalFile
    val rootPath = root.canonicalFile.path + File.separator
    require(target.path.startsWith(rootPath)) { "O arquivo compactado tentou gravar fora da pasta de extração." }
    return target
  }

  private fun containsGameImage(directory: File): Boolean =
    directory.walkTopDown().any { file ->
      file.isFile && file.extension.lowercase(Locale.ROOT) in gameExtensions
    }
}
