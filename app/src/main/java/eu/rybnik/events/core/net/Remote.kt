package eu.rybnik.events.core.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single place that knows where the scraped data lives.
 *
 * ─────────────────────────────────────────────────────────────
 *  ZMIEN TYLKO [GH_USER] na swoja nazwe uzytkownika GitHub.
 *  Repo musi byc publiczne — raw.githubusercontent.com nie
 *  obsluguje prywatnych repo bez tokenu.
 * ─────────────────────────────────────────────────────────────
 */
object RemoteConfig {
    const val GH_USER = "esm0x"
    private const val GH_REPO = "rybnik-app"
    private const val GH_BRANCH = "main"

    fun dataUrl(fileName: String) =
        "https://raw.githubusercontent.com/$GH_USER/$GH_REPO/$GH_BRANCH/scraper/data/$fileName"

    val isConfigured: Boolean get() = GH_USER != "YOUR_GH_USER"
}

val sharedHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

val sharedJson: Json by lazy {
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
}

/** What the UI needs to know about a background load, beyond the data itself. */
data class LoadState(
    val loading: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long? = null,
)

/**
 * Fetches a JSON document over HTTP and keeps the raw body in internal storage so a
 * cold start shows the last known data instead of a spinner. Subclasses only supply
 * the parse step.
 */
abstract class CachedRemoteSource<T>(
    private val context: Context,
    private val url: String,
    private val cacheFileName: String,
) {
    protected abstract fun parse(body: String): T

    private val _data = MutableStateFlow<T?>(null)
    val data: StateFlow<T?> = _data.asStateFlow()

    private val _state = MutableStateFlow(LoadState())
    val state: StateFlow<LoadState> = _state.asStateFlow()

    private val cacheFile: File get() = File(context.filesDir, cacheFileName)

    /** Cheap blocking read of the last good payload. Safe to call on app start. */
    fun loadCache() {
        runCatching {
            if (!cacheFile.exists()) return
            _data.value = parse(cacheFile.readText())
            _state.value = _state.value.copy(lastUpdated = cacheFile.lastModified())
        }.onFailure { Log.w(TAG, "loadCache($cacheFileName) failed", it) }
    }

    suspend fun refresh(): Result<T> = withContext(Dispatchers.IO) {
        if (!RemoteConfig.isConfigured) {
            val msg = "Ustaw GH_USER w RemoteConfig.kt — apka nie wie, skąd pobrać dane."
            _state.value = _state.value.copy(loading = false, error = msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching {
            val body = sharedHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error(httpMessage(r.code))
                r.body?.string() ?: error("Pusta odpowiedź serwera")
            }
            val parsed = parse(body)
            _data.value = parsed
            runCatching { cacheFile.writeText(body) }
                .onFailure { Log.w(TAG, "cache write failed", it) }
            _state.value = LoadState(loading = false, lastUpdated = System.currentTimeMillis())
            parsed
        }.onFailure { e ->
            Log.w(TAG, "refresh($url) failed", e)
            _state.value = _state.value.copy(loading = false, error = e.message ?: "Błąd pobierania")
        }
    }

    private fun httpMessage(code: Int) = when (code) {
        404 -> "Nie znaleziono danych (404). Sprawdź GH_USER i czy workflow już się wykonał."
        403 -> "Brak dostępu (403). Repo musi być publiczne."
        else -> "Błąd HTTP $code"
    }

    private companion object {
        const val TAG = "CachedRemoteSource"
    }
}
