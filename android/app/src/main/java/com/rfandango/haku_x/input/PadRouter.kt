package com.rfandango.haku_x.input

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.rfandango.haku_x.data.Binding
import com.rfandango.haku_x.data.Controllers
import com.rfandango.haku_x.data.PadOut
import com.rfandango.haku_x.data.PadProfile
import com.rfandango.haku_x.data.PadState

/**
 * The physical controllers in a game: each controller's events through its
 * mapping to the player it plays as (Controllers.Config.assign), and the
 * title's rumble back to that controller. The activity hands it every
 * gamepad event before SDL could see it; the core takes each player's state
 * (send) and gives back its rumble (rumbleOf: left << 16 | right).
 */
class PadRouter(
  private val context: Context,
  private val send: (player: Int, out: PadOut) -> Unit,
  private val rumbleOf: (player: Int) -> Int,
  private val vibrationEnabled: () -> Boolean,
) {
  private var config = Controllers.load(context)
  private val states = HashMap<Int, PadState>()
  private val profiles = HashMap<Int, PadProfile>()
  /** device id -> player */
  var assignment: Map<Int, Int> = emptyMap()
    private set
  private val out = PadOut()

  /** The configuration again (the settings or the menu changed it). */
  fun reload() {
    config = Controllers.load(context)
    reassign()
  }

  /** Controllers connected or gone: who plays as whom, and nothing from a player left without one. */
  fun reassign() {
    val devices = Controllers.connected()
    assignment = config.assign(devices)
    profiles.clear()
    motors.clear()
    val ids = devices.map { it.id }.toSet()
    states.keys.retainAll(ids)
    for (p in 0 until Controllers.PLAYERS) {
      if (p !in assignment.values) send(p, PadOut())
    }
    for ((id, p) in assignment) {
      val st = states[id] ?: continue
      val d = InputDevice.getDevice(id) ?: continue
      send(p, st.compute(profile(d), out))
    }
  }

  private fun profile(d: InputDevice) = profiles.getOrPut(d.id) { config.profileFor(d) }

  /** The key is a controller's that its mapping binds to an Xbox button. */
  fun bindsKey(e: KeyEvent): Boolean {
    val d = e.device ?: return false
    if (!Controllers.isGameController(d)) return false
    return profile(d).bind.values.any { l -> l.any { it is Binding.Key && it.code == e.keyCode } }
  }

  /** A controller's key: true when it is the controller's (the activity passes it on otherwise). */
  fun onKey(e: KeyEvent): Boolean {
    val d = e.device ?: return false
    if (!Controllers.isGameController(d)) return false
    val p = profile(d)
    val bound = p.bind.values.any { l -> l.any { it is Binding.Key && it.code == e.keyCode } }
    if (!bound && !KeyEvent.isGamepadButton(e.keyCode) && e.keyCode !in DPAD_KEYS) return false
    if (!bound && e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) return false     /* the menu's (the activity) */
    val st = states.getOrPut(d.id) { PadState() }
    if (st.onKey(e)) {
      assignment[d.id]?.let { send(it, st.compute(p, out)) }
    }
    return true
  }

  /** A controller's sticks, triggers and hat. */
  fun onMotion(e: MotionEvent): Boolean {
    val d = e.device ?: return false
    if ((e.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
    if (!Controllers.isGameController(d) || e.actionMasked != MotionEvent.ACTION_MOVE) return false
    val st = states.getOrPut(d.id) { PadState() }
    st.onMotion(e)
    assignment[d.id]?.let { send(it, st.compute(profile(d), out)) }
    return true
  }

  /** device id -> the levels last sent (left << 8 | right) and when */
  private val rumble = HashMap<Int, Int>()
  private val rumbleAt = HashMap<Int, Long>()
  /** device id -> whether it has motors of its own (asked once) */
  private val motors = HashMap<Int, Boolean>()

  /**
   * The titles' rumble is sent only to the assigned physical controller.
   * Devices without controller motors are intentionally left silent: the
   * phone's vibrator must never be used as a substitute for controller rumble.
   */
  fun pollRumble() {
    if (!vibrationEnabled()) {
      if (rumble.isNotEmpty()) stopRumble()
      return
    }
    val now = SystemClock.uptimeMillis()
    for ((id, p) in assignment) {
      val raw = rumbleOf(p)
      val r = level(raw ushr 16) shl 8 or level(raw and 0xFFFF)
      val last = rumble[id] ?: 0
      if (r == last && (r == 0 || now - (rumbleAt[id] ?: 0) < 900)) continue
      rumble[id] = r
      rumbleAt[id] = now
      InputDevice.getDevice(id)?.let { vibrate(it, r ushr 8, r and 0xFF) }
    }
  }

  /** Every controller still, when the game goes away. */
  fun stopRumble() {
    for (id in rumble.keys) InputDevice.getDevice(id)?.let { vibrate(it, 0, 0) }
    rumble.clear()
  }

  private fun hasMotors(d: InputDevice): Boolean = motors.getOrPut(d.id) {
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        val managerHasMotors = runCatching { d.vibratorManager.vibratorIds.isNotEmpty() }.getOrDefault(false)
        if (managerHasMotors) true else {
          @Suppress("DEPRECATION")
          d.vibrator.hasVibrator()
        }
      } else {
        @Suppress("DEPRECATION")
        d.vibrator.hasVibrator()
      }
    } catch (_: Exception) {
      false
    }
  }

  /** A strength (0..65535) as a motor's amplitude, 0..255 (0 is off, as SDL's). */
  private fun level(v: Int) = (v * 255 + 32767) / 65535

  /*
   * A motor without amplitude control is on or off: on from a quarter of the
   * strength (else a title's faint engine rumble would be the motor's all).
   */
  private fun amplitude(v: Int, control: Boolean): Int = when {
    control -> v
    v >= ONOFF_MIN -> VibrationEffect.DEFAULT_AMPLITUDE
    else -> 0
  }

  /** Vibrate a single controller motor (never the phone's default vibrator). */
  private fun vibrateOne(vib: Vibrator, left: Int, right: Int) {
    val a = amplitude(maxOf(left, right), vib.hasAmplitudeControl())
    if (a == 0) {
      vib.cancel()
      return
    }
    val effect = VibrationEffect.createOneShot(1000, a)
    if (Build.VERSION.SDK_INT >= 33) {
      vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    } else {
      @Suppress("DEPRECATION")
      vib.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
    }
  }

  /**
   * Send rumble only through the InputDevice's own controller vibrator(s).
   * Android's application/default vibrator is deliberately never consulted.
   */
  private fun vibrate(d: InputDevice, left: Int, right: Int) {
    try {
      if (!hasMotors(d)) return
      if (Build.VERSION.SDK_INT < 31) {
        @Suppress("DEPRECATION")
        vibrateOne(d.vibrator, left, right)
        return
      }
      val vm = d.vibratorManager
      val ids = vm.vibratorIds
      if (ids.isEmpty()) {
        @Suppress("DEPRECATION")
        vibrateOne(d.vibrator, left, right)
        return
      }
      val combined = CombinedVibration.startParallel()
      var any = false
      /* Two motors: left (heavy) and right (light), when exposed by the controller. */
      for ((i, vid) in ids.withIndex()) {
        val value = if (ids.size >= 2) (if (i == 0) left else right) else maxOf(left, right)
        val motor = vm.getVibrator(vid)
        val a = amplitude(value, motor.hasAmplitudeControl())
        if (a == 0) continue
        combined.addVibrator(vid, VibrationEffect.createOneShot(1000, a))
        any = true
      }
      when {
        !any -> vm.cancel()
        Build.VERSION.SDK_INT >= 33 ->
          vm.vibrate(combined.combine(), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        else -> vm.vibrate(combined.combine())
      }
    } catch (_: Exception) {
      /* Controller disconnected or its motor rejected the request: no phone fallback. */
    }
  }

  companion object {
    private val DPAD_KEYS = setOf(
      KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
    )
    /** The level (of 255) from which an on/off motor is on. */
    private const val ONOFF_MIN = 64
  }
}
