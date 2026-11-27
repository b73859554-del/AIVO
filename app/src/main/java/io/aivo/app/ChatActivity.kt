package io.aivo.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.Locale

class ChatActivity : Activity() {
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var api: AivoApiClient
    private lateinit var authentication: AuthenticationRepository
    private lateinit var conversations: ConversationStore
    private lateinit var transcript: ListView
    private lateinit var messageInput: EditText
    private lateinit var status: TextView
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var attachmentButton: Button
    private lateinit var languageSpinner: Spinner
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var currentTitleView: TextView
    private var currentConversation: StoredConversation? = null
    private var selectedAttachment: SelectedAttachment? = null
    private var generationJob: Job? = null
    private var requestCancellation: RequestCancellation? = null
    private var generationId = 0
    private var isGenerating = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var preferredLanguage = DEFAULT_LANGUAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = AivoApiClient(BackendConfiguration.proxyBaseUrl)
        authentication = AuthenticationRepository(api, EncryptedSessionStore(this))
        conversations = EncryptedConversationStore(this)
        tts = TextToSpeech(this) { result -> ttsReady = result == TextToSpeech.SUCCESS }
        try {
            currentConversation = savedInstanceState?.getString(STATE_CONVERSATION_ID)
                ?.let(conversations::find)
                ?: conversations.list().firstOrNull()
                ?: conversations.create()
        } catch (_: Exception) {
            currentConversation = null
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentConversation?.id?.let { outState.putString(STATE_CONVERSATION_ID, it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android; used to receive the system document picker result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_ATTACHMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedAttachment = null
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            status.text = getString(R.string.attachment_permission_error)
            return
        }
        selectedAttachment = runCatching { selectedAttachment(uri) }.getOrElse { exception ->
            status.text = exception.message ?: getString(R.string.attachment_error)
            null
        }
        status.text = selectedAttachment?.let { getString(R.string.attachment_selected, it.name) }
            ?: getString(R.string.attachment_error)
    }

    override fun onDestroy() {
        stopActiveRequest()
        screenScope.cancel()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.aivo_canvas))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        addButton(header, getString(R.string.back_label)) { finish() }
        addButton(header, getString(R.string.history_label)) { showHistory() }
        addButton(header, getString(R.string.new_conversation)) { createConversation() }
        root.addView(header)

