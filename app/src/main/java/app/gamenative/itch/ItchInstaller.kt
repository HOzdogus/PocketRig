package app.gamenative.itch

import android.content.Context
import android.webkit.CookieManager
import android.webkit.URLUtil
import app.gamenative.service.DownloadService
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import timber.log.Timber

/**
 * PocketRig: installs games downloaded from itch.io as custom games.
 *
 * The file is downloaded, unpacked into PocketRig's own CustomGames folder (which the library
 * scans automatically), and the game's itch.io cover is saved next to it as cover.<ext>.
 */
object ItchInstaller {
    private const val TAG = "ItchInstaller"

    /** What the itch.io page handed to the browser when you tapped Download. */
    data class Download(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val gameTitle: String,
        val coverUrl: String?,
    ) {
        val fileName: String
            get() = URLUtil.guessFileName(url, contentDisposition, mimeType)
    }

    enum class Platform { WINDOWS, ANDROID, MAC, LINUX, UNKNOWN }

    sealed interface Progress {
        data class Downloading(val bytes: Long, val total: Long) : Progress
        data class Unpacking(val files: Int) : Progress
        data object Finishing : Progress
    }

    /** Guesses which platform a download is for from its file name. */
    fun platformOf(fileName: String): Platform {
        val n = fileName.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".apk") || n.endsWith(".aab") -> Platform.ANDROID
            n.endsWith(".dmg") || n.endsWith(".pkg") -> Platform.MAC
            n.endsWith(".appimage") || n.endsWith(".deb") || n.endsWith(".rpm") || n.endsWith(".x86_64") -> Platform.LINUX
            n.endsWith(".exe") || n.endsWith(".msi") -> Platform.WINDOWS
            Regex("(^|[^a-z])(win|windows|win32|win64|pc)([^a-z]|$)").containsMatchIn(n) -> Platform.WINDOWS
            Regex("(^|[^a-z])(mac|macos|osx)([^a-z]|$)").containsMatchIn(n) -> Platform.MAC
            Regex("(^|[^a-z])(linux|lin64|lin32)([^a-z]|$)").containsMatchIn(n) -> Platform.LINUX
            else -> Platform.UNKNOWN
        }
    }

    /** Folder where itch games are unpacked; the library scans every subfolder of it. */
    private fun gamesRoot(): File = File(DownloadService.baseDataDirPath, "CustomGames").apply { mkdirs() }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }

    /** Downloads and unpacks the game. Returns the new game folder. */
    suspend fun install(
        context: Context,
        download: Download,
        gameName: String,
        onProgress: (Progress) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val job = coroutineContext
        val cancelled = { !job.isActive }
        val work = File(context.cacheDir, "itch").apply { mkdirs() }
        val archive = File(work, download.fileName.ifBlank { "download.bin" })
        var target: File? = null
        try {
            fetch(download.url, download.userAgent, archive, cancelled) { done, total ->
                onProgress(Progress.Downloading(done, total))
            }
            ensureActive()

            target = uniqueFolder(gamesRoot(), sanitize(gameName))
            target.mkdirs()
            val name = archive.name.lowercase(Locale.ROOT)
            var count = 0
            val tick = {
                if (cancelled()) throw CancellationException("Cancelled")
                count++
                if (count % 50 == 0) onProgress(Progress.Unpacking(count))
            }
            when {
                name.endsWith(".zip") -> unzip(archive, target, tick)
                name.endsWith(".7z") -> un7z(archive, target, tick)
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> untarGz(archive, target, tick)
                name.endsWith(".rar") -> throw IOException("RAR files aren't supported yet. Look for a .zip download instead.")
                // A bare .exe (a single-file game or an installer) is used as-is.
                else -> archive.copyTo(File(target, archive.name), overwrite = true)
            }
            ensureActive()

            onProgress(Progress.Finishing)
            flattenSingleFolder(target)
            if (!containsExe(target)) {
                throw IOException("No Windows .exe was found in this download. It may be for another platform.")
            }
            download.coverUrl?.let { saveCover(it, download.userAgent, target) }
            Timber.tag(TAG).i("Installed ${download.fileName} to $target")
            Result.success(target)
        } catch (e: CancellationException) {
            target?.deleteRecursively()
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "itch install failed")
            target?.deleteRecursively()
            Result.failure(e)
        } finally {
            archive.delete()
        }
    }

    // ---------------- download ----------------

    private fun fetch(
        url: String,
        userAgent: String?,
        dest: File,
        cancelled: () -> Boolean,
        onBytes: (Long, Long) -> Unit,
    ) {
        val builder = Request.Builder().url(url)
        userAgent?.let { builder.header("User-Agent", it) }
        CookieManager.getInstance().getCookie(url)?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw CancellationException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 1_000_000L) {
                            lastReport = done
                            onBytes(done, total)
                        }
                    }
                    onBytes(done, total)
                }
            }
        }
    }

    private fun saveCover(url: String, userAgent: String?, folder: File) {
        runCatching {
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
                .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"
            val builder = Request.Builder().url(url)
            userAgent?.let { builder.header("User-Agent", it) }
            client.newCall(builder.build()).execute().use { r ->
                if (r.isSuccessful) {
                    File(folder, "cover.$ext").outputStream().use { r.body.byteStream().copyTo(it) }
                }
            }
        }.onFailure { Timber.tag(TAG).w(it, "Couldn't save cover") }
    }

    // ---------------- unpacking ----------------

    /** Stops archive entries from writing outside the game folder ("zip slip"). */
    private fun safeChild(root: File, entryName: String): File {
        val out = File(root, entryName.replace('\\', '/'))
        val rootPath = root.canonicalPath + File.separator
        if (!out.canonicalPath.startsWith(rootPath) && out.canonicalPath != root.canonicalPath) {
            throw IOException("Unsafe path in archive: $entryName")
        }
        return out
    }

    private fun unzip(archive: File, dest: File, onFile: () -> Unit) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val out = safeChild(dest, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                onFile()
            }
        }
    }

    private fun un7z(archive: File, dest: File, onFile: () -> Unit) {
        SevenZFile.builder().setFile(archive).get().use { sz ->
            while (true) {
                val e = sz.nextEntry ?: break
                val out = safeChild(dest, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = sz.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                }
                onFile()
            }
        }
    }

    private fun untarGz(archive: File, dest: File, onFile: () -> Unit) {
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val out = safeChild(dest, e.name)
                if (e.isDirectory) {
                    out.mkdirs()
                    continue
                }
                if (!e.isFile) continue // skip links and devices
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
                onFile()
            }
        }
    }

    /** If everything unpacked into one top-level folder, move its contents up a level. */
    private fun flattenSingleFolder(root: File) {
        repeat(3) {
            val children = root.listFiles()?.filter { !it.name.startsWith("__MACOSX") } ?: return
            val only = children.singleOrNull() ?: return
            if (!only.isDirectory) return
            val tmp = File(root, ".pocketrig-flatten")
            if (!only.renameTo(tmp)) return
            tmp.listFiles()?.forEach { it.renameTo(File(root, it.name)) }
            tmp.deleteRecursively()
        }
    }

    private fun containsExe(root: File): Boolean =
        root.walkTopDown().maxDepth(4).any { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }

    // ---------------- names ----------------

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim()
            .take(80).ifBlank { "itch game" }

    private fun uniqueFolder(root: File, name: String): File {
        var candidate = File(root, name)
        var i = 2
        while (candidate.exists()) candidate = File(root, "$name ($i)").also { i++ }
        return candidate
    }

    /** Cleans an itch.io page title like "Download My Game by Someone" down to "My Game". */
    fun cleanTitle(raw: String?): String {
        var t = raw.orEmpty().trim()
        t = t.removePrefix("Download ").removePrefix("Purchase ")
        t = t.substringBefore(" by ").substringBefore(" - itch.io").substringBefore(" | itch.io")
        return t.trim().ifBlank { "itch game" }
    }
}
