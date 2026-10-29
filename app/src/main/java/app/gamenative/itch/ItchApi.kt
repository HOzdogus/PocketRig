package app.gamenative.itch

import android.content.Context
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * PocketRig: the parts of itch.io's API needed for the "Owned" list.
 * Uses an API key the user creates at https://itch.io/user/settings/api-keys.
 */
object ItchApi {
    const val API_KEYS_PAGE = "https://itch.io/user/settings/api-keys"
    private const val BASE = "https://api.itch.io"
    private const val PREFS = "pocketrig_itch"
    private const val KEY_API_KEY = "api_key"

    data class OwnedGame(
        val downloadKeyId: Long,
        val gameId: Long,
        val title: String,
        val coverUrl: String?,
        val pageUrl: String?,
        val classification: String,
        val platforms: Set<String>,
        val isWebGame: Boolean,
    )

    data class Upload(
        val id: Long,
        val fileName: String,
        val displayName: String,
        val size: Long,
        val type: String,
        val platforms: Set<String>,
    ) {
        val isWindows: Boolean get() = "windows" in platforms
        val isHtml: Boolean get() = type == "html"
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun savedKey(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveKey(context: Context, key: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, key?.trim()).apply()
    }

    private fun get(path: String, key: String): JSONObject {
        val req = Request.Builder()
            .url("$BASE$path")
            .header("Authorization", "Bearer $key")
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body.string()
            val json = runCatching { JSONObject(text) }.getOrNull()
            val error = json?.optJSONArray("errors")?.optString(0)
            if (!r.isSuccessful || error != null) {
                throw IOException(
                    when {
                        r.code == 401 || r.code == 403 || error?.contains("key", ignoreCase = true) == true ->
                            "itch.io didn't accept the API key. Check it and try again."
                        error != null -> "itch.io: $error"
                        else -> "itch.io request failed (HTTP ${r.code})"
                    },
                )
            }
            return json ?: throw IOException("Unexpected reply from itch.io")
        }
    }

    /** itch has used both a "traits" list (p_windows…) and true/false flags for platforms. */
    private fun platformsOf(o: JSONObject): Set<String> {
        val out = mutableSetOf<String>()
        o.optJSONArray("traits")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (arr.optString(i)) {
                    "p_windows" -> out += "windows"
                    "p_linux" -> out += "linux"
                    "p_osx" -> out += "mac"
                    "p_android" -> out += "android"
                }
            }
        }
        if (o.optBoolean("p_windows")) out += "windows"
        if (o.optBoolean("p_linux")) out += "linux"
        if (o.optBoolean("p_osx")) out += "mac"
        if (o.optBoolean("p_android")) out += "android"
        return out
    }

    /** Every game you've bought or claimed, newest first. */
    suspend fun ownedGames(key: String): List<OwnedGame> = withContext(Dispatchers.IO) {
        val out = mutableListOf<OwnedGame>()
        var page = 1
        while (page < 200) {
            val json = get("/profile/owned-keys?page=$page", key)
            val keys = json.optJSONArray("owned_keys") ?: JSONArray()
            for (i in 0 until keys.length()) {
                val k = keys.getJSONObject(i)
                val g = k.optJSONObject("game") ?: continue
                out += OwnedGame(
                    downloadKeyId = k.optLong("id"),
                    gameId = k.optLong("game_id", g.optLong("id")),
                    title = g.optString("title").ifBlank { "Untitled" },
                    coverUrl = g.optString("cover_url").ifBlank { null },
                    pageUrl = g.optString("url").ifBlank { null },
                    classification = g.optString("classification", "game"),
                    platforms = platformsOf(g),
                    isWebGame = g.optString("type") == "html",
                )
            }
            val perPage = json.optInt("per_page", 50)
            if (keys.length() < perPage || keys.length() == 0) break
            page++
        }
        out.distinctBy { it.gameId }
    }

    suspend fun uploads(key: String, game: OwnedGame): List<Upload> = withContext(Dispatchers.IO) {
        val json = get("/games/${game.gameId}/uploads?download_key_id=${game.downloadKeyId}", key)
        val arr = json.optJSONArray("uploads") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            Upload(
                id = u.optLong("id"),
                fileName = u.optString("filename"),
                displayName = u.optString("display_name").ifBlank { u.optString("filename") },
                size = u.optLong("size"),
                type = u.optString("type", "default"),
                platforms = platformsOf(u),
            )
        }
    }

    /** A URL that redirects to the actual file for this upload. */
    fun downloadUrl(key: String, game: OwnedGame, upload: Upload): String =
        "$BASE/uploads/${upload.id}/download?download_key_id=${game.downloadKeyId}" +
            "&api_key=${URLEncoder.encode(key, "UTF-8")}"

    /** Picks the most likely Windows build: plain game files over extras, 64-bit over 32-bit. */
    fun bestWindowsUploads(uploads: List<Upload>): List<Upload> =
        uploads.filter { it.isWindows && it.type == "default" }
            .ifEmpty { uploads.filter { it.isWindows } }
            .sortedWith(
                compareByDescending<Upload> { Regex("64").containsMatchIn(it.fileName) }
                    .thenByDescending { it.size },
            )
}