        currentTitleView = TextView(this).apply {
            text = currentConversation?.title ?: getString(R.string.conversation_unavailable)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.aivo_ink))
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        root.addView(currentTitleView)

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.aivo_muted))
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        root.addView(status, matchWidth())

        languageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter.createFromResource(
                this@ChatActivity,
                R.array.chat_languages,
                android.R.layout.simple_spinner_dropdown_item,
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    preferredLanguage = LANGUAGE_CODES.getOrElse(position) { DEFAULT_LANGUAGE }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        languageSpinner.setSelection(LANGUAGE_CODES.indexOf(preferredLanguage).coerceAtLeast(0))

        transcript = ListView(this).apply {
            divider = null
            setBackgroundColor(getColor(R.color.aivo_canvas))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            clipToPadding = false
        }
        messageAdapter = MessageAdapter()
        transcript.adapter = messageAdapter
        root.addView(transcript, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val sessionAvailable = try {
            authentication.currentSession() != null
        } catch (_: Exception) {
            false
        }
        val backendAvailable = BackendConfiguration.proxyBaseUrl != null
        if (backendAvailable && sessionAvailable && currentConversation != null) {
            renderComposer(root)
            status.text = getString(R.string.chat_ready)
        } else {
            renderBlockedState(root, backendAvailable, sessionAvailable)
        }
        if (isGenerating) {
            setGenerationUi(true)
        } else {
            messageAdapter.notifyDataSetChanged()
            transcript.post { transcript.setSelection(messageAdapter.count.coerceAtLeast(1) - 1) }
        }
        setContentView(root)
    }

    private fun renderBlockedState(parent: LinearLayout, backendAvailable: Boolean, sessionAvailable: Boolean) {
        val blockedMessage = when {
            !backendAvailable -> getString(R.string.chat_backend_unavailable)
            !sessionAvailable -> getString(R.string.chat_signin_required)
            else -> getString(R.string.chat_conversation_unavailable)
        }
        status.text = blockedMessage
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        addButton(actions, getString(R.string.account_title)) {
            startActivity(Intent(this, AccountActivity::class.java))
        }
        parent.addView(actions)
    }

    private fun renderComposer(parent: LinearLayout) {
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
            setBackgroundColor(getColor(R.color.aivo_white))
        }
        composer.addView(
            languageSpinner,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)),
        )
        messageInput = EditText(this).apply {
            hint = getString(R.string.chat_message_hint)
            minLines = 1
            maxLines = 5
            textSize = 16f
            setTextColor(getColor(R.color.aivo_ink))
            setHintTextColor(getColor(R.color.aivo_hint))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(getColor(R.color.aivo_canvas), 12f, getColor(R.color.aivo_border))
        }
        composer.addView(messageInput, matchWidth())
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        attachmentButton = addButton(actions, getString(R.string.attach_file)) { chooseAttachment(false) }
        addButton(actions, getString(R.string.attach_image)) { chooseAttachment(true) }
        sendButton = addButton(actions, getString(R.string.send_message)) { sendMessage() }
        stopButton = addButton(actions, getString(R.string.stop_generation)) { stopGeneration() }
        stopButton.visibility = View.GONE
        composer.addView(actions)
        parent.addView(composer)
    }

    private fun sendMessage() {
        if (isGenerating) return
        val content = messageInput.text.toString().trim()
        if (content.isEmpty()) {
            status.text = getString(R.string.chat_empty_message)
            return
        }
        val conversation = currentConversation ?: run {
            status.text = getString(R.string.chat_conversation_unavailable)
            return
        }
        val attachment = selectedAttachment
        hideKeyboard()
        val storedUserMessage = try {
            conversations.append(
                conversation.id,
                StoredChatMessage(
                    role = "user",
                    content = content,
                    attachmentNames = listOfNotNull(attachment?.name),
                ),
            )
        } catch (exception: Exception) {
            status.text = exception.message ?: getString(R.string.chat_save_error)
            return
        }
        currentConversation = storedUserMessage
        currentTitleView.text = storedUserMessage.title
        messageInput.text?.clear()
        selectedAttachment = null
        startGeneration(storedUserMessage, attachment)
    }

    private fun startGeneration(conversation: StoredConversation, attachment: SelectedAttachment?) {
        isGenerating = true
        generationId += 1
        val currentGeneration = generationId
        val cancellation = RequestCancellation()
        requestCancellation = cancellation
        status.text = getString(R.string.chat_generating)
        setGenerationUi(true)
        generationJob = screenScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val token = authentication.validAccessToken()
                    val attachments = listOfNotNull(attachment?.let(::encodeAttachment))
                    val request = ChatCompletionRequest(
                        conversationId = conversation.id,
                        messages = conversation.messages.map { ChatTurn(it.role, it.content) },
                        attachments = attachments,
                        preferredLanguage = preferredLanguage,
                    )
                    val apiResponse = api.execute(
                        AivoEndpoint.CHAT_COMPLETIONS,
                        request,
                        token,
                        cancellation,
                    )
                    GsonHolder.gson.fromJson(apiResponse.body, ChatCompletionResponse::class.java)
                        ?: throw AivoApiException(null, "AIVO returned an empty chat response.")
                }
                val answer = response.message
                if (answer?.role != "assistant" || answer.content.isBlank()) {
                    throw AivoApiException(null, "AIVO returned an invalid assistant response.")
                }
                if (answer.content.length > MAX_ASSISTANT_RESPONSE_LENGTH) {
                    throw AivoApiException(null, "AIVO response exceeded the supported message size.")
                }
                if (generationId == currentGeneration) {
                    currentConversation = conversations.append(
                        conversation.id,
                        StoredChatMessage(role = "assistant", content = answer.content),
                    )
                    currentTitleView.text = currentConversation?.title
                    status.text = getString(R.string.chat_ready)
                }
            } catch (exception: CancellationException) {
                if (generationId == currentGeneration && !isFinishing) {
                    status.text = getString(R.string.chat_generation_stopped)
                }
                throw exception
            } catch (exception: AivoApiException) {
                if (generationId == currentGeneration && exception.statusCode == 401) {
                    try {
                        withContext(Dispatchers.IO) { authentication.clearLocalSession() }
                        status.text = getString(R.string.chat_signin_required)
                        render()
                    } catch (_: Exception) {
                        status.text = getString(R.string.chat_session_clear_error)
                    }
                } else if (generationId == currentGeneration) {
                    status.text = exception.message
                }
            } catch (exception: IOException) {
                if (generationId == currentGeneration) status.text = getString(R.string.chat_network_error)
            } catch (exception: IllegalArgumentException) {
                if (generationId == currentGeneration) {
                    status.text = exception.message ?: getString(R.string.chat_response_error)
                }
            } catch (exception: Exception) {
                if (generationId == currentGeneration) status.text = getString(R.string.chat_response_error)
            } finally {
                if (generationId == currentGeneration) {
                    isGenerating = false
                    requestCancellation = null
                    setGenerationUi(false)
                }
            }
        }
    }

    private fun stopGeneration() {
        if (!isGenerating) return
        generationId += 1
        stopActiveRequest()
        isGenerating = false
        status.text = getString(R.string.chat_generation_stopped)
        setGenerationUi(false)
    }

    private fun stopActiveRequest() {
        requestCancellation?.cancel()
        requestCancellation = null
        generationJob?.cancel()
        generationJob = null
    }

    private fun setGenerationUi(generating: Boolean) {
        if (!::sendButton.isInitialized) return
        sendButton.isEnabled = !generating
        attachmentButton.isEnabled = !generating
        stopButton.visibility = if (generating) View.VISIBLE else View.GONE
        if (::messageAdapter.isInitialized) {
            messageAdapter.notifyDataSetChanged()
            if (messageAdapter.count > 0) transcript.setSelection(messageAdapter.count - 1)
        }
    }

    private fun chooseAttachment(imagesOnly: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (imagesOnly) "image/*" else "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, if (imagesOnly) arrayOf("image/*") else ALLOWED_ATTACHMENT_TYPES)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_ATTACHMENT)
        } catch (_: android.content.ActivityNotFoundException) {
            status.text = getString(R.string.file_picker_unavailable)
        }
    }

    private fun selectedAttachment(uri: Uri): SelectedAttachment {
        val mimeType = contentResolver.getType(uri)?.lowercase(Locale.ROOT) ?: "application/octet-stream"
        require(mimeType.startsWith("image/") || mimeType in ALLOWED_ATTACHMENT_TYPES) {
            getString(R.string.unsupported_attachment_type)
        }
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.attachment_fallback_name)
        return SelectedAttachment(uri, name, mimeType)
    }

    private fun encodeAttachment(attachment: SelectedAttachment): ChatAttachment {
        val bytes = contentResolver.openInputStream(attachment.uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_ATTACHMENT_BYTES) {
                    getString(R.string.attachment_too_large)
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IOException(getString(R.string.attachment_unavailable))
        return ChatAttachment(
            name = attachment.name,
            mimeType = attachment.mimeType,
            data = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun showHistory() {
        val items = try {
            conversations.list()
        } catch (_: Exception) {
            status.text = getString(R.string.chat_history_read_error)
            return
        }
        if (items.isEmpty()) {
            status.text = getString(R.string.chat_history_empty)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.history_label)
            .setItems(items.map { it.title }.toTypedArray()) { _, index ->
                currentConversation = items[index]
                status.text = getString(R.string.chat_history_opened)
                render()
            }
            .setNeutralButton(R.string.manage_current_conversation) { _, _ -> showManageCurrent() }
            .setNegativeButton(R.string.cancel_label, null)
            .show()
    }

    private fun showManageCurrent() {
        val conversation = currentConversation ?: return
        AlertDialog.Builder(this)
            .setTitle(conversation.title)
            .setItems(
                arrayOf(getString(R.string.rename_conversation), getString(R.string.delete_conversation)),
            ) { _, index ->
                if (index == 0) showRenameDialog(conversation) else confirmDelete(conversation)
            }
            .setNegativeButton(R.string.cancel_label, null)
            .show()
    }

    private fun showRenameDialog(conversation: StoredConversation) {
        val input = EditText(this).apply {
            setText(conversation.title)
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(ConversationRules.MAX_TITLE_LENGTH))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_conversation)
            .setView(input)
            .setNegativeButton(R.string.cancel_label, null)
            .setPositiveButton(R.string.save_label) { _, _ ->
                try {
                    currentConversation = conversations.rename(conversation.id, input.text.toString())
                    render()
                } catch (exception: IllegalArgumentException) {
                    status.text = exception.message
                } catch (_: Exception) {
                    status.text = getString(R.string.chat_save_error)
                }
            }
            .show()
    }

    private fun confirmDelete(conversation: StoredConversation) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_conversation)
            .setMessage(R.string.confirm_delete_conversation)
            .setNegativeButton(R.string.cancel_label, null)
            .setPositiveButton(R.string.delete_conversation) { _, _ ->
                try {
                    conversations.delete(conversation.id)
                    currentConversation = conversations.list().firstOrNull() ?: conversations.create()
                    status.text = getString(R.string.conversation_deleted)
                    render()
                } catch (_: Exception) {
                    status.text = getString(R.string.chat_save_error)
                }
            }
            .show()
    }

    private fun createConversation() {
        if (isGenerating) {
            status.text = getString(R.string.stop_before_new_conversation)
            return
        }
        try {
            currentConversation = conversations.create()
            status.text = getString(R.string.new_conversation_created)
            render()
        } catch (_: Exception) {
            status.text = getString(R.string.chat_save_error)
        }
    }

    private fun copyMessage(message: StoredChatMessage) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), message.content))
        status.text = getString(R.string.message_copied)
    }

    private fun speakMessage(message: StoredChatMessage) {
        if (!ttsReady) {
            status.text = getString(R.string.tts_unavailable)
            return
        }
        val language = Locale.forLanguageTag(preferredLanguage)
        val result = tts?.setLanguage(language) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.text = getString(R.string.tts_language_unavailable)
            return
        }
        tts?.speak(message.content, TextToSpeech.QUEUE_FLUSH, null, message.id)
        status.text = getString(R.string.tts_speaking)
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.windowToken?.let { manager.hideSoftInputFromWindow(it, 0) }
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(getColor(R.color.aivo_primary))
            background = roundedBackground(getColor(R.color.aivo_white), 12f, getColor(R.color.aivo_border))
            backgroundTintList = null
            setOnClickListener { action() }
            parent.addView(
                this,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(4) },
            )
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

    private inner class MessageAdapter : BaseAdapter() {
        private val messages get() = currentConversation?.messages.orEmpty()
        override fun getCount(): Int = messages.size
        override fun getItem(position: Int): StoredChatMessage = messages[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val message = getItem(position)
            val row = convertView as? LinearLayout ?: createMessageRow()
            val views = row.tag as MessageRowViews
            views.role.text = if (message.role == "user") getString(R.string.you_label) else getString(R.string.aivo_label)
            views.content.text = message.content
            views.attachment.visibility = if (message.attachmentNames.isEmpty()) View.GONE else View.VISIBLE
            if (message.attachmentNames.isNotEmpty()) {
                views.attachment.text = getString(R.string.attachment_names, message.attachmentNames.joinToString())
            }
            row.background = roundedBackground(
                getColor(if (message.role == "user") R.color.aivo_chat_user else R.color.aivo_white),
                16f,
                getColor(R.color.aivo_border),
            )
            views.copy.setOnClickListener { copyMessage(message) }
            views.speak.setOnClickListener { speakMessage(message) }
            return row
        }

        private fun createMessageRow(): LinearLayout {
            val row = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val role = TextView(this@ChatActivity).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.aivo_primary))
            }
            val content = TextView(this@ChatActivity).apply {
                textSize = 16f
                setTextColor(getColor(R.color.aivo_ink))
                setTextIsSelectable(true)
                setPadding(0, dp(6), 0, 0)
            }
            val attachment = TextView(this@ChatActivity).apply {
                textSize = 12f
                setTextColor(getColor(R.color.aivo_muted))
            }
            val actions = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val copy = addButton(actions, getString(R.string.copy_message)) {}
            val speak = addButton(actions, getString(R.string.read_aloud)) {}
            row.addView(role, matchWidth())
            row.addView(content, matchWidth())
            row.addView(attachment, matchWidth())
            row.addView(actions)
            row.tag = MessageRowViews(role, content, attachment, copy, speak)
            return row
        }
    }

    private data class MessageRowViews(
        val role: TextView,
        val content: TextView,
        val attachment: TextView,
        val copy: Button,
        val speak: Button,
    )

    private data class SelectedAttachment(val uri: Uri, val name: String, val mimeType: String)

    private object GsonHolder {
        val gson = com.google.gson.Gson()
    }

    private companion object {
        const val STATE_CONVERSATION_ID = "current_conversation_id"
        const val REQUEST_PICK_ATTACHMENT = 4201
        const val DEFAULT_LANGUAGE = "en"
        const val MAX_ATTACHMENT_BYTES = 4 * 1024 * 1024
        const val MAX_ASSISTANT_RESPONSE_LENGTH = 1_000_000
        const val READ_BUFFER_BYTES = 8 * 1024
        val LANGUAGE_CODES = listOf("en", "hi", "ur")
        val ALLOWED_ATTACHMENT_TYPES = arrayOf("application/pdf", "text/plain")
    }
}
