package dev.aiui.llm

import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanOnlyCleartextTest {

    @Test
    fun privateAndLocalHosts_countAsLocalNetwork() {
        // given
        val hosts = listOf("192.168.1.24", "10.0.0.5", "172.20.1.1", "127.0.0.1", "100.101.102.103", "macbook.local", "localhost", "fd12::1")

        // then
        hosts.forEach { assertTrue(it, isLocalNetworkHost(it)) }
    }

    @Test
    fun publicHosts_doNotCountAsLocalNetwork() {
        // given
        val hosts = listOf("api.openai.com", "8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1")

        // then
        hosts.forEach { assertFalse(it, isLocalNetworkHost(it)) }
    }

    @Test
    fun plainHttpToPublicHost_isBlockedBeforeAnyConnection() {
        // given
        val client = buildHttpClient()
        val request = Request.Builder().url("http://203.0.113.10/v1/models").build()

        // when
        val error = runCatching { client.newCall(request).execute() }.exceptionOrNull()

        // then
        assertTrue(error is CleartextBlockedException)
    }
}
