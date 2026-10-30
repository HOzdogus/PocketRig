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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
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
    private lateinit var statesDir: File

    /** Save-state file for a slot; slot 0 is the automatic one made when you exit. */
    private fun stateFile(slot: Int): File = File(statesDir, if (slot == 0) "auto.state" else "slot$slot.state")

    private fun slotLabel(slot: Int): String {
        val f = stateFile(slot)
        return if (f.isFile) {
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(f.lastModified()))
        } else {
            "empty"
        }
    }

    private suspend fun saveState(slot: Int): Boolean {
        val view = retroView ?: return false
        return try {
            val bytes = withContext(Dispatchers.IO) { view.serializeState() }
            if (bytes.isEmpty()) return false
            withContext(Dispatchers.IO) {
                val tmp = File(statesDir, "${stateFile(slot).name}.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(stateFile(slot))
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "Couldn't save DOS state")
            false
        }
    }

    private suspend fun loadState(slot: Int): Boolean {
        val view = retroView ?: return false
        val f = stateFile(slot)
        if (!f.isFile) return false
        return try {
            val bytes = withContext(Dispatchers.IO) { f.readBytes() }
            withContext(Dispatchers.IO) { view.unserializeState(bytes) }
        } catch (e: Exception) {
            Timber.w(e, "Couldn't load DOS state")
            false
        }
    }

    private fun exitWithAutoSave() {
        lifecycleScope.launch {
            saveState(0)
            finish()
        }
    }

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

        statesDir = File(DosGames.statesFolder(this), File(gamePath).name).apply { mkdirs() }
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
                        onExit = ::exitWithAutoSave,
                        hasResume = stateFile(0).isFile,
                        slotLabel = ::slotLabel,
                        onSave = { slot, done -> lifecycleScope.launch { done(saveState(slot)) } },
                        onLoad = { slot, done ->
                            lifecycleScope.launch {
                                // Right after launch the game may still be starting, so retry briefly.
                                var ok = loadState(slot)
                                var tries = 0
                                while (!ok && tries < 5 && stateFile(slot).isFile) {
                                    delay(800)
                                    ok = loadState(slot)
                                    tries++
                                }
                                done(ok)
                            }
                        },
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

        // Back exits the game, saving where you left off.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = exitWithAutoSave()
            },
        )
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
private fun DosOverlay(
    onKey: (Int, Boolean) -> Unit,
    onExit: () -> Unit,
    hasResume: Boolean,
    slotLabel: (Int) -> String,
    onSave: (slot: Int, done: (Boolean) -> Unit) -> Unit,
    onLoad: (slot: Int, done: (Boolean) -> Unit) -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    var slot by remember { mutableStateOf(1) }
    var message by remember { mutableStateOf<String?>(null) }
    var showResume by remember { mutableStateOf(hasResume) }
    var refresh by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            message = null
        }
    }
    androidx.compose.runtime.LaunchedEffect(showResume) {
        if (showResume) {
            delay(10_000)
            showResume = false
        }
    }

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
            TextButton(onClick = { slot = slot % 3 + 1 }) {
                // Reading refresh makes the label update after a save.
                val label = if (refresh >= 0) slotLabel(slot) else ""
                Text("Slot $slot ($label)", color = Color.White)
            }
            TextButton(onClick = {
                onSave(slot) { ok ->
                    message = if (ok) "Saved to slot $slot" else "Couldn't save"
                    refresh++
                }
            }) { Text("Save", color = Color.White) }
            TextButton(onClick = {
                onLoad(slot) { ok -> message = if (ok) "Loaded slot $slot" else "Slot $slot is empty" }
            }) { Text("Load", color = Color.White) }
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide pad" else "Show pad", color = Color.White)
            }
        }

        if (showResume) {
            TextButton(
                onClick = {
                    showResume = false
                    onLoad(0) { ok -> message = if (ok) "Resumed where you left off" else "Couldn't resume" }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 8.dp),
            ) { Text("Resume where you left off", color = Color.White, fontSize = 18.sp) }
        }

        message?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
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
