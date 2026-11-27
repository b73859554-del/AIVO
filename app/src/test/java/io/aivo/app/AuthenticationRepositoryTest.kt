package io.aivo.app

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationRepositoryTest {
    @Test
    fun emailSignInPersistsOnlyIssuedSessionAndNormalizesEmail() {
        val api = FakeApi()
        val sessions = MemorySessionStore()
        val repository = AuthenticationRepository(api, sessions, clock = { 1_000L })

        val session = repository.signInWithEmail("  person@example.test ", "password with spaces")

        assertEquals("person@example.test", (api.lastPayload as EmailAuthRequest).email)
        assertEquals("password with spaces", (api.lastPayload as EmailAuthRequest).password)
        assertEquals("issued-access", session.accessToken)
        assertEquals(session, sessions.session)
    }

    @Test
    fun refreshesExpiredSessionAndStoresRotatedTokens() {
        val api = FakeApi()
        api.responseByEndpoint[AivoEndpoint.SESSION_REFRESH] = sessionResponse(
            access = "rotated-access",
            refresh = "rotated-refresh",
            expiry = 3600,
            email = "person@example.test",
        )
        val sessions = MemorySessionStore().apply {
            session = AivoSession("expired-access", "old-refresh", 900L, "person@example.test")
        }
        val repository = AuthenticationRepository(api, sessions, clock = { 1_000L })

        assertEquals("rotated-access", repository.validAccessToken())
        assertEquals("rotated-refresh", sessions.session?.refreshToken)
        assertEquals(AivoEndpoint.SESSION_REFRESH, api.lastEndpoint)
    }

    @Test
    fun signOutAlwaysRemovesLocalSessionAndReportsRemoteFailure() {
        val api = FakeApi().apply {
            failure = AivoApiException(null, "unreachable")
        }
        val sessions = MemorySessionStore().apply {
            session = AivoSession("access", "refresh", 10_000L, "person@example.test")
        }
        val repository = AuthenticationRepository(api, sessions, clock = { 1L })

        val warning = repository.signOut()

        assertNull(sessions.session)
        assertEquals("AIVO could not confirm remote session revocation.", warning)
    }

    @Test
    fun registrationRequiresStrongPasswordButSignInKeepsExistingPasswordCompatibility() {
        assertEquals(
            "person@example.test",
            EmailRules.validateCredentials(" person@example.test ", "short").email,
        )
        assertTrue(
            runCatching { EmailRules.validateRegistration("person@example.test", "short") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertFalse(EmailRules.validateRegistration("person@example.test", "long-enough-password").password.isBlank())
    }

    @Test
    fun unauthorizedRefreshClearsLocalSession() {
        val api = FakeApi().apply {
            failureByEndpoint[AivoEndpoint.SESSION_REFRESH] = AivoApiException(401, "unauthorized")
        }
        val sessions = MemorySessionStore().apply {
            session = AivoSession("expired-access", "old-refresh", 900L, "person@example.test")
        }
        val repository = AuthenticationRepository(api, sessions, clock = { 1_000L })

        assertTrue(
            runCatching { repository.validAccessToken() }
                .exceptionOrNull() is AivoApiException,
        )
        assertNull(sessions.session)
    }

    @Test
    fun savedSessionRequiresNonemptyFieldsAndPositiveExpiry() {
        val valid = AivoSession("access", "refresh", 1_000L, "person@example.test")
        assertEquals(valid, AivoSessionRules.validate(valid))
        listOf(
            valid.copy(accessToken = ""),
            valid.copy(refreshToken = ""),
            valid.copy(expiresAtEpochSeconds = 0),
            valid.copy(email = ""),
        ).forEach { invalid ->
            assertTrue(runCatching { AivoSessionRules.validate(invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { AivoSessionRules.validate(null) }.exceptionOrNull() is IllegalArgumentException)
    }

    private companion object {
        fun sessionResponse(access: String, refresh: String, expiry: Long, email: String): String =
            Gson().toJson(
                mapOf(
                    "access_token" to access,
                    "refresh_token" to refresh,
                    "expires_in" to expiry,
                    "email" to email,
                ),
            )
    }

    private class FakeApi : AivoApi {
        var lastEndpoint: AivoEndpoint? = null
        var lastPayload: Any? = null
        var failure: Exception? = null
        val failureByEndpoint = mutableMapOf<AivoEndpoint, Exception>()
        val responseByEndpoint = mutableMapOf<AivoEndpoint, String>()

        override fun execute(
            endpoint: AivoEndpoint,
            payload: Any?,
            accessToken: String?,
            cancellation: RequestCancellation,
        ): AivoApiResponse {
            lastEndpoint = endpoint
            lastPayload = payload
            failureByEndpoint[endpoint]?.let { throw it }
            failure?.let { throw it }
            return AivoApiResponse(
                200,
                responseByEndpoint[endpoint] ?: sessionResponse(
                    access = "issued-access",
                    refresh = "issued-refresh",
                    expiry = 3600,
                    email = "person@example.test",
                ),
            )
        }
    }

    private class MemorySessionStore : SessionStore {
        var session: AivoSession? = null
        override fun read(): AivoSession? = session
        override fun write(session: AivoSession) {
            this.session = session
        }
        override fun clear() {
            session = null
        }
    }
}
