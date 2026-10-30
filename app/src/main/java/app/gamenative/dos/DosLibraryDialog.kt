package app.gamenative.dos

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PocketRig: where DOS games live, and which files count as one. */
object DosGames {
    /** Files DOSBox Pure can open directly. */
    private val EXTENSIONS = setOf("zip", "dosz", "exe", "com", "bat", "iso", "cue", "img", "ima", "vhd", "conf")

    fun folder(context: Context): File = File(context.filesDir, "dos/games").apply { mkdirs() }

    fun list(context: Context): List<File> =
        folder(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in EXTENSIONS }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            .orEmpty()

    /** Save states live in one folder per game. */
    fun statesFolder(context: Context): File = File(context.filesDir, "dos/states").apply { mkdirs() }

    fun displayName(file: File): String =
        file.nameWithoutExtension.replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    fun isSupported(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in EXTENSIONS

    /** Copies a picked file into the DOS games folder. Returns the new file. */
    suspend fun import(context: Context, uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: "game.zip"
            if (!isSupported(name)) error("PocketRig's DOS player opens .zip, .exe, .com, .iso and .cue files")
            var target = File(folder(context), name)
            var i = 2
            while (target.exists()) target = File(folder(context), "${name.substringBeforeLast('.')} ($i).${name.substringAfterLast('.')}").also { i++ }
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("Couldn't read the file")
            target
        }
    }
}

/** PocketRig: the DOS games list as a full-screen dialog. */
@Composable
fun DosLibraryDialog(onDismissRequest: () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            DosLibraryContent(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                onClose = onDismissRequest,
            )
        }
    }
}

/**
 * PocketRig: the DOS games list (used by the library's DOS tab). Add a game (usually a .zip),
 * then tap it to play.
 */
@Composable
fun DosLibraryContent(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onCountChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf(DosGames.list(context)) }
    var importing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    val coreReady = remember { DosPlayerActivity.isCoreAvailable(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            var added = 0
            for (uri in uris) {
                DosGames.import(context, uri)
                    .onSuccess { added++ }
                    .onFailure { SnackbarManager.show(it.message ?: "Couldn't add that file") }
            }
            importing = false
            games = DosGames.list(context)
            onCountChanged(games.size)
            if (added > 0) SnackbarManager.show(if (added == 1) "Added 1 DOS game" else "Added $added DOS games")
        }
    }

    Box(modifier) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "DOS games",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (onClose != null) {
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                }
                Text(
                    "Add a DOS game as a .zip (or .exe, .iso, .cue). The first time you play, pick which " +
                        "program to run and DOSBox Pure remembers it. Tap Keyboard in game for the on-screen keyboard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                if (!coreReady) {
                    Text(
                        "The DOS player is missing from this build. Install the latest PocketRig build.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(12.dp))
                }
                FilledTonalButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !importing,
                ) { Text(if (importing) "Adding…" else "Add DOS game") }
                Spacer(Modifier.size(12.dp))

                if (importing) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                if (games.isEmpty() && !importing) {
                    Text(
                        "No DOS games yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(games, key = { it.absolutePath }) { game ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = coreReady) { DosPlayerActivity.start(context, game) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    DosGames.displayName(game),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${game.extension.uppercase(Locale.ROOT)} · ${game.length() / 1_000_000} MB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { confirmDelete = game }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }

        confirmDelete?.let { game ->
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text("Remove ${DosGames.displayName(game)}?") },
                text = { Text("The game file is deleted from PocketRig. Your original copy elsewhere isn't touched.") },
                confirmButton = {
                    TextButton(onClick = {
                        game.delete()
                        File(DosGames.statesFolder(context), game.name).deleteRecursively()
                        confirmDelete = null
                        games = DosGames.list(context)
                        onCountChanged(games.size)
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            )
        }
    }
}
