package app.gamenative.dos

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import java.io.File
import timber.log.Timber

/**
 * PocketRig: plays a DOS game with DOSBox Pure (a libretro core) through LibretroDroid.
 *
 * DOSBox Pure opens .zip games directly, asks which .exe to run the first time and remembers it,
 * and has its own on-screen keyboard (the "Keyboard" button here sends the L3 button that opens it).
 */
class DosPlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_GAME = "game_path"
        const val CORE_LIB = "libdosbox_pure_libretro_android.so"

        fun start(context: Context, game: File) {
            context.startActivity(
                Intent(context, DosPlayerActivity::class.java)
                    .putExtra(EXTRA_GAME, game.absolutePath)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** True when the DOS core was packaged into this build. */
        fun isCoreAvailable(context: Context): Boolean =
            File(context.applicationInfo.nativeLibraryDir, CORE_LIB).isFile
    }

    private var retroView: GLRetroView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val gamePath = intent.getStringExtra(EXTRA_GAME)
        if (gamePath.isNullOrBlank() || !File(gamePath).exists() || !isCoreAvailable(this)) {
            Timber.w("DOS player can't start: game=$gamePath core=${isCoreAvailable(this)}")
            finish()
            return
        }

        val system = File(filesDir, "dos/system").apply { mkdirs() }
        val saves = File(filesDir, "dos/saves").apply { mkdirs() }

        val data = GLRetroViewData(this).apply {
            coreFilePath = CORE_LIB
            gameFilePath = gamePath
            systemDirectory = system.absolutePath
            savesDirectory = saves.absolutePath
            shader = ShaderConfig.Sharp
            preferLowLatencyAudio = true
            rumbleEventsEnabled = false
        }

        val view = GLRetroView(this, data).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        retroView = view
        lifecycle.addObserver(view)

        val root = FrameLayout(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            ComposeView(this).apply {
                setContent {
                    DosOverlay(
                        onKey = { code, down ->
                            view.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
                        },
                        onExit = { finish() },
                    )
                }
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        view.requestFocus()
    }

    // A physical controller goes straight to the game, even if the overlay has focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = retroView
        if (view != null && isGamepadKey(event.keyCode)) {
            view.sendKeyEvent(event.action, event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val view = retroView
        if (view != null && event.action == MotionEvent.ACTION_MOVE) {
            view.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_DPAD,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            )
            view.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
            )
            view.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
                event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ),
            )
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isGamepadKey(code: Int): Boolean = KeyEvent.isGamepadButton(code) ||
        code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
}

/** On-screen controls. DOSBox Pure maps these buttons to each game's keys automatically. */
@Composable
private fun DosOverlay(onKey: (Int, Boolean) -> Unit, onExit: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onExit) { Text("Exit", color = Color.White) }
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide pad" else "Show pad", color = Color.White)
            }
        }
        if (!visible) return@Box

        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Key("▲", KeyEvent.KEYCODE_DPAD_UP, onKey)
            Row {
                Key("◀", KeyEvent.KEYCODE_DPAD_LEFT, onKey)
                Spacer(Modifier.size(52.dp))
                Key("▶", KeyEvent.KEYCODE_DPAD_RIGHT, onKey)
            }
            Key("▼", KeyEvent.KEYCODE_DPAD_DOWN, onKey)
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Key("Y", KeyEvent.KEYCODE_BUTTON_Y, onKey, CircleShape)
            Row {
                Key("X", KeyEvent.KEYCODE_BUTTON_X, onKey, CircleShape)
                Spacer(Modifier.size(52.dp))
                Key("B", KeyEvent.KEYCODE_BUTTON_B, onKey, CircleShape)
            }
            Key("A", KeyEvent.KEYCODE_BUTTON_A, onKey, CircleShape)
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Key("L", KeyEvent.KEYCODE_BUTTON_L1, onKey)
            Key("Select", KeyEvent.KEYCODE_BUTTON_SELECT, onKey, wide = true)
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Key("Start", KeyEvent.KEYCODE_BUTTON_START, onKey, wide = true)
            Key("R", KeyEvent.KEYCODE_BUTTON_R1, onKey)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // L3 opens DOSBox Pure's on-screen keyboard; R3 its controller-mapping menu.
            Key("Keyboard", KeyEvent.KEYCODE_BUTTON_THUMBL, onKey, wide = true)
            Key("Menu", KeyEvent.KEYCODE_BUTTON_THUMBR, onKey, wide = true)
        }
    }
}

@Composable
private fun Key(
    label: String,
    code: Int,
    onKey: (Int, Boolean) -> Unit,
    shape: Shape = RoundedCornerShape(12.dp),
    wide: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(width = if (wide) 84.dp else 52.dp, height = 52.dp)
            .background(Color.White.copy(alpha = if (pressed) 0.45f else 0.16f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .pointerInput(code) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onKey(code, true)
                    waitForUpOrCancellation()
                    pressed = false
                    onKey(code, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = if (wide) 13.sp else 17.sp)
    }
}
