package app.gamenative.itch

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.util.SnackbarManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray

const val ITCH_WINDOWS_GAMES = "https://itch.io/games/platform-windows"
private const val ITCH_WEB_GAMES = "https://itch.io/games/platform-web"

private data class Site(val name: String, val note: String, val url: String)

private val SITES = listOf(
    Site("itch.io", "Windows games", ITCH_WINDOWS_GAMES),
    Site("itch.io", "Web games to play here", ITCH_WEB_GAMES),
    Site("Game Jolt", "Indie games", "https://gamejolt.com/games"),
    Site("GOG.com", "Your offline installers", "https://www.gog.com/en/account"),
    Site("IndieDB", "Indie games and demos", "https://www.indiedb.com/games"),
)

/** Reads the open page's name and cover image. */
private const val PAGE_INFO_JS = """
(function() {
  function meta(p) { var m = document.querySelector('meta[property="' + p + '"]'); return m ? m.content : ''; }
  return JSON.stringify([meta('og:title') || document.title, meta('og:image')]);
})()
"""

/** On an itch.io web-game page, finds the address of the game itself so it can fill the screen. */
private const val ITCH_FRAME_JS = """
(function() {
  var p = document.querySelector('.iframe_placeholder');
  if (p && p.getAttribute('data-iframe')) {
    var d = document.createElement('div'); d.innerHTML = p.getAttribute('data-iframe');
    var f = d.querySelector('iframe'); if (f && f.src) return f.src;
  }
  var g = document.querySelector('#game_drop, iframe[src*="itch.zone"]');
  return g && g.src ? g.src : '';
})()
"""

private enum class Pane { WEB, START, OWNED }

/**
 * PocketRig: a built-in browser for finding games.
 *
 * - Downloads from any site are installed into the library (unpacked, cover saved).
 * - "itch.io: my games" lists everything you own on itch.io, with one-tap installs.
 * - Game mode fills the screen and adds on-screen controls, and maps a controller to the
 *   keyboard keys web games expect.
 */
