package app.gamenative.utils

import android.content.Context
import app.gamenative.PluviaApp
import com.winlator.container.Container
import com.winlator.container.ContainerFiles
import com.winlator.container.ContainerManager
import com.winlator.container.ContainerOverlayMigrator
import com.winlator.contents.ContentsManager
import com.winlator.core.WineInfo
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.FileInputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * PocketRig: keeps game containers small.
 *
 * - Tells you whether a container sits on the shared Wine base (thin) or is a full copy, and why.
 * - Converts eligible full copies to thin ones without waiting for the next launch.
 * - Clears shader caches and temp files.
 * - Stores identical runtime files (Visual C++, DirectX, .NET, …) once across all containers using
 *   hard links. Each game still sees its own file at the same path.
 */
object ContainerSlimmer {
    private const val TAG = "ContainerSlimmer"

    enum class PrefixKind {
        /** Sits on the shared Wine base; only the game's own changes are stored. */
        SHARED,

        /** A full copy that can be moved onto the shared base (Bionic + downloadable Wine/Proton). */
        FULL_CONVERTIBLE,

        /** A full copy because the container uses the glibc variant. */
        FULL_GLIBC,

        /** A full copy because the container uses the built-in Wine. */
        FULL_BUILTIN_WINE,

        UNKNOWN,
    }

    data class CleanResult(val bytesFreed: Long, val filesDeleted: Int)

    data class ShareResult(val bytesSaved: Long, val filesLinked: Int, val groups: Int)

    /** True while a game session is running, when containers must not be changed. */
    fun isGameRunning(): Boolean = PluviaApp.xEnvironment != null

    fun prefixKind(config: JSONObject?): PrefixKind {
        if (config == null) return PrefixKind.UNKNOWN
        val variant = config.optString("containerVariant", Container.DEFAULT_VARIANT)
        val wineVersion = config.optString("wineVersion", "").ifBlank { null }
        val basePrefix = config.optString("basePrefix", "")
        return when {
            !Container.BIONIC.equals(variant, ignoreCase = true) -> PrefixKind.FULL_GLIBC
            WineInfo.isMainWineVersion(wineVersion) -> PrefixKind.FULL_BUILTIN_WINE
            basePrefix.isNotBlank() -> PrefixKind.SHARED
            else -> PrefixKind.FULL_CONVERTIBLE
        }
    }

    // ---------------- Clean-up ----------------

    /** Names inside a container's .cache folder that are safe to delete (rebuilt automatically). */
    private val CACHE_NAMES = listOf(
        Regex("^mesa_shader_cache.*", RegexOption.IGNORE_CASE),
        Regex("^dxvk.*", RegexOption.IGNORE_CASE),
        Regex(".*\\.dxvk-cache$", RegexOption.IGNORE_CASE),
        Regex("^vkd3d.*", RegexOption.IGNORE_CASE),
        Regex("^glcache$", RegexOption.IGNORE_CASE),
        Regex("^nvidia$", RegexOption.IGNORE_CASE),
        Regex("^fontconfig$", RegexOption.IGNORE_CASE),
    )

    /** Folders under .wine whose contents are temporary. */
    private val TEMP_DIRS_UNDER_WINE = listOf("drive_c/windows/temp")
    private val TEMP_DIRS_UNDER_USER = listOf("Temp", "AppData/Local/Temp", "AppData/Local/CrashDumps")

    private fun cleanTargets(containerDir: File): List<File> {
        val targets = mutableListOf<File>()
        File(containerDir, ".cache").listFiles()?.forEach { f ->
            // original_dlls is needed to switch DXVK/graphics wrappers back, so never touch it.
            if (f.name.equals("original_dlls", ignoreCase = true)) return@forEach
            if (CACHE_NAMES.any { it.matches(f.name) }) targets += f
        }
        val wine = File(containerDir, ".wine")
        // Skip any temp folder that is itself a symlink, so nothing outside the container is touched.
        for (rel in TEMP_DIRS_UNDER_WINE) {
            File(wine, rel).takeIf { !isLink(it) }?.listFiles()?.let { targets += it }
        }
        File(wine, "drive_c/users").listFiles()?.filter { it.isDirectory && !isLink(it) }?.forEach { user ->
            for (rel in TEMP_DIRS_UNDER_USER) {
                File(user, rel).takeIf { !isLink(it) }?.listFiles()?.let { targets += it }
            }
        }
        return targets
    }

