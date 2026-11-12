package dev.aiui.update

import dev.aiui.llm.buildHttpClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GitHubReleasesTest {
    private val server = MockWebServer()
    private lateinit var releases: GitHubReleases

    @Before
    fun setUp() {
        server.start()
        releases = GitHubReleases(buildHttpClient(), server.url("/repos/Meshkati/ai-ui/releases/latest").toString())
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    @Test
    fun latestRelease_returnsItsTag() = runBlocking {
        // given
        server.enqueue(json("""{"tag_name":"v0.2.0","name":"v0.2.0","assets":[]}"""))

        // when
        val tag = releases.latestTag("ai-ui/0.1.1 (Android)")

        // then
        assertEquals("v0.2.0", tag)
    }

    @Test
    fun request_sendsOnlyTheUserAgentAndGitHubHeaders() = runBlocking {
        // given
        server.enqueue(json("""{"tag_name":"v0.2.0"}"""))

        // when
        releases.latestTag("ai-ui/0.1.1 (Android)")

        // then
        val recorded = server.takeRequest()
        assertEquals("ai-ui/0.1.1 (Android)", recorded.headers["User-Agent"])
        assertEquals("application/vnd.github+json", recorded.headers["Accept"])
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun rateLimited_returnsNoTag() = runBlocking {
        // given
        server.enqueue(json("""{"message":"API rate limit exceeded"}""", code = 403))

        // when
        val tag = releases.latestTag("ai-ui/0.1.1 (Android)")

        // then
        assertNull(tag)
    }

    @Test
    fun answerThatIsNotJson_returnsNoTag() = runBlocking {
        // given
        server.enqueue(MockResponse.Builder().body("<html>captive portal</html>").build())

        // when
        val tag = releases.latestTag("ai-ui/0.1.1 (Android)")

        // then
        assertNull(tag)
    }

    @Test
    fun serverUnreachable_returnsNoTag() = runBlocking {
        // given
        val url = server.url("/repos/Meshkati/ai-ui/releases/latest").toString()
        server.close()
        val offline = GitHubReleases(buildHttpClient(), url)

        // when
        val tag = offline.latestTag("ai-ui/0.1.1 (Android)")

        // then
        assertNull(tag)
    }
}