@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GameBrowserDialog(
    startUrl: String?,
    openOwned: Boolean = false,
    onDismissRequest: () -> Unit,
    onInstalled: (folder: File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var webView by remember { mutableStateOf<GameWebView?>(null) }
    var pane by remember { mutableStateOf(if (openOwned) Pane.OWNED else if (startUrl == null) Pane.START else Pane.WEB) }
    var currentUrl by remember { mutableStateOf(startUrl.orEmpty()) }
    var address by remember { mutableStateOf(startUrl.orEmpty()) }
    var pageTitle by remember { mutableStateOf("") }
    var pageLoading by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    var gameMode by remember { mutableStateOf(false) }
    var showPad by remember { mutableStateOf(true) }
    var preset by remember { mutableStateOf(ControlPreset.ARROWS_ZX) }

    var lastGameTitle by remember { mutableStateOf<String?>(null) }
    var lastGameCover by remember { mutableStateOf<String?>(null) }

    var pending by remember { mutableStateOf<ItchInstaller.Download?>(null) }
    var progress by remember { mutableStateOf<ItchInstaller.Progress?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var installing by remember { mutableStateOf(false) }

    fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains('.') && !text.contains(' ') -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8")
        }
        pane = Pane.WEB
        webView?.loadUrl(url)
    }

    fun setGameMode(on: Boolean) {
        gameMode = on
        webView?.let { v ->
            v.mapController = on
            v.preset = preset
            if (on) {
                v.isFocusableInTouchMode = true
                v.requestFocus()
                // On itch.io, open the game itself so it fills the screen instead of the page around it.
                if (currentUrl.contains("itch.io")) {
                    v.evaluateJavascript(ITCH_FRAME_JS) { raw ->
                        val src = runCatching { JSONArray("[$raw]").getString(0) }.getOrNull()
                        if (!src.isNullOrBlank()) v.loadUrl(src)
                    }
                }
            }
        }
    }

    fun close() {
        if (!installing) onDismissRequest()
    }

    fun startInstall(dl: ItchInstaller.Download, name: String) {
        installing = true
        progress = null
        installJob = scope.launch {
            try {
                ItchInstaller.install(context, dl, name) { progress = it }
                    .onSuccess { folder ->
                        SnackbarManager.show("$name was added to your library")
                        onInstalled(folder)
                    }
                    .onFailure { SnackbarManager.show("Couldn't install $name: ${it.message ?: "unknown error"}") }
            } finally {
                installing = false
                progress = null
                installJob = null
            }
        }
    }

    Dialog(
        onDismissRequest = {
            val v = webView
            when {
                gameMode -> setGameMode(false)
                pane != Pane.WEB && currentUrl.isNotEmpty() -> pane = Pane.WEB
                pane == Pane.WEB && v != null && v.canGoBack() -> v.goBack()
                else -> close()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (!gameMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = ::close) { Icon(Icons.Default.Close, contentDescription = "Close") }
                        IconButton(
                            onClick = {
                                val v = webView
                                if (pane != Pane.WEB && currentUrl.isNotEmpty()) {
                                    pane = Pane.WEB
                                } else if (v != null && v.canGoBack()) {
                                    v.goBack()
                                }
                            },
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Search or type a web address") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigate(address) }),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(onClick = { pane = Pane.START }) { Icon(Icons.Default.Home, contentDescription = "Start page") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("itch.io: my games") },
                                    onClick = { menuOpen = false; pane = Pane.OWNED },
                                )
                                DropdownMenuItem(
                                    text = { Text("Play this page as a game") },
                                    enabled = currentUrl.isNotEmpty(),
                                    onClick = { menuOpen = false; pane = Pane.WEB; setGameMode(true) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Save to my web games") },
                                    enabled = currentUrl.isNotEmpty(),
                                    onClick = {
                                        menuOpen = false
                                        val title = lastGameTitle ?: ItchInstaller.cleanTitle(pageTitle)
                                        SavedBrowserGames.save(
                                            context,
                                            SavedBrowserGames.Game(title, currentUrl, lastGameCover, preset),
                                        )
                                        SnackbarManager.show("Saved $title to your web games")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Reload") },
                                    onClick = { menuOpen = false; webView?.reload() },
                                )
                            }
                        }
                    }
                    if (pageLoading && pane == Pane.WEB) LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            GameWebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        pageLoading = true
                                        url?.let { currentUrl = it; address = it }
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        pageLoading = false
                                        url?.let { currentUrl = it; address = it }
                                        view.evaluateJavascript(PAGE_INFO_JS) { raw ->
                                            parsePageInfo(raw)?.let { (title, cover) ->
                                                // Remember real game pages (not search or download pages) for names and covers.
                                                val u = url.orEmpty()
                                                val looksLikeGamePage =
                                                    Regex("^https://[^/]+\\.itch\\.io/[^/?#]+/?([?#].*)?$").matches(u) ||
                                                        (!u.contains("itch.io") && cover.isNotBlank())
                                                if (looksLikeGamePage) {
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
                                        pending = ItchInstaller.Download(
                                            url = url,
                                            userAgent = userAgent,
                                            contentDisposition = contentDisposition,
                                            mimeType = mimeType,
                                            gameTitle = lastGameTitle ?: ItchInstaller.cleanTitle(info?.first),
                                            coverUrl = info?.second?.ifBlank { null } ?: lastGameCover,
                                        )
                                    }
                                }
                                if (!startUrl.isNullOrBlank()) loadUrl(startUrl)
                                webView = this
                            }
                        },
                        onRelease = { it.destroy() },
                    )

                    when (pane) {
                        Pane.START -> StartPane(
                            onOpen = { navigate(it) },
                            onPlaySaved = { game ->
                                preset = game.preset
                                pane = Pane.WEB
                                webView?.loadUrl(game.url)
                                setGameMode(true)
                            },
                        )
                        Pane.OWNED -> OwnedPane(
                            onOpenUrl = { navigate(it) },
                            onInstall = { pending = it },
                            onPlayWeb = { url ->
                                navigate(url)
                                // Give the page a moment to load before pulling the game out of it.
                                scope.launch {
                                    kotlinx.coroutines.delay(2500)
                                    setGameMode(true)
                                }
                            },
                        )
                        Pane.WEB -> Unit
                    }

                    if (gameMode) {
                        if (showPad) {
                            OnScreenGamePad(onButton = { b, down -> webView?.press(b, down) })
                        }
                        // Small controls along the top so the game keeps the screen.
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 6.dp)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { setGameMode(false) }) { Text("Exit", color = Color.White) }
                            TextButton(onClick = { showPad = !showPad }) {
                                Text(if (showPad) "Hide pad" else "Show pad", color = Color.White)
                            }
                            TextButton(
                                onClick = {
                                    val next = ControlPreset.entries[(preset.ordinal + 1) % ControlPreset.entries.size]
                                    preset = next
                                    webView?.preset = next
                                    SavedBrowserGames.updatePreset(context, currentUrl, next)
                                    SnackbarManager.show("Controls: ${next.label}")
                                },
                            ) { Text(preset.label, color = Color.White) }
                        }
                    }
                }
            }
        }

        // Confirm before installing a download.
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
                                "This is an Android app. PocketRig runs Windows games, so pick the Windows " +
                                    "download instead, or install this one from a normal browser.",
                                color = MaterialTheme.colorScheme.error,
                            )
                            ItchInstaller.Platform.MAC, ItchInstaller.Platform.LINUX -> Text(
                                "This looks like the ${platform.name.lowercase().replaceFirstChar { it.uppercase() }} " +
                                    "version. PocketRig runs Windows games, so the Windows download is the one to pick.",
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Text(
                                if (dl.fileName.startsWith("setup", ignoreCase = true)) {
                                    "This looks like an installer. It will be added to your library; play it once " +
                                        "to run the installer, then choose the game's own .exe in its settings."
                                } else {
                                    "It will be unpacked into your library. If it has more than one .exe, you'll be " +
                                        "asked which to launch the first time you play."
                                },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = platform != ItchInstaller.Platform.ANDROID && name.isNotBlank(),
                        onClick = {
                            pending = null
                            startInstall(dl, name)
                        },
                    ) { Text("Install") }
                },
                dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
            )
        }

        if (installing) {
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = { Text("Installing…") },
                text = { InstallProgress(progress) },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { installJob?.cancel() }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun InstallProgress(p: ItchInstaller.Progress?) {
    Column {
        when (p) {
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
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Downloading $mb MB")
                }
            }
            is ItchInstaller.Progress.Unpacking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.size(12.dp))
                Text("Unpacking (${p.files} files)")
            }
            ItchInstaller.Progress.Finishing, null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.size(12.dp))
                Text(if (p == null) "Starting…" else "Finishing up…")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartPane(
    onOpen: (String) -> Unit,
    onPlaySaved: (SavedBrowserGames.Game) -> Unit,
) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(SavedBrowserGames.load(context)) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Find games", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Downloads from any site are installed straight into your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SITES.forEach { site ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(site.url) },
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(site.name, fontWeight = FontWeight.SemiBold)
                            Text(site.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("My web games", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (saved.isEmpty()) {
                Text(
                    "Open a browser game, then choose \"Save to my web games\" from the menu to keep it here. " +
                        "It opens full screen with controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            saved.forEach { game ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPlaySaved(game) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(game.coverUrl, Modifier.size(56.dp))
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(game.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Controls: ${game.preset.label}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        SavedBrowserGames.remove(context, game.url)
                        saved = SavedBrowserGames.load(context)
                    }) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                }
            }
        }
    }
}

