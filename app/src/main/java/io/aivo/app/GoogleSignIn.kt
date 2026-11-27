package io.aivo.app

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom
import java.util.Base64

internal data class GoogleIdTokenResult(val idToken: String, val nonce: String)

internal class GoogleSignIn(private val activity: Activity) {
    suspend fun requestIdToken(webClientId: String): GoogleIdTokenResult {
        require(webClientId.isNotBlank()) { "Google OAuth client ID is not configured for this AIVO environment." }
        val nonce = createNonce()
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val response = try {
            CredentialManager.create(activity).getCredential(
                context = activity,
                request = request,
            )
        } catch (exception: NoCredentialException) {
            throw GoogleCredentialUnavailableException(exception)
        }
        val credential = response.credential
        require(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) {
            "Google sign-in returned an unsupported credential type."
        }
        val tokenCredential = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (exception: Exception) {
            throw IllegalStateException("Google sign-in returned an invalid ID token.", exception)
        }
        return GoogleIdTokenResult(tokenCredential.idToken, nonce)
    }

    suspend fun clearProviderState() {
        CredentialManager.create(activity).clearCredentialState(
            androidx.credentials.ClearCredentialStateRequest(),
        )
    }

    private fun createNonce(): String {
        val bytes = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val NONCE_BYTES = 32
    }
}

internal class GoogleCredentialUnavailableException(cause: NoCredentialException) :
    Exception("No Google credential is available on this device.", cause)
