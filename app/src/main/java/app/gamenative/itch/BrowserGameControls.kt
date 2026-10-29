package app.gamenative.itch

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Buttons on a controller (or on the on-screen pad). */
enum class PadButton { UP, DOWN, LEFT, RIGHT, A, B, X, Y, START, SELECT, L1, R1 }

/** Which keyboard keys the buttons press. Most web games use one of these layouts. */
enum class ControlPreset(val label: String, private val keys: Map<PadButton, Int>) {
    ARROWS_ZX(
        "Arrows + Z / X",
        mapOf(
            PadButton.A to KeyEvent.KEYCODE_Z, PadButton.B to KeyEvent.KEYCODE_X,
            PadButton.X to KeyEvent.KEYCODE_C, PadButton.Y to KeyEvent.KEYCODE_V,
            PadButton.L1 to KeyEvent.KEYCODE_SHIFT_LEFT, PadButton.R1 to KeyEvent.KEYCODE_CTRL_LEFT,
        ),
    ),
    ARROWS_SPACE(
        "Arrows + Space / Shift",
        mapOf(
            PadButton.A to KeyEvent.KEYCODE_SPACE, PadButton.B to KeyEvent.KEYCODE_SHIFT_LEFT,
            PadButton.X to KeyEvent.KEYCODE_Z, PadButton.Y to KeyEvent.KEYCODE_X,
            PadButton.L1 to KeyEvent.KEYCODE_Q, PadButton.R1 to KeyEvent.KEYCODE_E,
        ),
    ),
    WASD(
        "WASD + J / K",
        mapOf(
            PadButton.UP to KeyEvent.KEYCODE_W, PadButton.DOWN to KeyEvent.KEYCODE_S,
            PadButton.LEFT to KeyEvent.KEYCODE_A, PadButton.RIGHT to KeyEvent.KEYCODE_D,
            PadButton.A to KeyEvent.KEYCODE_J, PadButton.B to KeyEvent.KEYCODE_K,
            PadButton.X to KeyEvent.KEYCODE_L, PadButton.Y to KeyEvent.KEYCODE_I,
            PadButton.L1 to KeyEvent.KEYCODE_Q, PadButton.R1 to KeyEvent.KEYCODE_E,
        ),
    ),
    ;

    fun keyFor(b: PadButton): Int = keys[b] ?: when (b) {
        PadButton.UP -> KeyEvent.KEYCODE_DPAD_UP
        PadButton.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        PadButton.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        PadButton.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
        PadButton.START -> KeyEvent.KEYCODE_ENTER
        PadButton.SELECT -> KeyEvent.KEYCODE_ESCAPE
        else -> KeyEvent.KEYCODE_SPACE
    }

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: ARROWS_ZX
    }
}

/**
 * A WebView that, in game mode, turns controller input into keyboard presses so web games that
 * only understand the keyboard can be played with a controller.
 */
@SuppressLint("ViewConstructor")
class GameWebView(context: Context) : WebView(context) {
    /** When true, controller buttons and sticks are translated into key presses. */
    var mapController = false
    var preset = ControlPreset.ARROWS_ZX

    private val stickHeld = mutableSetOf<PadButton>()

    fun press(button: PadButton, down: Boolean) = sendKey(preset.keyFor(button), down)

