package app.gamenative.itch

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray

private const val ITCH_HOME = "https://itch.io/games/platform-windows"

/** Reads the game's name and cover from the itch.io page that is open. */
private const val PAGE_INFO_JS = """
(function() {
  function meta(p) { var m = document.querySelector('meta[property="' + p + '"]'); return m ? m.content : ''; }
  return JSON.stringify([meta('og:title') || document.title, meta('og:image')]);
})()
"""

/**
 * PocketRig: a built-in itch.io browser. Browse, sign in and buy as normal; tapping a game's
 * Download button installs it into PocketRig instead of saving the file.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItchBrowserDialog(
    onDismissRequest: () -> Unit,
    onInstalled: (folder: File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("itch.io") }
    var pageLoading by remember { mutableStateOf(true) }

    // The last real game page we saw, in case the download page has no cover of its own.
    var lastGameTitle by remember { mutableStateOf<String?>(null) }
    var lastGameCover by remember { mutableStateOf<String?>(null) }

    var pending by remember { mutableStateOf<ItchInstaller.Download?>(null) }
    var progress by remember { mutableStateOf<ItchInstaller.Progress?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var installing by remember { mutableStateOf(false) }

    fun close() {
        if (installing) return
        onDismissRequest()
    }

    Dialog(
        onDismissRequest = {
            val view = webView
            if (view != null && view.canGoBack()) view.goBack() else close()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            IconButton(onClick = ::close) { Icon(Icons.Default.Close, contentDescription = "Close") }
                        },
                        actions = {
                            IconButton(
                                onClick = { webView?.takeIf { it.canGoBack() }?.goBack() },
                            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                            IconButton(onClick = { webView?.loadUrl(ITCH_HOME) }) {
                                Icon(Icons.Default.Home, contentDescription = "Windows games")
                            }
                        },
                    )
                    if (pageLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
        ) { padding ->
            AndroidView(
                modifier = Modifier.padding(padding).fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                pageLoading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                pageLoading = false
                                // A game page looks like https://someone.itch.io/game-name
                                val u = url.orEmpty()
                                val isGamePage = Regex("^https://[^/]+\\.itch\\.io/[^/?#]+/?([?#].*)?$").matches(u)
                                if (isGamePage) {
                                    view.evaluateJavascript(PAGE_INFO_JS) { raw ->
                                        parsePageInfo(raw)?.let { (title, cover) ->
                                            lastGameTitle = ItchInstaller.cleanTitle(title)
                                            lastGameCover = cover.ifBlank { null }
                                        }
                                    }
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                title?.let { pageTitle = it }
                            }
                        }

                        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            if (installing) return@setDownloadListener
                            evaluateJavascript(PAGE_INFO_JS) { raw ->
                                val info = parsePageInfo(raw)
                                val title = lastGameTitle ?: ItchInstaller.cleanTitle(info?.first)
                                val cover = info?.second?.ifBlank { null } ?: lastGameCover
                                pending = ItchInstaller.Download(
                                    url = url,
                                    userAgent = userAgent,
                                    contentDisposition = contentDisposition,
                                    mimeType = mimeType,
                                    gameTitle = title,
                                    coverUrl = cover,
                                )
                            }
                        }
                        loadUrl(ITCH_HOME)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }

        // Confirm before installing.
        pending?.let { dl ->
            var name by remember(dl) { mutableStateOf(dl.gameTitle) }
            val platform = ItchInstaller.platformOf(dl.fileName)
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("Install in PocketRig?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(dl.fileName, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Game name") },
                            singleLine = true,
                        )
                        when (platform) {
                            ItchInstaller.Platform.ANDROID -> Text(
                                "This is the Android version. PocketRig runs Windows games, so pick the " +
                                    "Windows download instead, or install this one from a normal browser.",
                                color = MaterialTheme.colorScheme.error,
                            )
                            ItchInstaller.Platform.MAC, ItchInstaller.Platform.LINUX -> Text(
                                "This looks like the ${platform.name.lowercase().replaceFirstChar { it.uppercase() }} " +
                                    "version. PocketRig runs Windows games, so the Windows download is the one to pick.",
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Text(
                                "It will be unpacked into your library. If it contains more than one .exe, " +
                                    "you'll be asked which to launch the first time you play.",
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = platform != ItchInstaller.Platform.ANDROID && name.isNotBlank(),
                        onClick = {
                            pending = null
                            progress = null
                            installing = true
                            installJob = scope.launch {
                                try {
                                    val result = ItchInstaller.install(context, dl, name) { progress = it }
                                    result.onSuccess { folder ->
                                        SnackbarManager.show("$name was added to your library")
                                        onInstalled(folder)
                                    }.onFailure {
                                        SnackbarManager.show("Couldn't install $name: ${it.message ?: "unknown error"}")
                                    }
                                } finally {
                                    installing = false
                                    progress = null
                                    installJob = null
                                }
                            }
                        },
                    ) { Text("Install") }
                },
                dismissButton = {
                    TextButton(onClick = { pending = null }) { Text("Cancel") }
                },
            )
        }

        // Install progress.
        if (installing) {
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Installing…") },
                text = {
                    Column {
                        when (val p = progress) {
                            is ItchInstaller.Progress.Downloading -> {
                                val mb = p.bytes / 1_000_000
                                if (p.total > 0) {
                                    LinearProgressIndicator(
                                        progress = { (p.bytes.toFloat() / p.total).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text("Downloading $mb of ${p.total / 1_000_000} MB")
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(8.dp))
                                    Text("Downloading $mb MB")
                                }
                            }
                            is ItchInstaller.Progress.Unpacking -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(Modifier.size(12.dp))
                                Text("Unpacking (${p.files} files)")
                            }
                            ItchInstaller.Progress.Finishing, null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(Modifier.size(12.dp))
                                Text(if (p == null) "Starting…" else "Finishing up…")
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { installJob?.cancel() }) { Text("Cancel") }
                },
            )
        }
    }
}

/** evaluateJavascript returns a JSON-encoded string holding our JSON array. */
private fun parsePageInfo(raw: String?): Pair<String, String>? = runCatching {
    val decoded = JSONArray("[$raw]").getString(0)
    val arr = JSONArray(decoded)
    arr.optString(0, "") to arr.optString(1, "")
}.getOrNull()
