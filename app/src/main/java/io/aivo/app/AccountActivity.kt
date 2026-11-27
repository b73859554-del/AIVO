package io.aivo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountActivity : Activity() {
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var authentication: AuthenticationRepository
    private lateinit var body: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private var registrationMode = false
    private var busy = false
    private var emailDraft = ""
    private var statusText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = AivoApiClient(BackendConfiguration.proxyBaseUrl)
        authentication = AuthenticationRepository(api, EncryptedSessionStore(this))
        render()
    }

    override fun onDestroy() {
        screenScope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.aivo_canvas))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        addButton(header, getString(R.string.back_label)) { finish() }
        addText(header, getString(R.string.account_title), 20f, R.color.aivo_ink, Typeface.BOLD)
        shell.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(32))
        }
        scroll.addView(body)
        shell.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(shell)

        addText(body, getString(R.string.account_security_note), 15f, R.color.aivo_muted)
        addVerticalSpace(body, 20)
        if (statusText != null) {
            addInfo(body, statusText.orEmpty())
            addVerticalSpace(body, 12)
        }

        val session = try {
            authentication.currentSession()
        } catch (_: IllegalStateException) {
            addInfo(body, getString(R.string.account_session_unreadable))
            addButton(body, getString(R.string.sign_out_on_device)) {
                confirmLocalSignOut()
            }
            return
        }

        if (BackendConfiguration.proxyBaseUrl == null) {
            addInfo(body, getString(R.string.account_backend_unavailable))
            addText(body, getString(R.string.account_oauth_not_configured), 14f, R.color.aivo_muted)
            if (session != null) {
                addVerticalSpace(body, 16)
                addText(body, getString(R.string.signed_in_as, session.email), 16f, R.color.aivo_ink)
                addButton(body, getString(R.string.sign_out_on_device)) { confirmLocalSignOut() }
            }
            return
        }

        if (session != null) {
            renderSignedIn(session)
        } else {
            renderSignIn()
        }
    }

    private fun renderSignedIn(session: AivoSession) {
        addText(body, getString(R.string.signed_in_as, session.email), 19f, R.color.aivo_ink, Typeface.BOLD)
        addVerticalSpace(body, 10)
        addText(body, getString(R.string.account_session_note), 14f, R.color.aivo_muted)
        addButton(body, getString(R.string.open_chat)) {
            startActivity(Intent(this, ChatActivity::class.java))
        }
        addButton(body, getString(R.string.sign_out)) { signOut() }
        addButton(body, getString(R.string.delete_account), destructive = true) {
            confirmDeleteAccount()
        }
    }

    private fun renderSignIn() {
        addText(body, getString(R.string.email_label), 14f, R.color.aivo_ink, Typeface.BOLD)
        emailInput = addInput(
            body,
            getString(R.string.email_placeholder),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        emailInput.setText(emailDraft)
        emailInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                emailDraft = value?.toString().orEmpty()
            }
            override fun afterTextChanged(value: Editable?) = Unit
        })
        addVerticalSpace(body, 14)
        addText(body, getString(R.string.password_label), 14f, R.color.aivo_ink, Typeface.BOLD)
        passwordInput = addInput(
            body,
            getString(R.string.password_placeholder),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )

        addButton(body, if (registrationMode) getString(R.string.create_account) else getString(R.string.sign_in)) {
            authenticateByEmail()
        }
        addButton(body, if (registrationMode) getString(R.string.have_account) else getString(R.string.create_account)) {
            registrationMode = !registrationMode
            statusText = null
            render()
        }
        if (!registrationMode) {
            addButton(body, getString(R.string.forgot_password)) { requestPasswordReset() }
        }

        val googleClientId = BackendConfiguration.googleWebClientId
        if (googleClientId != null) {
            addButton(body, getString(R.string.google_sign_in)) { signInWithGoogle(googleClientId) }
        } else {
            addVerticalSpace(body, 12)
            addText(body, getString(R.string.account_oauth_not_configured), 14f, R.color.aivo_muted)
        }
    }

    private fun authenticateByEmail() {
        if (busy) return
        val email = emailInput.text.toString()
        val password = passwordInput.text.toString()
        passwordInput.text?.clear()
        runOperation(
            if (registrationMode) getString(R.string.creating_account) else getString(R.string.signing_in),
        ) {
            val session = withContext(Dispatchers.IO) {
                if (registrationMode) {
                    authentication.registerWithEmail(email, password)
                } else {
                    authentication.signInWithEmail(email, password)
                }
            }
            emailDraft = session.email
            statusText = getString(R.string.signed_in_as, session.email)
            render()
        }
    }

    private fun requestPasswordReset() {
        if (busy) return
        val email = emailInput.text.toString()
        runOperation(getString(R.string.sending_reset)) {
            withContext(Dispatchers.IO) { authentication.requestPasswordReset(email) }
            statusText = getString(R.string.password_reset_requested)
            render()
        }
    }

    private fun signInWithGoogle(webClientId: String) {
        if (busy) return
        runOperation(getString(R.string.google_signing_in)) {
            val token = GoogleSignIn(this).requestIdToken(webClientId)
            val session = withContext(Dispatchers.IO) {
                authentication.signInWithGoogle(token.idToken, token.nonce)
            }
            emailDraft = session.email
            statusText = getString(R.string.signed_in_as, session.email)
            render()
        }
    }

    private fun signOut() {
        if (busy) return
        runOperation(getString(R.string.signing_out)) {
            val remoteStatus = withContext(Dispatchers.IO) { authentication.signOut() }
            val providerStatus = try {
                GoogleSignIn(this).clearProviderState()
                null
            } catch (_: Exception) {
                getString(R.string.google_provider_signout_unconfirmed)
            }
            statusText = listOfNotNull(remoteStatus, providerStatus)
                .joinToString(" ")
                .ifBlank { getString(R.string.signed_out) }
            registrationMode = false
            render()
        }
    }

    private fun confirmLocalSignOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sign_out)
            .setMessage(R.string.confirm_local_sign_out)
            .setNegativeButton(R.string.cancel_label, null)
            .setPositiveButton(R.string.sign_out) { _, _ ->
                runOperation(getString(R.string.signing_out)) {
                    withContext(Dispatchers.IO) { authentication.clearLocalSession() }
                    statusText = getString(R.string.signed_out)
                    render()
                }
            }
            .show()
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_account)
            .setMessage(R.string.confirm_delete_account)
            .setNegativeButton(R.string.cancel_label, null)
            .setPositiveButton(R.string.delete_account) { _, _ ->
                runOperation(getString(R.string.deleting_account)) {
                    withContext(Dispatchers.IO) { authentication.deleteAccount() }
                    statusText = getString(R.string.account_deleted)
                    render()
                }
            }
            .show()
    }

    private fun runOperation(progress: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        statusText = progress
        render()
        screenScope.launch {
            try {
                operation()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalArgumentException) {
                statusText = exception.message ?: getString(R.string.invalid_input)
            } catch (exception: AivoApiException) {
                statusText = exception.message
            } catch (exception: GetCredentialException) {
                statusText = getString(R.string.google_signin_cancelled_or_failed)
            } catch (exception: GoogleCredentialUnavailableException) {
                statusText = getString(R.string.google_no_credential)
            } catch (_: Exception) {
                statusText = getString(R.string.account_operation_failed)
            } finally {
                busy = false
                if (!isFinishing) render()
            }
        }
    }

    private fun addInput(parent: LinearLayout, hintText: String, inputTypeValue: Int): EditText =
        EditText(this).apply {
            hint = hintText
            inputType = inputTypeValue
            textSize = 16f
            setTextColor(getColor(R.color.aivo_ink))
            setHintTextColor(getColor(R.color.aivo_hint))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(getColor(R.color.aivo_white), 12f, getColor(R.color.aivo_border))
            parent.addView(
                this,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) },
            )
        }

    private fun addInfo(parent: LinearLayout, message: String) {
        val card = LinearLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(getColor(R.color.aivo_white), 16f, getColor(R.color.aivo_border))
        }
        addText(card, message, 15f, R.color.aivo_muted)
        parent.addView(card, matchWidth())
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) {
        parent.addView(
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(getColor(color))
                typeface = Typeface.create(Typeface.DEFAULT, style)
            },
            matchWidth(),
        )
    }

    private fun addButton(parent: LinearLayout, label: String, destructive: Boolean = false, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(getColor(if (destructive) R.color.aivo_error else R.color.aivo_primary))
            background = roundedBackground(getColor(R.color.aivo_white), 14f, getColor(R.color.aivo_border))
            backgroundTintList = null
            isEnabled = !busy
            setOnClickListener { action() }
        }
        parent.addView(button, matchWidth().apply { topMargin = dp(12) })
    }

    private fun addVerticalSpace(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun matchWidth() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun roundedBackground(color: Int, radiusDp: Float, stroke: Int? = null) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
