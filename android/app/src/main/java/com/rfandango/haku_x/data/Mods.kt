package com.rfandango.haku_x.data

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * A mod (mods/README.md): its folder name, what it says about itself, its XBE
 * patches (file, line) and the vblank rate it asks for (0: the video mode's).
 */
data class ModInfo(
  val id: String,
  val name: String,
  val description: String,
  val author: String,
  val patches: List<Pair<String, String>>,
  val refresh: Int = 0,
)

/** A cheat (mods/README.md): its name, note, once, and its code lines as written. */
data class CheatInfo(val name: String, val note: String, val once: Boolean, val code: List<String>)

/** A disc's mods and cheats. */
class GameMods(val mods: List<ModInfo>, val cheats: List<CheatInfo>) {
  val isEmpty get() = mods.isEmpty() && cheats.isEmpty()
}

/**
 * The repository's mods/ folder, packed as the APK's assets/mods: a folder
 * per game key (GameOverrides.gameKey: title ID and certificate version,
 * 54430006.6), a cheats.txt and a folder per mod in it.
 */
object ModCatalog {
  private const val ROOT = "mods"
  private const val TAG = "hakuX-mods"
  /** Game key (upper case) -> its folder's name as the repository has it. */
  @Volatile private var folders: Map<String, String>? = null

  private fun folders(context: Context): Map<String, String> = folders ?: run {
    val map = context.assets.list(ROOT).orEmpty().associateBy { it.uppercase(Locale.ROOT) }
    folders = map
    map
  }

  private fun localFolder(context: Context, gameKey: String) =
    File(context.filesDir, "downloaded_mods/${gameKey.uppercase(Locale.ROOT)}")

  private fun folder(context: Context, gameKey: String) = folders(context)[gameKey.uppercase(Locale.ROOT)]

  /** Whether this exact disc release has bundled or downloaded content. */
  fun has(context: Context, gameKey: String) =
    localFolder(context, gameKey).isDirectory || folder(context, gameKey) != null

  /**
   * Downloads the curated files for one exact TitleID.version from this
   * project's public mods catalog. Files are staged and swapped atomically;
   * no game files or user-created mods are overwritten.
   */
  fun download(context: Context, gameKey: String): Boolean {
    val key = gameKey.uppercase(Locale.ROOT)
    if (!key.matches(Regex("[0-9A-F]{8}\\.\\d+"))) return false
    val connection = (URL("https://api.github.com/repos/cleiltonBR/hakuX/git/trees/ui%2Fxbox-series-x-on-build307?recursive=1")
      .openConnection() as HttpURLConnection).apply {
      connectTimeout = 15000
      readTimeout = 30000
      setRequestProperty("Accept", "application/vnd.github+json")
      setRequestProperty("User-Agent", "HakuX-Android")
    }
    val stage = File(context.cacheDir, "mods-download/$key.tmp")
    return try {
      val body = connection.inputStream.bufferedReader().use { it.readText() }
      val tree = JSONObject(body).getJSONArray("tree")
      val prefix = "$ROOT/$key/"
      val entries = (0 until tree.length()).map { tree.getJSONObject(it) }
        .filter { it.optString("type") == "blob" && it.optString("path").startsWith(prefix) }
      if (entries.isEmpty()) return false
      stage.deleteRecursively()
      var total = 0L
      for (entry in entries) {
        val path = entry.getString("path")
        val relative = path.removePrefix(prefix)
        val parts = relative.split('/')
        if (relative.isBlank() || parts.any { it.isBlank() || it == "." || it == ".." } ||
            relative.startsWith("/") || relative.contains('\\')) continue
        val size = entry.optLong("size", 0L)
        if (size < 0 || size > 16L * 1024 * 1024 || total + size > 64L * 1024 * 1024)
          throw IllegalStateException("Catálogo excede o limite de tamanho")
        val encoded = relative.split('/').joinToString("/") {
          java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val raw = URL("https://raw.githubusercontent.com/cleiltonBR/hakuX/ui/xbox-series-x-on-build307/$ROOT/$key/$encoded")
          .openConnection() as HttpURLConnection
        raw.connectTimeout = 15000
        raw.readTimeout = 30000
        raw.setRequestProperty("User-Agent", "HakuX-Android")
        val target = File(stage, relative)
        target.parentFile?.mkdirs()
        raw.inputStream.use { input -> target.outputStream().use { output ->
          total += input.copyTo(output)
          if (total > 64L * 1024 * 1024) throw IllegalStateException("Download excede 64 MB")
        } }
        raw.disconnect()
      }
      if (stage.listFiles().isNullOrEmpty()) return false
      val parent = localFolder(context, key).parentFile!!
      parent.mkdirs()
      val target = localFolder(context, key)
      val backup = File(parent, "$key.old")
      backup.deleteRecursively()
      if (target.exists() && !target.renameTo(backup)) throw IllegalStateException("Não foi possível preparar a atualização")
      if (!stage.renameTo(target)) {
        if (backup.exists()) backup.renameTo(target)
        throw IllegalStateException("Não foi possível instalar o catálogo baixado")
      }
      backup.deleteRecursively()
      true
    } catch (e: Exception) {
      Log.w(TAG, "$key: download failed: ${e.message}")
      stage.deleteRecursively()
      false
    } finally {
      connection.disconnect()
    }
  }

  /** The disc's mods (by folder name) and cheats (in file order). */
  fun load(context: Context, gameKey: String): GameMods {
    val local = localFolder(context, gameKey)
    if (local.isDirectory) {
      val mods = local.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        .mapNotNull { dir -> File(dir, "mod.txt").takeIf { it.isFile }?.let { parseMod(dir.name, it.readText()) } }
      val cheats = File(local, "cheats.txt").takeIf { it.isFile }?.let { parseCheats(it.readText()) }.orEmpty()
      return GameMods(mods, cheats)
    }
    val dir = folder(context, gameKey) ?: return GameMods(emptyList(), emptyList())
    val am = context.assets
    val base = "$ROOT/$dir"
    val mods = am.list(base).orEmpty().sorted().mapNotNull { id ->
      text(am, "$base/$id/mod.txt")?.let { parseMod(id, it) }
    }
    val cheats = text(am, "$base/cheats.txt")?.let { parseCheats(it) }.orEmpty()
    return GameMods(mods, cheats)
  }

  private fun text(am: AssetManager, path: String): String? =
    runCatching { am.open(path).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

  private fun content(line: String) = line.trim().takeUnless { it.isEmpty() || it.startsWith("#") }

  private fun header(line: String): Pair<String, String>? {
    val colon = line.indexOf(':')
    if (colon <= 0) return null
    val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
    return if (key.all { it.isLetter() }) key to line.substring(colon + 1).trim() else null
  }

  private fun parseMod(id: String, text: String): ModInfo {
    val fields = mutableMapOf<String, String>()
    val patches = mutableListOf<Pair<String, String>>()
    var xbe: String? = null
    for (raw in text.lines()) {
      val line = content(raw) ?: continue
      if (line.startsWith("[") && line.endsWith("]")) {
        xbe = line.substring(1, line.length - 1).trim()
        continue
      }
      val x = xbe
      if (x != null) {
        patches += x to line
      } else {
        header(line)?.let { (k, v) -> fields[k] = v }
      }
    }
    return ModInfo(
      id = id,
      name = fields["name"]?.takeIf { it.isNotEmpty() } ?: id,
      description = fields["description"].orEmpty(),
      author = fields["author"].orEmpty(),
      patches = patches,
      refresh = fields["refresh"]?.toIntOrNull() ?: 0,
    )
  }

  private fun parseCheats(text: String): List<CheatInfo> {
    val out = mutableListOf<CheatInfo>()
    var name: String? = null
    var note = ""
    var once = false
    val code = mutableListOf<String>()
    fun flush() {
      name?.let { out += CheatInfo(it, note, once, code.toList()) }
      note = ""
      once = false
      code.clear()
    }
    for (raw in text.lines()) {
      val line = content(raw) ?: continue
      if (line.startsWith("[") && line.endsWith("]")) {
        flush()
        name = line.substring(1, line.length - 1).trim()
        continue
      }
      if (name == null) continue
      val h = header(line)
      when {
        h?.first == "note" -> note = h.second
        line.equals("once", ignoreCase = true) -> once = true
        else -> code += line
      }
    }
    flush()
    return out
  }

  /**
   * A mod's files (assets mods/<game>/<mod>/files/...) in the app's storage
   * for the core to read, extracted again after the app changed: their disc
   * paths and host files.
   */
  fun extract(context: Context, gameKey: String, mod: String): List<Pair<String, File>> {
    val local = File(localFolder(context, gameKey), "$mod/files")
    if (local.isDirectory) {
      return local.walkTopDown().filter { it.isFile }.map { it.relativeTo(local).path to it }.toList()
    }
    val dir = folder(context, gameKey) ?: return emptyList()
    val am = context.assets
    val src = "$ROOT/$dir/$mod/files"
    val dst = File(context.filesDir, "mods/${gameKey.uppercase(Locale.ROOT)}/$mod")
    val files = File(dst, "files")
    val stamp = File(dst, ".stamp")
    val app = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
    if (stamp.isFile && stamp.readText() == app) {
      return files.walkTopDown().filter { it.isFile }.map { it.relativeTo(files).path to it }.toList()
    }
    dst.deleteRecursively()
    val out = mutableListOf<Pair<String, File>>()
    fun copy(asset: String, rel: String) {
      val children = am.list(asset).orEmpty()
      if (children.isEmpty()) {
        val target = File(files, rel)
        target.parentFile?.mkdirs()
        runCatching { am.open(asset).use { i -> target.outputStream().use { i.copyTo(it) } } }
          .onSuccess { out += rel to target }
          .onFailure { Log.w(TAG, "$asset: ${it.message}") }
        return
      }
      for (c in children) copy("$asset/$c", if (rel.isEmpty()) c else "$rel/$c")
    }
    if (am.list(src).orEmpty().isNotEmpty()) copy(src, "")
    dst.mkdirs()
    stamp.writeText(app)
    return out
  }
}

/**
 * One disc's choices: its mods on or off, its cheats on or off, by name. In
 * one JSON file ("<game>/mod/<id>" or "<game>/cheat/<name>" -> true) both
 * processes read afresh and write whole, renamed into place, as Controllers:
 * the game's menu toggles cheats in its own process, and shared preferences
 * served the Mods screen its cached copy, whose next toggle wrote the
 * game's changes away.
 */
class ModChoices(context: Context, private val game: String) {
  private val file = File(context.filesDir, FILE)
  private val app = context.applicationContext

  fun mod(id: String) = read().optBoolean("$game/mod/$id", false)
  fun setMod(id: String, on: Boolean) = write("$game/mod/$id", on)
  fun cheat(name: String) = read().optBoolean("$game/cheat/$name", false)
  fun setCheat(name: String, on: Boolean) = write("$game/cheat/$name", on)

  private fun read(): JSONObject = synchronized(LOCK) {
    if (!file.exists()) {
      // the choices the shared preferences (per_game_mods) held before; the file is left as it was
      val old = app.getSharedPreferences(NAME, Context.MODE_PRIVATE).all
      return JSONObject().apply { for ((k, v) in old) if (v is Boolean) put(k, v) }
    }
    try {
      JSONObject(file.readText())
    } catch (_: Exception) {
      JSONObject()
    }
  }

  private fun write(key: String, on: Boolean) {
    synchronized(LOCK) {
      val o = read()
      if (on) o.put(key, true) else o.remove(key)
      val tmp = File(file.parentFile, "$FILE.${android.os.Process.myPid()}.tmp")
      tmp.writeText(o.toString())
      tmp.renameTo(file)
    }
  }

  companion object {
    const val NAME = "per_game_mods"
    private const val FILE = "mod_choices.json"
    private val LOCK = Any()
  }
}

/**
 * The run's manifest for the core (native/core/mods.h): the enabled mods'
 * files and XBE patches, every cheat with its state. The cheats in their
 * order here are the in-game menu's (nativeSetCheat takes the index).
 */
object ModRun {
  private const val TAG = "hakuX-mods"

  /** The manifest written (null: the game has none), and the run's cheats. */
  fun write(context: Context, gameKey: String): Pair<File, List<CheatInfo>>? {
    val game = ModCatalog.load(context, gameKey)
    if (game.isEmpty) return null
    val choices = ModChoices(context, gameKey)
    val lines = mutableListOf<String>()
    val on = game.mods.filter { choices.mod(it.id) }
    for (mod in on) {
      for ((disc, file) in ModCatalog.extract(context, gameKey, mod.id)) {
        lines += "file\t${disc.replace('/', '\\')}\t${file.absolutePath}"
      }
    }
    for (mod in game.mods) {
      // a mod that is off too: a savestate saved with it on gets the disc's bytes back
      val kind = if (mod in on) "patch" else "patchoff"
      for ((xbe, line) in mod.patches) lines += "$kind\t$xbe\t${mod.id}\t$line"
    }
    on.maxOfOrNull { it.refresh }?.takeIf { it > 0 }?.let { lines += "refresh\t$it" }
    for (c in game.cheats) {
      lines += "cheat\t${if (choices.cheat(c.name)) 1 else 0}\t${if (c.once) 1 else 0}\t${c.name}"
      for (l in c.code) lines += "code\t$l"
    }
    val file = File(context.filesDir, "native/mods.txt")
    file.parentFile?.mkdirs()
    file.writeText(lines.joinToString("\n", postfix = "\n"))
    Log.i(TAG, "$gameKey: ${on.size} of ${game.mods.size} mods on, ${game.cheats.size} cheats")
    return file to game.cheats
  }
}
