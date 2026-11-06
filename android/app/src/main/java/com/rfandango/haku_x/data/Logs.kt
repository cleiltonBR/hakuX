package com.rfandango.haku_x.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.rfandango.haku_x.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * The app's logs (Settings, Export logs). An app session is the main
 * process's life; its files are in logs/current: session.txt, app.log (the
 * main process) and one game-<n>-<name>.log per game process. Each process
 * writes its own log from logcat (its pid's lines, everything it logs), so a
 * game's log goes on when Android kills the launcher behind it, and lines
 * reach the file as they come: a crash leaves the file as it was.
 *
 * At the main process's start the previous session is zipped into
 * logs/sessions/<its start>.zip, with why its processes ended (exits.txt,
 * and Android's ANR traces and crash tombstones), and the newest SESSIONS
 * are kept. A game process still running then keeps its log, moved to the
 * new session. An export is one ZIP of those sessions and the one running.
 */
object Logs {
  private const val TAG = "hakuX-app"
  private const val SESSIONS = 4
  private const val MAX_BYTES = 32L shl 20     /* a file stops growing here */
  private const val SESSION = "session.txt"
  private const val START = "start-ms: "

  private fun root(context: Context) = File(context.filesDir, "logs")
  private fun current(context: Context) = File(root(context), "current")
  private fun sessions(context: Context) = File(root(context), "sessions")

  /** The main process starts: the previous session is packaged, a new one begins. */
  fun startApp(context: Context) {
    val app = context.applicationContext
    File(app.filesDir, "current.log").delete()      /* the xemu-based core's capture */
    File(app.filesDir, "previous.log").delete()
    val root = root(app)
    val current = current(app)
    if (!current.list().isNullOrEmpty()) {
      current.renameTo(File(root, "packing-${System.currentTimeMillis()}"))
    }
    current.mkdirs()
    val start = System.currentTimeMillis()
    File(current, SESSION).writeText(sessionInfo(app, start))
    capture(File(current, "app.log"), "=== hakuX ${BuildConfig.VERSION_NAME}: the app, pid ${Process.myPid()}\n")
    thread(isDaemon = true, name = "log-pack") {
      try {
        val live = liveGamePids(app)
        val packing = root.listFiles { f -> f.isDirectory && f.name.startsWith("packing-") }.orEmpty()
        for (dir in packing.sortedBy { it.name }) {
          pack(app, dir, start, live, current)
        }
        sessions(app).listFiles { f -> f.name.endsWith(".zip") }.orEmpty()
          .sortedByDescending { it.name }.drop(SESSIONS).forEach { it.delete() }
      } catch (e: Exception) {
        Log.e(TAG, "logs: packaging the last session failed", e)
      }
    }
  }

  /** A game process starts: its own log in the running session. */
  fun startGame(context: Context, name: String, why: String) {
    val current = current(context).apply { mkdirs() }
    val n = current.list().orEmpty().mapNotNull { GAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
      .maxOrNull()?.plus(1) ?: 1
    val file = File(current, "game-$n-${fileName(name)}.log")
    capture(file, "=== $name ($why), pid ${Process.myPid()}, started ${stamp(System.currentTimeMillis())}\n")
  }

  /**
   * Every session kept and the running one, as hakuX-logs-<now>.zip in the
   * folder tree; its name, or null if it couldn't be written.
   */
  fun export(context: Context, tree: Uri): String? {
    val dir = DocumentFile.fromTreeUri(context, tree) ?: return null
    val name = "hakuX-logs-${fileStamp(System.currentTimeMillis())}"
    val doc = dir.createFile("application/zip", name) ?: return null
    val tmp = File(context.cacheDir, "logs-current.zip")
    try {
      val current = current(context)
      val start = startOf(current) ?: System.currentTimeMillis()
      FileOutputStream(tmp).use { zipSession(context, current, start, System.currentTimeMillis(), emptySet(), it) }
      context.contentResolver.openOutputStream(doc.uri)?.use { out ->
        ZipOutputStream(out).use { zip ->
          for (f in sessions(context).listFiles { f -> f.name.endsWith(".zip") }.orEmpty().sortedBy { it.name }) {
            put(zip, f.name, f)
          }
          put(zip, "${fileStamp(start)} (current).zip", tmp)
        }
      } ?: return null
      return doc.name
    } catch (e: Exception) {
      Log.e(TAG, "logs: export failed", e)
      doc.delete()
      return null
    } finally {
      tmp.delete()
    }
  }

  /** dir (a session set aside) into sessions/<its start>.zip; the logs of live game processes go to current. */
  private fun pack(context: Context, dir: File, end: Long, live: Set<Int>, current: File) {
    for (f in dir.listFiles().orEmpty()) {
      if (GAME.matches(f.name) && pidOf(f) in live) {
        f.renameTo(File(current, f.name))           /* its writer keeps the file open */
      }
    }
    val start = startOf(dir) ?: dir.lastModified()
    val zip = File(sessions(context).apply { mkdirs() }, "${fileStamp(start)}.zip")
    FileOutputStream(zip).use { zipSession(context, dir, start, end, live, it) }
    dir.deleteRecursively()
  }

  /** A session's files and the exits of its processes (from start to end) as a ZIP. */
  private fun zipSession(context: Context, dir: File, start: Long, end: Long, skip: Set<Int>, out: OutputStream) {
    ZipOutputStream(out).use { zip ->
      for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
        if (f.isFile && !(GAME.matches(f.name) && pidOf(f) in skip)) put(zip, f.name, f)
      }
      exits(context, start, end, zip)
    }
  }

  /** Why the app's processes ended between start and end (Android 11+), with their traces. */
  private fun exits(context: Context, start: Long, end: Long, zip: ZipOutputStream) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val am = context.getSystemService(ActivityManager::class.java) ?: return
    val list = try {
      am.getHistoricalProcessExitReasons(context.packageName, 0, 0).filter { it.timestamp in start until end }
    } catch (e: Exception) {
      return
    }
    if (list.isEmpty()) return
    val text = StringBuilder()
    for (x in list.sortedBy { it.timestamp }) {
      text.append("${stamp(x.timestamp)} ${x.processName} pid ${x.pid}: ${reason(x.reason)}, status ${x.status}, " +
        "importance ${x.importance}, pss ${x.pss} KB, rss ${x.rss} KB")
      x.description?.let { text.append(": ").append(it) }
      text.append('\n')
      try {
        x.traceInputStream?.use { trace ->
          val ext = if (x.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "tombstone.pb" else "trace.txt"
          zip.putNextEntry(ZipEntry("exit-${x.pid}-${reason(x.reason)}.$ext"))
          trace.copyTo(zip)
          zip.closeEntry()
        }
      } catch (e: Exception) {
        Log.w(TAG, "logs: no trace for pid ${x.pid}: ${e.message}")
      }
    }
    zip.putNextEntry(ZipEntry("exits.txt"))
    zip.write(text.toString().toByteArray())
    zip.closeEntry()
  }

  /** The process's own logcat lines (everything it logs) appended to file, up to MAX_BYTES. */
  private fun capture(file: File, header: String) {
    thread(isDaemon = true, name = "log-capture") {
      try {
        FileOutputStream(file, true).use { out ->
          var total = file.length()
          out.write(header.toByteArray())
          val logcat = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
            .redirectErrorStream(true).start()
          val buf = ByteArray(16384)
          logcat.inputStream.use { input ->
            while (true) {
              val n = input.read(buf)
              if (n < 0) break
              out.write(buf, 0, n)                  /* unbuffered: on disk as it comes */
              total += n
              if (total >= MAX_BYTES) {
                out.write("\n=== log stopped at ${MAX_BYTES shr 20} MB\n".toByteArray())
                break                               /* and logcat with it, not read on for nothing */
              }
            }
          }
          logcat.destroy()
        }
      } catch (e: Exception) {
        Log.e(TAG, "logs: capture into ${file.name} failed", e)
      }
    }
  }

  private fun sessionInfo(context: Context, start: Long): String =
    "hakuX ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) ", debug build" else ""}\n" +
      "session started ${stamp(start)}\n" +
      "device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), ${soc()}\n" +
      "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.FINGERPRINT}\n" +
      "$START$start\n"

  private fun soc() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "SoC unknown"

  private fun startOf(dir: File): Long? =
    File(dir, SESSION).takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith(START) }
      ?.removePrefix(START)?.trim()?.toLongOrNull()

  /** The pid a game log's first line names (startGame's header). */
  private fun pidOf(file: File): Int? =
    file.bufferedReader().use { it.readLine() }?.let { PID.find(it)?.groupValues?.get(1)?.toIntOrNull() }

  /** The game processes running now. */
  private fun liveGamePids(context: Context): Set<Int> =
    context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty()
      .filter { it.processName.endsWith(":native") }.map { it.pid }.toSet()

  private fun put(zip: ZipOutputStream, name: String, file: File) {
    zip.putNextEntry(ZipEntry(name))
    file.inputStream().use { it.copyTo(zip) }
    zip.closeEntry()
  }

  private fun reason(r: Int) = when (r) {
    ApplicationExitInfo.REASON_EXIT_SELF -> "exit"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init-failure"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resources"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
    ApplicationExitInfo.REASON_OTHER -> "other"
    else -> "unknown-$r"
  }

  private fun fileName(name: String) = name.replace(Regex("[^A-Za-z0-9 ._()-]"), "_").trim().take(64).ifEmpty { "game" }
  private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
  private fun fileStamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ms))

  private val GAME = Regex("game-(\\d+)-.*\\.log")
  private val PID = Regex("pid (\\d+)")
}