private enum class OwnedFilter(val label: String) { ALL("All"), WINDOWS("Windows"), WEB("Web") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnedPane(
    onOpenUrl: (String) -> Unit,
    onInstall: (ItchInstaller.Download) -> Unit,
    onPlayWeb: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(ItchApi.savedKey(context)) }
    var keyInput by remember { mutableStateOf("") }
    var games by remember { mutableStateOf<List<ItchApi.OwnedGame>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(OwnedFilter.WINDOWS) }
    var choosing by remember { mutableStateOf<Pair<ItchApi.OwnedGame, List<ItchApi.Upload>>?>(null) }
    var busyGame by remember { mutableStateOf<Long?>(null) }

    fun load(k: String) {
        loading = true
        error = null
        scope.launch {
            try {
                games = ItchApi.ownedGames(k)
            } catch (e: Exception) {
                error = e.message
                games = null
            } finally {
                loading = false
            }
        }
    }

    fun install(game: ItchApi.OwnedGame, upload: ItchApi.Upload) {
        val k = key ?: return
        onInstall(
            ItchInstaller.Download(
                url = ItchApi.downloadUrl(k, game, upload),
                userAgent = null,
                contentDisposition = null,
                mimeType = null,
                gameTitle = game.title,
                coverUrl = game.coverUrl,
                fileNameOverride = upload.fileName,
            ),
        )
    }

