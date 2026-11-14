package com.rfandango.haku_x.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

/**
 * Stores the user's Lossless.dll as data. The Windows PE is never loaded by
 * Android; native LSFG code reads only its RCDATA shader resources.
 */
object LosslessDllStore {
  private const val FILE_NAME = "Lossless.dll"
  private const val MAX_DLL_BYTES = 64L * 1024L * 1024L

  fun installedFile(context: Context): File =
    File(File(context.filesDir, "lsfg"), FILE_NAME)

  fun import(context: Context, uri: Uri): File {
    val directory = File(context.filesDir, "lsfg")
    check(directory.exists() || directory.mkdirs()) { "Não foi possível criar a pasta LSFG." }
    val destination = File(directory, "$FILE_NAME.importing")
    try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        destination.outputStream().use { output ->
          val buffer = ByteArray(64 * 1024)
          var total = 0L
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_DLL_BYTES) { "O arquivo DLL excede 64 MB." }
            output.write(buffer, 0, count)
          }
        }
      } ?: error("Não foi possível ler o arquivo selecionado.")
      validate(destination)
      val finalFile = installedFile(context)
      if (finalFile.exists()) check(finalFile.delete()) { "Não foi possível substituir a DLL anterior." }
      check(destination.renameTo(finalFile)) { "Não foi possível guardar Lossless.dll." }
      return finalFile
    } catch (error: Throwable) {
      destination.delete()
      throw error
    }
  }

  fun remove(context: Context) {
    installedFile(context).delete()
  }

  private fun validate(file: File) {
    require(file.length() in 64..MAX_DLL_BYTES) { "Arquivo DLL vazio ou com tamanho inválido." }
    RandomAccessFile(file, "r").use { pe ->
      require(pe.readUnsignedByte() == 0x4d && pe.readUnsignedByte() == 0x5a) {
        "O arquivo selecionado não é um executável PE válido."
      }
      pe.seek(0x3c)
      val offset = readLe32(pe)
      require(offset >= 64 && offset <= file.length() - 24) { "Cabeçalho PE inválido." }
      pe.seek(offset.toLong())
      require(readLe32(pe) == 0x00004550L) { "Assinatura PE inválida." }
      val machine = readLe16(pe)
      require(machine == 0x8664) { "A DLL precisa ser a versão Windows x64 (AMD64)." }
      pe.seek(offset.toLong() + 24)
      require(readLe16(pe) == 0x020b) { "A DLL precisa usar o formato PE32+ x64." }
    }
  }

  private fun readLe16(file: RandomAccessFile): Int {
    val a = file.read()
    val b = file.read()
    require(a >= 0 && b >= 0) { "Cabeçalho PE truncado." }
    return a or (b shl 8)
  }

  private fun readLe32(file: RandomAccessFile): Long {
    val a = readLe16(file).toLong()
    val b = readLe16(file).toLong()
    return a or (b shl 16)
  }
}
