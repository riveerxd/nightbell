package me.river.nightbell.data.check

import android.util.Log
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.UpdateSource
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks whether a newer Nightbell exists.
 *
 * Two sources, because there are two ways to have installed this app and they do
 * not move together. GitHub carries the APK the maintainer signs, available the
 * moment a tag goes out. F-Droid builds from source on its own schedule and is
 * usually a release or two behind, which for someone who installed from there is
 * not a lag but the truth: it is the newest version their client can hand them.
 *
 * Nothing here downloads or installs anything, and there is no code path in this
 * app that could. An update is a notification with a link.
 */
class UpdateChecker(
    baseClient: OkHttpClient? = null,
    private val githubBase: String = GitHubChecker.API_BASE,
    private val fdroidBase: String = FDROID_BASE,
) {

    private val client: OkHttpClient = (baseClient ?: OkHttpClient())
        .newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The newest release [source] knows about, or null when the question could
     * not be answered.
     *
     * Null rather than a throw or a fabricated answer: a version check that fails
     * is a non-event, and the only correct response to one is to say nothing and
     * ask again in six hours.
     */
    suspend fun latest(source: UpdateSource): AppUpdate.Release? = withContext(Dispatchers.IO) {
        try {
            when (source) {
                UpdateSource.GITHUB -> github()
                UpdateSource.FDROID -> fdroid()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Log.i(TAG, "Update check failed (${error::class.java.simpleName}); trying again later")
            null
        }
    }

    private fun github(): AppUpdate.Release? {
        val url = "$githubBase/repos/${AppUpdate.REPO_OWNER}/${AppUpdate.REPO_NAME}/releases/latest"
        val obj = fetchObject(url, github = true) ?: return null
        val tag = obj.text("tag_name") ?: return null
        val version = tag.removePrefix("v")
        if (version.isBlank()) return null
        return AppUpdate.Release(
            version = version,
            url = obj.text("html_url").orEmpty().ifBlank { AppUpdate.DOWNLOAD_URL },
            source = UpdateSource.GITHUB,
            notes = obj.text("name").orEmpty(),
        )
    }

    private fun fdroid(): AppUpdate.Release? {
        val obj = fetchObject("$fdroidBase/api/v1/packages/${AppUpdate.FDROID_PACKAGE}", github = false)
            ?: return null
        val suggested = (obj["suggestedVersionCode"] as? JsonPrimitive)?.content?.toIntOrNull()
        val packages = obj["packages"] as? JsonArray ?: return null
        // The suggested code is the one an F-Droid client would install. Falling
        // back to the highest listed keeps this working if that field ever goes.
        val entry = packages
            .filterIsInstance<JsonObject>()
            .let { list ->
                list.firstOrNull { (it["versionCode"] as? JsonPrimitive)?.content?.toIntOrNull() == suggested }
                    ?: list.maxByOrNull { (it["versionCode"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
            } ?: return null
        val version = entry.text("versionName")?.takeIf { it.isNotBlank() } ?: return null
        return AppUpdate.Release(
            version = version,
            url = AppUpdate.FDROID_URL,
            source = UpdateSource.FDROID,
        )
    }

    private fun fetchObject(url: String, github: Boolean): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", HttpChecker.USER_AGENT)
            .apply {
                if (github) {
                    header("Accept", "application/vnd.github+json")
                    header("X-GitHub-Api-Version", GitHubChecker.API_VERSION)
                } else {
                    header("Accept", "application/json")
                }
            }
            .build()
        // Deliberately unauthenticated, even when the user has saved a token. A
        // version check is about this app rather than about anything of theirs,
        // and spending their rate-limit budget on it (or sending their credential
        // somewhere it is not needed) would be taking a liberty.
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = runCatching { response.body.string() }.getOrDefault("")
            return runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private const val TAG = "UpdateChecker"
        const val FDROID_BASE = "https://f-droid.org"
        private const val TIMEOUT_SECONDS = 10L
    }
}
