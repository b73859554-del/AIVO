package io.aivo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AivoApiClientTest {
    @Test
    fun buildsOnlyAivoVersionedPathsUnderConfiguredProxy() {
        val client = AivoApiClient("https://api.example.test/gateway/aivo/")

        assertEquals(
            "https://api.example.test/gateway/aivo/v1/chat/completions",
            client.urlFor(AivoEndpoint.CHAT_COMPLETIONS).toExternalForm(),
        )
        assertEquals(
            "https://api.example.test/gateway/aivo/v1/auth/google",
            client.urlFor(AivoEndpoint.GOOGLE_SIGN_IN).toExternalForm(),
        )
    }

    @Test
    fun unconfiguredEnvironmentFailsClosedWithoutFallback() {
        val exception = runCatching {
            AivoApiClient(null).urlFor(AivoEndpoint.CHAT_COMPLETIONS)
        }.exceptionOrNull()

        assertTrue(exception is AivoApiException)
        assertEquals("AIVO service is not configured for this environment.", exception?.message)
    }

    @Test
    fun authenticatedRoutesRejectMissingAccessTokenBeforeOpeningConnection() {
        val exception = runCatching {
            AivoApiClient("https://api.example.test").execute(
                endpoint = AivoEndpoint.CHAT_COMPLETIONS,
                payload = null,
                accessToken = null,
                cancellation = RequestCancellation(),
            )
        }.exceptionOrNull()

        assertTrue(exception is AivoApiException)
        assertEquals("Sign in to your AIVO account to continue.", exception?.message)
    }
}
