package app.gamenative.speed

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import timber.log.Timber

/**
 * PocketRig fast-forward. Writes the speed that libpocketspeed.so (preloaded into the game's Wine
 * processes) reads. See app/src/main/cpp/pocketspeed/pocketspeed.c for the file layout.
 *
 * Only Bionic containers are supported: the library is built for Android's own C library.
 */
object SpeedControl {
    private const val TAG = "SpeedControl"
    private const val PREFS = "pocketrig_speed"
    private const val KEY_ENABLED = "enabled"
    private const val FILE_NAME = "pocketrig_speed.bin"
    private const val LIB_NAME = "libpocketspeed.so"
    private const val SIZE = 4096

    /** Speeds offered in the in-game menu. */
    val SPEEDS = listOf(1.0, 1.5, 2.0, 3.0, 4.0)

    @Volatile
    private var buffer: MappedByteBuffer? = null

    /** True when the running game was launched with fast-forward available. */
    @Volatile
    var isActiveForSession = false
        private set

    @Volatile
    var currentSpeed = 1.0
        private set

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun libFile(context: Context) = File(context.applicationInfo.nativeLibraryDir, LIB_NAME)

    /**
     * Called by the game launcher. Returns the library path to preload and the control file path,
     * or null if fast-forward is off or unavailable. Always starts the game at normal speed.
     */
    @JvmStatic
    fun prepareForLaunch(context: Context): Pair<String, String>? {
        isActiveForSession = false
        currentSpeed = 1.0
        if (!isEnabled(context)) return null
        val lib = libFile(context)
        if (!lib.isFile) {
            Timber.tag(TAG).w("Fast-forward library missing at $lib")
            return null
        }
        return try {
            val file = File(context.filesDir, FILE_NAME)
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(SIZE.toLong())
                val map = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE.toLong())
                map.order(ByteOrder.LITTLE_ENDIAN)
                buffer = map
            }
            file.setReadable(true, false)
            write(scale = 1.0, baseReal = 0L, baseVirt = 0L)
            isActiveForSession = true
            lib.absolutePath to file.absolutePath
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Couldn't prepare fast-forward")
            null
        }
    }

    /** Changes the game's speed. Time stays continuous, so the game never sees a jump backwards. */
    @Synchronized
    fun setSpeed(scale: Double) {
        val map = buffer ?: return
        if (!isActiveForSession) return
        val now = System.nanoTime() // CLOCK_MONOTONIC on Android
        val oldScale = map.getDouble(8)
        val oldBaseReal = map.getLong(16)
        val oldBaseVirt = map.getLong(24)
        val gameNow = oldBaseVirt + ((now - oldBaseReal) * oldScale).toLong()
        write(scale, now, gameNow)
        currentSpeed = scale
        Timber.tag(TAG).i("Game speed set to ${scale}x")
    }

    /** Back to normal speed when the game ends. */
    @JvmStatic
    @Synchronized
    fun endSession() {
        if (isActiveForSession && currentSpeed != 1.0) setSpeed(1.0)
        isActiveForSession = false
        buffer = null
        currentSpeed = 1.0
    }

    /** Seqlock write: odd sequence number while the values are being changed. */
    private fun write(scale: Double, baseReal: Long, baseVirt: Long) {
        val map = buffer ?: return
        val seq = map.getInt(0)
        val writing = if (seq % 2 == 0) seq + 1 else seq + 2
        map.putInt(0, writing)
        map.putInt(4, 1)
        map.putDouble(8, scale)
        map.putLong(16, baseReal)
        map.putLong(24, baseVirt)
        map.putInt(0, writing + 1)
    }
}