    private fun sendKey(code: Int, down: Boolean) {
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(
            now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        super.dispatchKeyEvent(ev)
    }

    private fun isController(ev: android.view.InputEvent): Boolean {
        val s = ev.source
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (s and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD && ev.device?.isVirtual == false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!mapController || !isController(event)) return super.dispatchKeyEvent(event)
        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> PadButton.A
            KeyEvent.KEYCODE_BUTTON_B -> PadButton.B
            KeyEvent.KEYCODE_BUTTON_X -> PadButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> PadButton.Y
            KeyEvent.KEYCODE_BUTTON_START -> PadButton.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> PadButton.SELECT
            KeyEvent.KEYCODE_BUTTON_L1 -> PadButton.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> PadButton.R1
            KeyEvent.KEYCODE_DPAD_UP -> PadButton.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PadButton.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadButton.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PadButton.RIGHT
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.repeatCount == 0) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> press(button, true)
                KeyEvent.ACTION_UP -> press(button, false)
            }
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!mapController || !isController(event) || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        // Left stick and the d-pad "hat" both act as the direction keys.
        val x = pick(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_X))
        val y = pick(event.getAxisValue(MotionEvent.AXIS_HAT_Y), event.getAxisValue(MotionEvent.AXIS_Y))
        setHeld(PadButton.LEFT, x < -0.5f)
        setHeld(PadButton.RIGHT, x > 0.5f)
        setHeld(PadButton.UP, y < -0.5f)
        setHeld(PadButton.DOWN, y > 0.5f)
        return true
    }

    private fun pick(hat: Float, stick: Float) = if (kotlin.math.abs(hat) > 0.1f) hat else stick

    private fun setHeld(b: PadButton, held: Boolean) {
        if (held && stickHeld.add(b)) press(b, true)
        if (!held && stickHeld.remove(b)) press(b, false)
    }
}

/** On-screen d-pad and buttons for playing web games without a controller. */
@Composable
fun OnScreenGamePad(
    onButton: (PadButton, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(16.dp)) {
        // D-pad, bottom left
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PadKey("▲", PadButton.UP, onButton)
            Row {
                PadKey("◀", PadButton.LEFT, onButton)
                Spacer(Modifier.size(56.dp))
                PadKey("▶", PadButton.RIGHT, onButton)
            }
            PadKey("▼", PadButton.DOWN, onButton)
        }
        // Face buttons, bottom right (Xbox-style layout)
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PadKey("Y", PadButton.Y, onButton, CircleShape)
            Row {
                PadKey("X", PadButton.X, onButton, CircleShape)
                Spacer(Modifier.size(56.dp))
                PadKey("B", PadButton.B, onButton, CircleShape)
            }
            PadKey("A", PadButton.A, onButton, CircleShape)
        }
        // Shoulders and menu buttons along the top corners
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey("L", PadButton.L1, onButton)
            PadKey("Select", PadButton.SELECT, onButton, RoundedCornerShape(12.dp), wide = true)
        }
        Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey("Start", PadButton.START, onButton, RoundedCornerShape(12.dp), wide = true)
            PadKey("R", PadButton.R1, onButton)
        }
    }
}

@Composable
private fun PadKey(
    label: String,
    button: PadButton,
    onButton: (PadButton, Boolean) -> Unit,
    shape: Shape = RoundedCornerShape(12.dp),
    wide: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(width = if (wide) 72.dp else 56.dp, height = 56.dp)
            .background(Color.White.copy(alpha = if (pressed) 0.45f else 0.18f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onButton(button, true)
                    waitForUpOrCancellation()
                    pressed = false
                    onButton(button, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = if (wide) 13.sp else 18.sp)
    }
}

/** Web games you've saved, shown on the browser's start page. */
object SavedBrowserGames {
    private const val PREFS = "pocketrig_browser"
    private const val KEY = "saved_games"

    data class Game(val title: String, val url: String, val coverUrl: String?, val preset: ControlPreset)

    fun load(context: Context): List<Game> = runCatching {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Game(
                o.optString("title"), o.optString("url"),
                o.optString("cover").ifBlank { null }, ControlPreset.fromName(o.optString("preset")),
            )
        }
    }.getOrDefault(emptyList())

    private fun store(context: Context, games: List<Game>) {
        val arr = JSONArray()
        games.forEach {
            arr.put(
                JSONObject().put("title", it.title).put("url", it.url)
                    .put("cover", it.coverUrl ?: "").put("preset", it.preset.name),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun save(context: Context, game: Game) = store(context, listOf(game) + load(context).filter { it.url != game.url })

    fun remove(context: Context, url: String) = store(context, load(context).filter { it.url != url })

    fun updatePreset(context: Context, url: String, preset: ControlPreset) =
        store(context, load(context).map { if (it.url == url) it.copy(preset = preset) else it })
}
