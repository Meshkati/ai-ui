package dev.aiui.update

import dev.aiui.data.PrefsRepository
import dev.aiui.llm.LenientJson
import dev.aiui.llm.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Asks GitHub for the tag of the latest release, e.g. "v0.2.0". */
class GitHubReleases(private val http: OkHttpClient, private val latestUrl: String = Updates.LATEST_URL) {

    /** Null if the request fails or the answer has no tag. Sends nothing but [userAgent]. */
    suspend fun latestTag(userAgent: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(latestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", userAgent)
            .build()
        try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) return@use null
                LenientJson.parseToJsonElement(response.body.string())
                    .jsonObject["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null // not JSON, or not an object
        }
    }
}

/** The daily check behind the chat screen's notice, and the one the user starts in Settings. */
class UpdateChecker(
    private val prefs: PrefsRepository,
    private val releases: GitHubReleases,
    val installed: String,
) {
    private val userAgent = Updates.userAgent(installed)

    /** The newer release GitHub last reported, if any. */
    val newer: Flow<Version?> = prefs.updates.map { Updates.offer(installed, it.latestRelease) }.distinctUntilChanged()

    /** A newer release the user has not been told about yet. */
    val untold: Flow<Version?> =
        prefs.updates.map { Updates.offer(installed, it.latestRelease, it.toldRelease) }.distinctUntilChanged()

    /** Asks GitHub at most once a day, and only from a release build. */
    suspend fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (!Updates.checks(installed)) return
        if (!Updates.isDue(now, prefs.updates.first().nextCheck)) return
        prefs.setNextUpdateCheck(now + Updates.INTERVAL_FAILED)
        val tag = releases.latestTag(userAgent) ?: return
        prefs.setLatestRelease(tag, nextCheck = now + Updates.INTERVAL_OK)
    }

    /** Asks GitHub now. A check that works also counts as the day's automatic one. */
    suspend fun checkNow(): Updates.Found {
        val tag = releases.latestTag(userAgent)
        if (tag != null) prefs.setLatestRelease(tag, nextCheck = System.currentTimeMillis() + Updates.INTERVAL_OK)
        return Updates.found(installed, tag)
    }

    suspend fun markTold(version: Version) = prefs.setToldRelease("v$version")
}