    LaunchedEffect(key) { key?.let { load(it) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (key == null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Your itch.io games", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "To list everything you've bought or claimed, PocketRig needs an itch.io API key. " +
                        "It stays on this phone.\n\n" +
                        "1. Tap the button below and sign in to itch.io if asked.\n" +
                        "2. Tap \"Generate new API key\", then copy the key.\n" +
                        "3. Come back here (Home, then the menu, then \"itch.io: my games\") and paste it.",
                )
                FilledTonalButton(onClick = { onOpenUrl(ItchApi.API_KEYS_PAGE) }) { Text("Open itch.io API keys page") }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    label = { Text("API key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    enabled = keyInput.isNotBlank(),
                    onClick = {
                        ItchApi.saveKey(context, keyInput)
                        key = keyInput.trim()
                    },
                ) { Text("Save key") }
            }
            return@Surface
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Your itch.io games" + (games?.let { " (${it.size})" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { key?.let { load(it) } }) { Text("Refresh") }
                TextButton(onClick = {
                    ItchApi.saveKey(context, null)
                    key = null
                    games = null
                }) { Text("Change key") }
            }
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OwnedFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                else -> {
                    val shown = games.orEmpty().filter {
                        when (filter) {
                            OwnedFilter.ALL -> true
                            OwnedFilter.WINDOWS -> "windows" in it.platforms
                            OwnedFilter.WEB -> it.isWebGame
                        }
                    }
                    if (shown.isEmpty()) {
                        Text("Nothing here yet.", modifier = Modifier.padding(16.dp))
                    }
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(shown, key = { it.gameId }) { game ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(game.coverUrl, Modifier.size(width = 84.dp, height = 66.dp))
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(game.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        (game.platforms.map { it.replaceFirstChar { c -> c.uppercase() } } +
                                            listOfNotNull("Web".takeIf { game.isWebGame }))
                                            .joinToString(" · ").ifBlank { game.classification },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                when {
                                    busyGame == game.gameId -> CircularProgressIndicator(Modifier.size(24.dp))
                                    "windows" in game.platforms -> FilledTonalButton(onClick = {
                                        val k = key ?: return@FilledTonalButton
                                        busyGame = game.gameId
                                        scope.launch {
                                            try {
                                                val best = ItchApi.bestWindowsUploads(ItchApi.uploads(k, game))
                                                when {
                                                    best.isEmpty() -> SnackbarManager.show("No Windows download for ${game.title}")
                                                    best.size == 1 -> install(game, best.first())
                                                    else -> choosing = game to best
                                                }
                                            } catch (e: Exception) {
                                                SnackbarManager.show(e.message ?: "Couldn't load downloads")
                                            } finally {
                                                busyGame = null
                                            }
                                        }
                                    }) { Text("Install") }
                                    game.isWebGame && game.pageUrl != null -> FilledTonalButton(
                                        onClick = { onPlayWeb(game.pageUrl) },
                                    ) { Text("Play") }
                                    game.pageUrl != null -> TextButton(onClick = { onOpenUrl(game.pageUrl) }) { Text("Open") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    choosing?.let { (game, uploads) ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text("Which download?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uploads.forEach { u ->
                        TextButton(
                            onClick = {
                                choosing = null
                                install(game, u)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(u.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (u.size > 0) Text("${u.size / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Cover(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)) {
        if (!url.isNullOrBlank()) {
            CoilImage(
                imageModel = { url },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                modifier = Modifier.fillMaxSize(),
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
