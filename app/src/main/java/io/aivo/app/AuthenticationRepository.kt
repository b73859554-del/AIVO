package io.aivo.app

import com.google.gson.Gson
import java.time.Instant

internal class AuthenticationRepository(
    private val api: AivoApi,
    private val sessions: SessionStore,
    private val clock: () -> Long = { Instant.now().epochSecond },
    private val gson: Gson = Gson(),
) {
    fun signInWithEmail(email: String, password: String): AivoSession =
        authenticate(AivoEndpoint.EMAIL_SIGN_IN, EmailRules.validateCredentials(email, password))

    fun registerWithEmail(email: String, password: String): AivoSession =
        authenticate(AivoEndpoint.EMAIL_SIGN_UP, EmailRules.validateRegistration(email, password))

    fun signInWithGoogle(idToken: String, nonce: String): AivoSession {
        require(idToken.isNotBlank()) { "Google did not return an ID token." }
        require(nonce.isNotBlank()) { "Google sign-in nonce is missing." }
        val response = api.execute(
            AivoEndpoint.GOOGLE_SIGN_IN,
            GoogleTokenRequest(idToken, nonce),
        )
        return persistSession(response.body)
    }

    private fun isValidToken(token: String): Boolean =
        token.isNotBlank() && token.length <= MAX_TOKEN_LENGTH && token.none(Character::isISOControl)

    fun requestPasswordReset(email: String) {
        val normalizedEmail = EmailRules.validateAddress(email)
        api.execute(AivoEndpoint.EMAIL_PASSWORD_RESET, EmailAddressRequest(normalizedEmail))
    }

    fun currentSession(): AivoSession? = sessions.read()

    fun clearLocalSession() = sessions.clear()

    fun validAccessToken(): String {
        val session = sessions.read() ?: throw AivoApiException(null, "Sign in to your AIVO account to continue.")
        if (session.expiresAtEpochSeconds > clock() + EXPIRY_SKEW_SECONDS) return session.accessToken

        val response = try {
            api.execute(
                AivoEndpoint.SESSION_REFRESH,
                RefreshTokenRequest(session.refreshToken),
            )
        } catch (exception: AivoApiException) {
            if (exception.statusCode == 401 || exception.statusCode == 403) sessions.clear()
            throw exception
        }
        return persistSession(response.body, fallbackEmail = session.email).accessToken
    }

    fun signOut(): String? {
        val session = sessions.read()
        val remoteFailure = if (session == null) null else try {
            api.execute(
                AivoEndpoint.SESSION_LOGOUT,
                LogoutRequest(session.refreshToken),
                session.accessToken,
            )
            null
        } catch (_: AivoApiException) {
            "AIVO could not confirm remote session revocation."
        }
        sessions.clear()
        return remoteFailure
    }

    fun deleteAccount() {
        val accessToken = validAccessToken()
        api.execute(AivoEndpoint.ACCOUNT_DELETE, accessToken = accessToken)
        sessions.clear()
    }

    private fun authenticate(endpoint: AivoEndpoint, request: EmailAuthRequest): AivoSession {
        val response = api.execute(endpoint, request)
        return persistSession(response.body, fallbackEmail = request.email)
    }

    private fun persistSession(responseBody: String, fallbackEmail: String? = null): AivoSession {
        val wireSession = try {
            gson.fromJson(responseBody, SessionResponse::class.java)
        } catch (exception: Exception) {
            throw AivoApiException(null, "AIVO returned an invalid session response.", exception)
        } ?: throw AivoApiException(null, "AIVO returned an empty session response.")

        val accessToken = wireSession.accessToken?.takeIf(::isValidToken)
            ?: throw AivoApiException(null, "AIVO did not issue an access token.")
        val refreshToken = wireSession.refreshToken?.takeIf(::isValidToken)
            ?: throw AivoApiException(null, "AIVO did not issue a refresh token.")
        val expiresIn = wireSession.expiresInSeconds
            ?.takeIf { it in MIN_SESSION_SECONDS..MAX_SESSION_SECONDS }
            ?: throw AivoApiException(null, "AIVO returned an invalid session expiry.")
        val email = (wireSession.email ?: fallbackEmail)
            ?.takeIf(String::isNotBlank)
            ?: throw AivoApiException(null, "AIVO did not return an account email.")

        return AivoSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = clock() + expiresIn,
            email = email,
        ).also(sessions::write)
    }

    private companion object {
        const val EXPIRY_SKEW_SECONDS = 60
        const val MIN_SESSION_SECONDS = 60
        const val MAX_SESSION_SECONDS = 60L * 60 * 24 * 90
        const val MAX_TOKEN_LENGTH = 16 * 1024
    }
}

internal object EmailRules {
    fun validateCredentials(email: String, password: String): EmailAuthRequest {
        require(password.isNotEmpty() && password.length <= MAX_PASSWORD_LENGTH) {
            "Enter a password of no more than 128 characters."
        }
        return EmailAuthRequest(validateAddress(email), password)
    }

    fun validateRegistration(email: String, password: String): EmailAuthRequest {
        require(password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "Password must be between 8 and 128 characters."
        }
        return EmailAuthRequest(validateAddress(email), password)
    }

    fun validateAddress(email: String): String {
        val normalized = email.trim()
        require(normalized.length <= MAX_EMAIL_LENGTH && EMAIL_PATTERN.matches(normalized)) {
            "Enter a valid email address."
        }
        return normalized
    }

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private const val MIN_PASSWORD_LENGTH = 8
    private const val MAX_PASSWORD_LENGTH = 128
    private const val MAX_EMAIL_LENGTH = 254
}