    fun cleanableBytes(containerDir: File): Long =
        cleanTargets(containerDir).sumOf { sizeOf(it.toPath()) }

    suspend fun cleanUp(containerDir: File): CleanResult = withContext(Dispatchers.IO) {
        var bytes = 0L
        var files = 0
        for (target in cleanTargets(containerDir)) {
            val size = sizeOf(target.toPath())
            val count = countFiles(target.toPath())
            if (deleteTree(target.toPath())) {
                bytes += size
                files += count
            }
        }
        Timber.tag(TAG).i("Cleaned ${containerDir.name}: $files files, $bytes bytes")
        CleanResult(bytes, files)
    }

    // ---------------- Convert to the shared base ----------------

    /** Moves an eligible full container onto the shared Wine base now. Returns bytes freed. */
    suspend fun convertToShared(context: Context, containerId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = ContainerManager(context)
            val container = manager.getContainers().firstOrNull { it.id == containerId }
                ?: error("Container not found")
            if (container.isOverlay) return@runCatching 0L
            val before = ContainerStorageManager.getContainerDirectorySize(container.rootDir.toPath())
            val contents = ContentsManager(context)
            contents.syncContents()
            ContainerOverlayMigrator.migrateIfNeeded(context, contents, container)
            val reloaded = ContainerManager(context).getContainers().firstOrNull { it.id == containerId }
            if (reloaded?.isOverlay != true) error("This container couldn't be moved onto the shared base")
            val after = ContainerStorageManager.getContainerDirectorySize(container.rootDir.toPath())
            (before - after).coerceAtLeast(0L)
        }
    }

    // ---------------- Share identical runtime files ----------------

    /** Runtime DLLs that games install into system32/syswow64. */
    private val RUNTIME_DLL = Regex(
        "^(msvcp\\d.*|msvcr\\d.*|vcruntime\\d.*|vccorlib\\d.*|concrt\\d.*|vcomp\\d.*|vcamp\\d.*|" +
            "mfc\\d.*|mfcm\\d.*|atl\\d+|api-ms-win-crt-.*|ucrtbase|d3dx9_\\d+|d3dx10.*|d3dx11.*|" +
            "d3dcompiler_\\d+|xinput.*|xaudio2_\\d+|x3daudio.*|xapofx.*|xactengine.*|openal32|wrap_oal|" +
            "physx.*|cudart.*|nvcuda)\\.dll$",
        RegexOption.IGNORE_CASE,
    )

    /** Whole folders that hold runtimes (.NET and the GAC). Everything inside them is shared. */
    private val RUNTIME_TREES = listOf(
        "drive_c/windows/Microsoft.NET",
        "drive_c/windows/assembly",
    )
    private val SYSTEM_DIRS = listOf("drive_c/windows/system32", "drive_c/windows/syswow64")
    private const val MIN_SHARE_BYTES = 32 * 1024L

    private fun runtimeFiles(containerDir: File): List<Path> {
        val wine = File(containerDir, ".wine")
        val out = mutableListOf<Path>()
        for (rel in SYSTEM_DIRS) {
            File(wine, rel).listFiles()?.forEach { f ->
                if (!f.isFile || isLink(f)) return@forEach
                if (!RUNTIME_DLL.matches(f.name)) return@forEach
                if (ContainerFiles.isAppManaged("$rel/${f.name}")) return@forEach
                if (f.length() >= MIN_SHARE_BYTES) out += f.toPath()
            }
        }
        for (rel in RUNTIME_TREES) {
            val root = File(wine, rel).toPath()
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue
            runCatching {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName.toString().lowercase()
                        if (attrs.isRegularFile && attrs.size() >= MIN_SHARE_BYTES &&
                            !name.endsWith(".log") && !name.endsWith(".tmp")
                        ) {
                            out += file
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE
                })
            }
        }
        return out
    }

    /**
     * Finds runtime files that are byte-for-byte identical across containers and makes them share
     * one copy on disk. Safe to run repeatedly; files that already share are skipped.
     */
    suspend fun shareRuntimes(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): ShareResult = withContext(Dispatchers.IO) {
        val home = File(ImageFs.find(context).rootDir, "home")
        val containers = home.listFiles()
            ?.filter { it.isDirectory && !isLink(it) && it.name.startsWith("${ImageFs.USER}-") }
            .orEmpty()

        onProgress("Looking for runtime files…")
        // Group candidates by size first; only same-size files can be identical.
        val bySize = HashMap<Long, MutableList<Path>>()
        for (dir in containers) {
            for (file in runtimeFiles(dir)) {
                val size = runCatching { Files.size(file) }.getOrNull() ?: continue
                bySize.getOrPut(size) { mutableListOf() } += file
            }
        }

        var saved = 0L
        var linked = 0
        var groups = 0
        val candidates = bySize.filterValues { it.size > 1 }
        var done = 0
        for ((size, files) in candidates) {
            done++
            if (done % 25 == 0) onProgress("Comparing files ($done of ${candidates.size})…")

            // Files that already share an inode count as one.
            val byInode = files.groupBy { inodeKey(it) }
            if (byInode.size < 2) continue

            val byHash = HashMap<String, MutableList<List<Path>>>()
            for ((_, sameInode) in byInode) {
                val hash = sha256(sameInode.first()) ?: continue
                byHash.getOrPut(hash) { mutableListOf() } += sameInode
            }
            for ((_, inodeGroups) in byHash) {
                if (inodeGroups.size < 2) continue
                // Keep the inode that already has the most links; point everything else at it.
                val keeperGroup = inodeGroups.maxByOrNull { it.size }!!
                val keeper = keeperGroup.first()
                var any = false
                for (group in inodeGroups) {
                    if (group === keeperGroup) continue
                    for (dup in group) {
                        if (replaceWithLink(dup, keeper)) {
                            linked++
                            any = true
                        }
                    }
                    // The space only comes back once the last path to the old inode is gone.
                    saved += size
                }
                if (any) groups++
            }
        }
        Timber.tag(TAG).i("Shared runtimes: $linked files linked in $groups groups, ~$saved bytes saved")
        ShareResult(saved, linked, groups)
    }

    private fun replaceWithLink(target: Path, keeper: Path): Boolean {
        val tmp = target.resolveSibling(".${target.fileName}.pocketrig-link")
        return try {
            Files.deleteIfExists(tmp)
            Files.createLink(tmp, keeper)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w("Couldn't share $target: ${e.message}")
            runCatching { Files.deleteIfExists(tmp) }
            false
        }
    }

    // ---------------- helpers ----------------

    private fun inodeKey(path: Path): Any =
        runCatching { Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: path

    private fun sha256(path: Path): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(path.toFile()).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun isLink(f: File): Boolean = Files.isSymbolicLink(f.toPath())

    /** Size on disk, skipping symlinks and not counting shared (hard-linked) files. */
    private fun sizeOf(root: Path): Long {
        if (Files.isSymbolicLink(root)) return 0L
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return runCatching { if (linkCount(root) > 1) 0L else Files.size(root) }.getOrDefault(0L)
        }
        var total = 0L
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && linkCount(file) <= 1) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE
            })
        }
        return total
    }

    private fun countFiles(root: Path): Int {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 1
        var n = 0
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    n++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE
            })
        }
        return n
    }

    private fun linkCount(file: Path): Int =
        runCatching { (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() }
            .getOrDefault(1)

    /** Deletes a file or folder without following symlinks out of the container. */
    private fun deleteTree(root: Path): Boolean = runCatching {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(root)
            return@runCatching true
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                runCatching { Files.delete(file) }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                runCatching { Files.delete(dir) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE
        })
        true
    }.getOrDefault(false)
}
