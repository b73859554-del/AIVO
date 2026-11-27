package io.aivo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendConfigurationTest {
    @Test
    fun unsetUrlDisablesBackendConfiguration() {
        assertNull(ProxyUrl.resolve(""))
        assertNull(ProxyUrl.resolve("  "))
    }

    @Test
    fun acceptsHttpsUrlWithoutCredentialsOrQuery() {
        assertEquals(
            "https://api.example.test/aivo",
            ProxyUrl.resolve("https://api.example.test/aivo"),
        )
    }

    @Test
    fun rejectsInsecureOrCredentialBearingUrls() {
        listOf(
            "http://api.example.test",
            "https://user:password@api.example.test",
            "https://api.example.test?token=secret",
            "https://api.example.test#fragment",
        ).forEach { url ->
            val exception = runCatching { ProxyUrl.resolve(url) }.exceptionOrNull()
            assertNotNull("Expected URL to be rejected: $url", exception)
            assertTrue(exception is IllegalArgumentException)
        }
    }
}
