package com.rfandango.haku_x.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Rewrites a full Xbox disc dump (the video partition, then the game's) as
 * an XISO beside it, then moves the dump into ORIGINALS_FOLDER.
 *
 * The XISO is the game partition's files packed one after another, laid out
 * as extract-xiso's rewrite does: the volume descriptor at 0x10000, the root
 * directory at sector 0x108, each directory's table followed by its files
 * and then its subdirectories; tables are binary trees sorted by upper-cased
 * name, entries 4-byte aligned and never across a sector; 0xFF pads tables
 * and files to sectors, zeros pad the image to 64 KiB. Files are copied byte
 * for byte (no media patch).
 */
object XisoConverter {
  /** Where a converted dump goes, beside its XISO (the library doesn't list it). */
  const val ORIGINALS_FOLDER = "Original ISOs"

  sealed interface Result {
    /** The XISO; originalMoved is false when the dump couldn't be moved and stayed in place. */
    data class Done(val xiso: DocumentFile, val originalMoved: Boolean) : Result
    data class Failed(val message: String) : Result
    data object Cancelled : Result
  }

  private const val SECTOR = 2048
  private const val VOLUME = 0x10000L
  private const val ROOT_SECTOR = 0x108L
  private const val IMAGE_MODULUS = 0x10000L
  private const val MAGIC = "MICROSOFT*XBOX*MEDIA"
  private const val ATTR_DIR = 0x10
  private const val MASK = 0xFFFFFFFFL
  private const val COPY_CHUNK = 4 shl 20

  /** "Game (USA).iso" -> "Game (USA).xiso.iso". */
  fun outputName(fileName: String): String = fileName.substringBeforeLast('.') + ".xiso.iso"

  /**
   * Converts game (a file under the games folder). Blocking; progress gets
   * the bytes copied so far and the total, cancelled is polled between chunks.
   */
  fun convert(
    context: Context,
    folder: Uri,
    game: Game,
    progress: (Long, Long) -> Unit,
    cancelled: () -> Boolean,
  ): Result {
    val resolver = context.contentResolver
    val parent = parentOf(context, folder, game.relativePath)
      ?: return Result.Failed("Can't open the game's folder")
    val fileName = game.relativePath.substringAfterLast('/')
    val name = outputName(fileName)
    if (parent.findFile(name) != null) return Result.Failed("$name already exists")

    var out: DocumentFile? = null
    try {
      val input = resolver.openFileDescriptor(game.uri, "r") ?: throw IOException("Can't read ${game.relativePath}")
      input.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { fis ->
          val src = fis.channel
          val partition = XboxDisc.partitionOf(src) ?: throw IOException("Not an Xbox disc image")
          val vd = readAt(src, partition + VOLUME + MAGIC.length, 16)
          val root = Entry(ByteArray(0), ATTR_DIR, 0, 0, null)
          root.children = readDir(src, partition, vd.getInt(0).toLong() and MASK, vd.getInt(4).toLong() and MASK, 0)
          val fileTime = ByteArray(8).also { vd.position(8); vd.get(it) }
          layout(root)
          val total = totalBytes(root)

          val created = parent.createFile("application/octet-stream", name) ?: throw IOException("Can't create $name")
          out = created
          val output = resolver.openFileDescriptor(created.uri, "w") ?: throw IOException("Can't write $name")
          output.use { opfd ->
            FileOutputStream(opfd.fileDescriptor).use { fos ->
              val w = Writer(fos.channel)
              writeHeader(w, root, fileTime)
              var copied = 0L
              emit(w, src, partition, root) { n ->
                copied += n
                progress(copied, total)
                if (cancelled()) throw CancelledException()
              }
              w.zeros((IMAGE_MODULUS - w.position % IMAGE_MODULUS) % IMAGE_MODULUS)
              w.flush()
            }
          }
        }
      }
    } catch (_: CancelledException) {
      out?.delete()
      return Result.Cancelled
    } catch (e: Exception) {
      out?.delete()
      return Result.Failed(e.message ?: e.toString())
    }

    // The dump goes into the originals folder beside the XISO.
    val moved = try {
      val originals = parent.findFile(ORIGINALS_FOLDER)?.takeIf { it.isDirectory }
        ?: parent.createDirectory(ORIGINALS_FOLDER)
      originals != null && DocumentsContract.moveDocument(resolver, game.uri, parent.uri, originals.uri) != null
    } catch (_: Exception) {
      false
    }
    return Result.Done(out!!, moved)
  }

  /** Builds an XDVDFS image from an extracted SAF folder; source files remain untouched. */
  fun convertFolder(context: Context, treeUri: Uri, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): Result {
    val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return Result.Failed("Não foi possível abrir a pasta selecionada")
    if (!rootDoc.isDirectory) return Result.Failed("Selecione uma pasta de jogo")
    var output: DocumentFile? = null
    try {
      var foundXbe = false
      val root = Entry(ByteArray(0), ATTR_DIR, 0, 0, emptyList())
      fun collect(folder: DocumentFile, target: Entry, depth: Int) {
        if (depth > 64) throw IOException("A pasta contém muitos níveis de subpastas")
        val children = ArrayList<Entry>()
        val names = HashSet<String>()
        for (doc in folder.listFiles()) {
          if (cancelled()) throw CancelledException()
          val name = doc.name ?: throw IOException("Um arquivo não possui nome")
          if (name.endsWith(".xiso.iso", true)) continue
          val encoded = name.toByteArray(Charsets.UTF_8)
          if (encoded.isEmpty() || encoded.size > 42 || encoded.any { (it.toInt() and 255) < 32 }) throw IOException("Nome incompatível com XDVDFS: $name")
          if (!names.add(name.uppercase(java.util.Locale.ROOT))) throw IOException("Nome duplicado: $name")
          if (doc.isDirectory) {
            val child = Entry(encoded, ATTR_DIR, 0, 0, emptyList())
            collect(doc, child, depth + 1)
            children.add(child)
          } else {
            val size = doc.length()
            if (size < 0 || size > MASK) throw IOException("Tamanho inválido: $name")
            if (depth == 0 && name.equals("default.xbe", true)) foundXbe = true
            children.add(Entry(encoded, 0, 0, size, null, doc.uri))
          }
        }
        target.children = children
      }
      collect(rootDoc, root, 0)
      if (!foundXbe) throw IOException("Não encontrei default.xbe na raiz da pasta selecionada")
      layout(root)
      val safeName = (rootDoc.name ?: "Jogo").replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "Jogo" }
      val fileName = safeName + ".xiso.iso"
      if (rootDoc.findFile(fileName) != null) throw IOException("$fileName já existe nesta pasta")
      output = rootDoc.createFile("application/octet-stream", fileName) ?: throw IOException("Não foi possível criar $fileName")
      val pfd = context.contentResolver.openFileDescriptor(output.uri, "w") ?: throw IOException("Não foi possível gravar $fileName")
      pfd.use { descriptor ->
        FileOutputStream(descriptor.fileDescriptor).use { stream ->
          val writer = Writer(stream.channel)
          writeHeader(writer, root, ByteArray(8))
          val total = totalBytes(root)
          var copied = 0L
          emitFolder(writer, context, root) { count ->
            copied += count
            progress(copied, total)
            if (cancelled()) throw CancelledException()
          }
          writer.zeros((IMAGE_MODULUS - writer.position % IMAGE_MODULUS) % IMAGE_MODULUS)
          writer.flush()
        }
      }
      return Result.Done(output!!, false)
    } catch (_: CancelledException) {
      output?.delete()
      return Result.Cancelled
    } catch (error: Exception) {
      output?.delete()
      return Result.Failed(error.message ?: error.toString())
    }
  }

  private fun emitFolder(writer: Writer, context: Context, dir: Entry, copied: (Long) -> Unit) {
    writer.seekTo(dir.outSector * SECTOR)
    val table = ByteBuffer.allocate((nSectors(dir.tableSize.toLong()) * SECTOR).toInt()).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until table.capacity()) table.put(i, 0xFF.toByte())
    for (entry in dir.order) {
      table.position(entry.offset)
      table.putShort(((entry.left?.offset ?: 0) / 4).toShort())
      table.putShort(((entry.right?.offset ?: 0) / 4).toShort())
      table.putInt(entry.outSector.toInt())
      table.putInt(if (entry.isDir) (nSectors(entry.tableSize.toLong()) * SECTOR).toInt() else entry.size.toInt())
      table.put(entry.attributes.toByte())
      table.put(entry.name.size.toByte())
      table.put(entry.name)
    }
    table.position(0)
    writer.put(table)
    for (entry in dir.order) {
      if (entry.isDir) continue
      writer.seekTo(entry.outSector * SECTOR)
      val source = context.contentResolver.openInputStream(entry.sourceUri ?: throw IOException("Arquivo de origem ausente"))
        ?: throw IOException("Não foi possível ler um arquivo da pasta")
      source.use { writer.copy(it, entry.size, copied) }
      writer.fill((SECTOR - entry.size % SECTOR) % SECTOR, 0xFF.toByte())
    }
    for (entry in dir.order) if (entry.isDir) emitFolder(writer, context, entry, copied)
  }

  private class CancelledException : Exception()

  /** A directory entry: the source's, then where it goes in the XISO. */
  private class Entry(
    val name: ByteArray,
    val attributes: Int,
    val sector: Long,
    val size: Long,
    var children: List<Entry>?,
    val sourceUri: Uri? = null,
  ) {
    val isDir get() = attributes and ATTR_DIR != 0
    var outSector = 0L
    /** Directories: the table's bytes (an empty directory has a padding sector). */
    var tableSize = 0
    /** Directories: the children in table order, a binary tree's prefix order. */
    var order: List<Entry> = emptyList()
    var offset = 0
    var left: Entry? = null
    var right: Entry? = null
  }

  private fun parentOf(context: Context, folder: Uri, relativePath: String): DocumentFile? {
    var dir = DocumentFile.fromTreeUri(context, folder) ?: return null
    for (segment in relativePath.split('/').dropLast(1)) {
      dir = dir.findFile(segment)?.takeIf { it.isDirectory } ?: return null
    }
    return dir
  }

  private fun readAt(ch: FileChannel, pos: Long, n: Int): ByteBuffer {
    val buf = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    var at = pos
    while (buf.hasRemaining()) {
      val got = ch.read(buf, at)
      if (got <= 0) throw IOException("The image ends early")
      at += got
    }
    buf.flip()
    return buf
  }

  /** A directory table's entries, walking its tree (an all-0xFF table is empty). */
  private fun readDir(ch: FileChannel, partition: Long, sector: Long, size: Long, depth: Int): List<Entry> {
    if (size == 0L) return emptyList()
    if (depth > 64 || size > 64L shl 20) throw IOException("Directory tree too deep or too large")
    val t = readAt(ch, partition + sector * SECTOR, size.toInt())
    val out = ArrayList<Entry>()
    val seen = HashSet<Int>()
    val pending = ArrayDeque<Int>().apply { add(0) }
    while (pending.isNotEmpty()) {
      val off = pending.removeLast()
      if (off < 0 || off + 14 > t.capacity() || !seen.add(off)) continue
      val left = t.getShort(off).toInt() and 0xFFFF
      if (left == 0xFFFF) continue // padding
      val right = t.getShort(off + 2).toInt() and 0xFFFF
      val nameLen = t.get(off + 13).toInt() and 0xFF
      if (off + 14 + nameLen > t.capacity()) continue
      val entry = Entry(
        name = ByteArray(nameLen) { t.get(off + 14 + it) },
        attributes = t.get(off + 12).toInt() and 0xFF,
        sector = t.getInt(off + 4).toLong() and MASK,
        size = t.getInt(off + 8).toLong() and MASK,
        children = null,
      )
      if (entry.isDir) entry.children = readDir(ch, partition, entry.sector, entry.size, depth + 1)
      out += entry
      if (right != 0) pending.add(right * 4)
      if (left != 0) pending.add(left * 4)
    }
    return out
  }

  /** extract-xiso's name order: ASCII upper-cased, bytes compared as C chars. */
  private val NAME_ORDER = Comparator<Entry> { a, b ->
    val n = minOf(a.name.size, b.name.size)
    for (i in 0 until n) {
      var x = a.name[i].toInt()
      var y = b.name[i].toInt()
      if (x in 'a'.code..'z'.code) x -= 32
      if (y in 'a'.code..'z'.code) y -= 32
      if (x != y) return@Comparator x.compareTo(y)
    }
    a.name.size.compareTo(b.name.size)
  }

  private fun nSectors(bytes: Long): Long = (bytes + SECTOR - 1) / SECTOR

  /** Tables and sectors for the whole tree, in the order the image is written. */
  private fun layout(root: Entry) {
    tables(root)
    var next = ROOT_SECTOR
    fun place(dir: Entry) {
      dir.outSector = next
      next += nSectors(dir.tableSize.toLong())
      for (e in dir.order) {
        if (!e.isDir) {
          e.outSector = next
          next += nSectors(e.size)
        }
      }
      for (e in dir.order) if (e.isDir) place(e)
    }
    place(root)
  }

  /** Each directory's table: a balanced tree over its sorted names, entries placed in prefix order. */
  private fun tables(dir: Entry) {
    val sorted = dir.children.orEmpty().sortedWith(NAME_ORDER)
    val order = ArrayList<Entry>(sorted.size)
    fun build(lo: Int, hi: Int): Entry? {
      if (lo > hi) return null
      val mid = (lo + hi) / 2
      val node = sorted[mid]
      order += node
      node.left = build(lo, mid - 1)
      node.right = build(mid + 1, hi)
      return node
    }
    build(0, sorted.size - 1)
    dir.order = order
    var size = 0
    for (e in order) {
      val len = (14 + e.name.size + 3) and 3.inv()
      if (size / SECTOR != (size + len - 1) / SECTOR) size = (size + SECTOR - 1) / SECTOR * SECTOR
      e.offset = size
      size += len
    }
    dir.tableSize = if (order.isEmpty()) SECTOR else size
    for (e in order) if (e.isDir) tables(e)
  }

  private fun totalBytes(dir: Entry): Long =
    dir.order.sumOf { if (it.isDir) totalBytes(it) else it.size }

  /** Sequential output with a known position. */
  private class Writer(private val ch: FileChannel) {
    var position = 0L
      private set
    private val buf = ByteBuffer.allocateDirect(COPY_CHUNK)

    fun put(b: ByteBuffer) {
      while (b.hasRemaining()) position += ch.write(b)
    }

    fun put(bytes: ByteArray) = put(ByteBuffer.wrap(bytes))

    fun fill(n: Long, value: Byte) {
      var left = n
      while (left > 0) {
        val k = minOf(left, buf.capacity().toLong()).toInt()
        buf.clear()
        for (i in 0 until k) buf.put(value)
        buf.flip()
        put(buf)
        left -= k
      }
    }

    fun zeros(n: Long) = fill(n, 0)

    fun seekTo(pos: Long) {
      check(pos >= position) { "layout out of order at $position, wanted $pos" }
      zeros(pos - position)
    }

    /** n bytes of src from pos; each chunk reported to copied. */
    fun copy(src: FileChannel, pos: Long, n: Long, copied: (Long) -> Unit) {
      var at = pos
      var left = n
      while (left > 0) {
        buf.clear()
        buf.limit(minOf(left, buf.capacity().toLong()).toInt())
        while (buf.hasRemaining()) {
          val got = src.read(buf, at + buf.position())
          if (got <= 0) throw IOException("The image ends early")
        }
        buf.flip()
        val k = buf.remaining()
        put(buf)
        at += k
        left -= k
        copied(k.toLong())
      }
    }

    fun copy(input: java.io.InputStream, size: Long, copied: (Long) -> Unit) {
      val bytes = ByteArray(1024 * 1024)
      var remaining = size
      while (remaining > 0) {
        val wanted = minOf(bytes.size.toLong(), remaining).toInt()
        var count = 0
        while (count < wanted) {
          val read = input.read(bytes, count, wanted - count)
          if (read < 0) throw IOException("Um arquivo terminou antes do esperado")
          if (read > 0) count += read
        }
        put(ByteBuffer.wrap(bytes, 0, count))
        remaining -= count
        copied(count.toLong())
      }
    }

    fun flush() = ch.force(false)
  }

  /** The volume descriptor at 0x10000: magic, root directory sector and size, filetime, magic. */
  private fun writeHeader(w: Writer, root: Entry, fileTime: ByteArray) {
    w.zeros(VOLUME)
    val vd = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN)
    vd.put(MAGIC.toByteArray(Charsets.US_ASCII))
    vd.putInt(root.outSector.toInt())
    vd.putInt(root.tableSize)
    vd.put(fileTime)
    vd.position(SECTOR - MAGIC.length)
    vd.put(MAGIC.toByteArray(Charsets.US_ASCII))
    vd.flip()
    w.put(vd)
  }

  /** A directory's table, its files, then its subdirectories. */
  private fun emit(w: Writer, src: FileChannel, partition: Long, dir: Entry, copied: (Long) -> Unit) {
    w.seekTo(dir.outSector * SECTOR)
    val table = ByteBuffer.allocate((nSectors(dir.tableSize.toLong()) * SECTOR).toInt()).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until table.capacity()) table.put(i, 0xFF.toByte())
    for (e in dir.order) {
      table.position(e.offset)
      table.putShort(((e.left?.offset ?: 0) / 4).toShort())
      table.putShort(((e.right?.offset ?: 0) / 4).toShort())
      table.putInt(e.outSector.toInt())
      table.putInt(if (e.isDir) (nSectors(e.tableSize.toLong()) * SECTOR).toInt() else e.size.toInt())
      table.put(e.attributes.toByte())
      table.put(e.name.size.toByte())
      table.put(e.name)
    }
    table.position(0)
    w.put(table)
    for (e in dir.order) {
      if (e.isDir) continue
      w.seekTo(e.outSector * SECTOR)
      w.copy(src, partition + e.sector * SECTOR, e.size, copied)
      w.fill((SECTOR - e.size % SECTOR) % SECTOR, 0xFF.toByte())
    }
    for (e in dir.order) if (e.isDir) emit(w, src, partition, e, copied)
  }
}
