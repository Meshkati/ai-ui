package dev.aiui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun releaseTag_parsesLikeItsVersionName() {
        // when
        val fromTag = Version.parse("v0.2.10")
        val fromVersionName = Version.parse("0.2.10")

        // then
        assertEquals(Version(0, 2, 10), fromTag)
        assertEquals(fromTag, fromVersionName)
    }

    @Test
    fun localAndDebugVersionNames_areNotReleases() {
        // given
        val names = listOf("0.0.0-local", "0.1.1-debug", "0.0.0-pr12", "v1.02.3", "")

        // when
        val parsed = names.map(Version::parse)

        // then
        assertTrue(parsed.all { it == null })
    }

    @Test
    fun versionsWithTwoDigitParts_compareByNumberNotText() {
        // when
        val newer = Version(0, 10, 0) > Version(0, 9, 9)

        // then
        assertTrue(newer)
    }

    @Test
    fun localBuild_doesNotCheckOnItsOwn() {
        // when
        val checks = Updates.checks("0.0.0-local-debug")

        // then
        assertFalse(checks)
    }

    @Test
    fun newerRelease_isOffered() {
        // when
        val offer = Updates.offer(installed = "0.1.1", latest = "v0.2.0")

        // then
        assertEquals(Version(0, 2, 0), offer)
    }

    @Test
    fun installedLatestRelease_isNotOfferedItself() {
        // when
        val offer = Updates.offer(installed = "0.2.0", latest = "v0.2.0")

        // then
        assertNull(offer)
    }

    @Test
    fun releaseTheUserWasToldAbout_isNotOfferedAgain() {
        // when
        val offer = Updates.offer(installed = "0.1.1", latest = "v0.2.0", told = "v0.2.0")

        // then
        assertNull(offer)
    }

    @Test
    fun releaseNewerThanTheOneTold_isOffered() {
        // when
        val offer = Updates.offer(installed = "0.1.1", latest = "v0.2.1", told = "v0.2.0")

        // then
        assertEquals(Version(0, 2, 1), offer)
    }

    @Test
    fun beforeTheFirstCheck_nothingIsOffered() {
        // when
        val offer = Updates.offer(installed = "0.1.1", latest = "")

        // then
        assertNull(offer)
    }

    @Test
    fun waitNotOver_checkIsNotDue() {
        // when
        val due = Updates.isDue(now = 10 * hour, next = 11 * hour)

        // then
        assertFalse(due)
    }

    @Test
    fun waitOver_checkIsDue() {
        // when
        val due = Updates.isDue(now = 11 * hour, next = 11 * hour)

        // then
        assertTrue(due)
    }

    @Test
    fun clockTurnedBack_doesNotBlockChecks() {
        // when
        val due = Updates.isDue(now = 0, next = 25 * hour)

        // then
        assertTrue(due)
    }

    @Test
    fun userCheckWithNewerRelease_findsIt() {
        // when
        val found = Updates.found(installed = "0.1.1", latest = "v0.2.0")

        // then
        assertEquals(Updates.Found.Newer(Version(0, 2, 0)), found)
    }

    @Test
    fun userCheckOnTheLatestRelease_isUpToDate() {
        // when
        val found = Updates.found(installed = "0.2.0", latest = "v0.2.0")

        // then
        assertEquals(Updates.Found.UpToDate, found)
    }

    @Test
    fun userCheckOnADebugBuild_namesTheLatestRelease() {
        // when
        val found = Updates.found(installed = "0.0.0-local-debug", latest = "v0.2.0")

        // then
        assertEquals(Updates.Found.NotARelease(Version(0, 2, 0)), found)
    }

    @Test
    fun userCheckWithNoAnswer_fails() {
        // when
        val found = Updates.found(installed = "0.1.1", latest = null)

        // then
        assertEquals(Updates.Found.Failed, found)
    }

    @Test
    fun userCheckWithAnOddTag_fails() {
        // when
        val found = Updates.found(installed = "0.1.1", latest = "nightly")

        // then
        assertEquals(Updates.Found.Failed, found)
    }

    @Test
    fun releasePage_isThisRepositorysTag() {
        // when
        val url = Updates.releasePage(Version(0, 2, 0))

        // then
        assertEquals("https://github.com/Meshkati/ai-ui/releases/tag/v0.2.0", url)
    }
}
