package io.aivo.app

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal enum class AivoEndpoint(val path: String, val method: String, val requiresAccessToken: Boolean = false) {
    EMAIL_SIGN_UP("v1/auth/email/sign-up", "POST"),
    EMAIL_SIGN_IN("v1/auth/email/sign-in", "POST"),
    EMAIL_PASSWORD_RESET("v1/auth/email/password-reset", "POST"),
    GOOGLE_SIGN_IN("v1/auth/google", "POST"),
    SESSION_REFRESH("v1/auth/refresh", "POST"),
    SESSION_LOGOUT("v1/auth/logout", "POST", true),
    ACCOUNT_DELETE("v1/account", "DELETE", true),
    CHAT_COMPLETIONS("v1/chat/completions", "POST", true),
}

internal data class AivoApiResponse(val statusCode: Int, val body: String)

internal interface AivoApi {
    fun execute(
        endpoint: AivoEndpoint,
        payload: Any? = null,
        accessToken: String? = null,
        cancellation: RequestCancellation = RequestCancellation(),
    ): AivoApiResponse
}

internal class AivoApiException(
    val statusCode: Int?,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class RequestCancellation {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()

    fun cancel() {
        cancelled.set(true)
        connection.getAndSet(null)?.disconnect()
    }

    internal fun attach(connection: HttpURLConnection) {
        this.connection.set(connection)
        if (cancelled.get()) this.connection.getAndSet(null)?.disconnect()
    }

    internal fun detach() {
        connection.set(null)
    }

    internal fun isCancelled(): Boolean = cancelled.get()
}

internal class AivoApiClient(
    private val baseUrl: String?,
    private val gson: Gson = Gson(),
) : AivoApi {
    fun urlFor(endpoint: AivoEndpoint): URL {
        val configuredBase = baseUrl?.let(ProxyUrl::resolve)
            ?: throw AivoApiException(null, "AIVO service is not configured for this environment.")
        return try {
            val base = URI(configuredBase)
            URI(
                base.scheme,
                null,
                base.host,
                base.port,
                "${base.path.orEmpty().trimEnd('/')}/${endpoint.path}",
                null,
                null,
            ).toURL()
        } catch (exception: Exception) {
            throw AivoApiException(null, "AIVO service configuration is invalid.", exception)
        }
    }

    override fun execute(
        endpoint: AivoEndpoint,
        payload: Any?,
        accessToken: String?,
        cancellation: RequestCancellation,
    ): AivoApiResponse {
        val url = urlFor(endpoint)
        if (endpoint.requiresAccessToken && accessToken.isNullOrBlank()) {
            throw AivoApiException(null, "Sign in to your AIVO account to continue.")
        }
        require(accessToken == null || (
            accessToken.length <= MAX_TOKEN_LENGTH &&
                accessToken.none { Character.isISOControl(it) }
            )
        ) {
            "AIVO access token has an invalid format."
        }
        val connection = try {
            url.openConnection() as? HttpsURLConnection
                ?: throw AivoApiException(null, "AIVO requests require HTTPS.")
        } catch (exception: AivoApiException) {
            throw exception
        } catch (exception: IOException) {
            throw AivoApiException(null, "Could not connect to AIVO. Check your connection and try again.", exception)
        }
        cancellation.attach(connection)

        try {
            if (cancellation.isCancelled()) throw AivoRequestCancelledException()
            connection.requestMethod = endpoint.method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-AIVO-Environment", BuildConfig.AIVO_ENVIRONMENT)
            if (!accessToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val requestBytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
                require(requestBytes.size <= MAX_REQUEST_BYTES) { "AIVO request exceeds the supported size." }
                connection.outputStream.use { it.write(requestBytes) }
            }
            if (cancellation.isCancelled()) throw AivoRequestCancelledException()

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use(::readBoundedBody).orEmpty()
            if (statusCode !in 200..299) {
                throw AivoApiException(statusCode, responseMessage(responseBody, statusCode))
            }
            return AivoApiResponse(statusCode, responseBody)
        } catch (exception: AivoApiException) {
            throw exception
        } catch (exception: IOException) {
            if (cancellation.isCancelled()) throw AivoRequestCancelledException()
            throw AivoApiException(null, "AIVO could not complete the request. Please try again.", exception)
        } finally {
            cancellation.detach()
            connection.disconnect()
        }
    }

    private fun readBoundedBody(stream: java.io.InputStream): String {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val output = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) {
                throw AivoApiException(null, "AIVO response exceeded the supported size.")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun responseMessage(body: String, statusCode: Int): String {
        val message = try {
            gson.fromJson(body, AivoErrorBody::class.java)?.message
        } catch (_: JsonParseException) {
            null
        }
        val safeMessage = message
            ?.filter { it == '\n' || it == '\t' || it.code >= 32 }
            ?.trim()
            ?.take(MAX_ERROR_MESSAGE_CHARS)
            ?.takeIf(String::isNotBlank)
        return safeMessage ?: "AIVO request failed (HTTP $statusCode)."
    }

    private data class AivoErrorBody(val message: String?)

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 45_000
        const val READ_BUFFER_BYTES = 8 * 1024
        const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_ERROR_MESSAGE_CHARS = 240
        const val MAX_TOKEN_LENGTH = 16 * 1024
    }
}

internal class AivoRequestCancelledException : IOException("AIVO request was cancelled.")

internal data class EmailAuthRequest(val email: String, val password: String)
internal data class EmailAddressRequest(val email: String)
internal data class GoogleTokenRequest(
    @SerializedName("id_token") val idToken: String,
    val nonce: String,
)
internal data class RefreshTokenRequest(@SerializedName("refresh_token") val refreshToken: String)
internal data class LogoutRequest(@SerializedName("refresh_token") val refreshToken: String)
internal data class ChatTurn(val role: String, val content: String)
internal data class ChatAttachment(
    val name: String,
    @SerializedName("mime_type") val mimeType: String,
    val data: String,
)
internal data class ChatCompletionRequest(
    @SerializedName("conversation_id")
    val conversationId: String,
    val messages: List<ChatTurn>,
    val attachments: List<ChatAttachment> = emptyList(),
    @SerializedName("preferred_language")
    val preferredLanguage: String,
)
internal data class ChatCompletionResponse(
    val message: ChatTurn?,
    @SerializedName("request_id") val requestId: String?,
)
internal data class SessionResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_in") val expiresInSeconds: Long?,
    val email: String?,
)
