package io.aivo.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
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
    private lateinit var voicePreferences: SharedPreferences
    private var currentConversation: StoredConversation? = null
    private var selectedAttachment: SelectedAttachment? = null
    private var generationJob: Job? = null
    private var requestCancellation: RequestCancellation? = null
    private var generationId = 0
    private var isGenerating = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionProvider: SpeechRecognitionProvider? = null
    private var recognitionGeneration = 0
    private var microphonePermissionRequestPending = false
    private var speechGeneration = 0
    private var activeUtteranceId: String? = null
    private var spokenText: String? = null
    private var speechResumeOffset = 0
    private var speechBaseOffset = 0
    private var voiceState = VoiceAssistantState()
    private var voiceIndicator: TextView? = null
    private var voiceInputButton: Button? = null
    private var voiceSettingsButton: Button? = null
    private var pauseSpeechButton: Button? = null
    private var resumeSpeechButton: Button? = null
    private var stopSpeechButton: Button? = null
    private var preferredLanguage = DEFAULT_LANGUAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = AivoApiClient(BackendConfiguration.proxyBaseUrl)
        authentication = AuthenticationRepository(api, EncryptedSessionStore(this))
        conversations = EncryptedConversationStore(this)
        voicePreferences = getSharedPreferences(VOICE_PREFERENCES, Context.MODE_PRIVATE)
        preferredLanguage = voicePreferences.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE)
            ?.takeIf { it in LANGUAGE_CODES }
            ?: DEFAULT_LANGUAGE
        tts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady && !isFinishing && !isDestroyed) {
                tts?.setOnUtteranceProgressListener(createSpeechProgressListener())
                applySpeechSettings()
            }
        }
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

    override fun onPause() {
        if (
            voiceState.mode == VoiceMode.STARTING ||
            voiceState.mode == VoiceMode.LISTENING ||
            voiceState.mode == VoiceMode.PROCESSING
        ) {
            cancelVoiceInput(showMessage = false)
        }
        if (voiceState.mode == VoiceMode.SPEAKING || voiceState.mode == VoiceMode.PAUSED) {
            stopSpeech(showMessage = false)
        }
        super.onPause()
    }

    override fun onStop() {
        if (voiceState.mode == VoiceMode.AWAITING_PERMISSION) {
            microphonePermissionRequestPending = false
            cancelVoiceInput(showMessage = false)
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentConversation?.id?.let { outState.putString(STATE_CONVERSATION_ID, it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android; used for the runtime microphone permission result.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (!microphonePermissionRequestPending) return
        microphonePermissionRequestPending = false
        if (voiceState.mode != VoiceMode.AWAITING_PERMISSION || isFinishing || isDestroyed) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        voiceState = VoiceAssistantTransitions.permissionResult(granted)
        refreshVoiceUi()
        if (granted) {
            startSpeechRecognition()
        } else {
            status.text = getString(
                if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    R.string.voice_permission_denied
                } else {
                    R.string.voice_permission_settings
                },
            )
        }
    }

    private fun releaseSpeechRecognizer(cancel: Boolean = false): Boolean {
        val recognizer = speechRecognizer ?: return false
        speechRecognizer = null
        var cancellationFailed = false
        if (cancel) {
            try {
                recognizer.cancel()
            } catch (_: IllegalStateException) {
                cancellationFailed = true
            }
        }
        recognizer.destroy()
        return cancellationFailed
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
        cancelVoiceInput(showMessage = false)
        releaseSpeechRecognizer()
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
                    val selectedLanguage = LANGUAGE_CODES.getOrElse(position) { DEFAULT_LANGUAGE }
                    if (preferredLanguage != selectedLanguage) {
                        preferredLanguage = selectedLanguage
                        editVoicePreferences { putString(KEY_LANGUAGE, selectedLanguage) }
                        if (voiceState.mode == VoiceMode.SPEAKING || voiceState.mode == VoiceMode.PAUSED) {
                            stopSpeech(showMessage = false)
                        }
                        if (ttsReady) applySpeechSettings()
                    }
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
        voiceIndicator = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.aivo_muted))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        composer.addView(voiceIndicator, matchWidth())
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
        val voiceActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        voiceInputButton = addButton(voiceActions, getString(R.string.voice_start)) { toggleVoiceInput() }
        voiceSettingsButton = addButton(voiceActions, getString(R.string.voice_settings)) { showVoiceSettings() }
        composer.addView(voiceActions)
        val speechActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pauseSpeechButton = addButton(speechActions, getString(R.string.voice_pause)) { pauseSpeech() }
        resumeSpeechButton = addButton(speechActions, getString(R.string.voice_resume)) { resumeSpeech() }
        stopSpeechButton = addButton(speechActions, getString(R.string.voice_stop)) { stopSpeech() }
        composer.addView(speechActions)
        parent.addView(composer)
        refreshVoiceUi()
    }

    private fun sendMessage() {
        if (isGenerating) return
        cancelVoiceInput(showMessage = false)
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
                    if (voicePreferences.getBoolean(KEY_AUTO_SPEAK, false)) {
                        speakText(answer.content)
                    }
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
        voiceInputButton?.isEnabled = !generating
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

    private fun toggleVoiceInput() {
        if (
            voiceState.mode == VoiceMode.AWAITING_PERMISSION ||
            voiceState.mode == VoiceMode.STARTING ||
            voiceState.mode == VoiceMode.LISTENING ||
            voiceState.mode == VoiceMode.PROCESSING
        ) {
            cancelVoiceInput()
        } else {
            beginVoiceInput()
        }
    }

    private fun beginVoiceInput() {
        if (isGenerating) {
            status.text = getString(R.string.voice_recognition_during_generation)
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.MICROPHONE_UNAVAILABLE)
            status.text = getString(R.string.voice_microphone_unavailable)
            refreshVoiceUi()
            return
        }

        val recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        val provider = SpeechPrivacyPolicy.provider(onDeviceAvailable)
        recognitionProvider = provider
        if (!recognitionAvailable) {
            voiceState = VoiceAssistantTransitions.requestListening(
                permissionGranted = false,
                recognitionAvailable = false,
            )
            status.text = getString(R.string.voice_input_unavailable)
            refreshVoiceUi()
            return
        }
        val consented = voicePreferences.getBoolean(KEY_EXTERNAL_RECOGNITION_CONSENT, false)
        if (!SpeechPrivacyPolicy.mayStart(provider, consented)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_external_consent_title)
                .setMessage(R.string.voice_external_consent_message)
                .setNegativeButton(R.string.cancel_label) { _, _ ->
                    status.text = getString(R.string.voice_consent_needed)
                }
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    editVoicePreferences { putBoolean(KEY_EXTERNAL_RECOGNITION_CONSENT, true) }
                    beginVoiceInput()
                }
                .show()
            return
        }

        if (voiceState.mode == VoiceMode.SPEAKING || voiceState.mode == VoiceMode.PAUSED) {
            stopSpeech(showMessage = false)
        }
        val permissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        voiceState = VoiceAssistantTransitions.requestListening(permissionGranted, recognitionAvailable)
        refreshVoiceUi()
        if (voiceState.mode == VoiceMode.AWAITING_PERMISSION) {
            status.text = getString(R.string.voice_permission_required)
            requestMicrophonePermission()
        } else {
            startSpeechRecognition()
        }
    }

    private fun requestMicrophonePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_start)
                .setMessage(R.string.voice_permission_rationale)
                .setNegativeButton(R.string.cancel_label) { _, _ ->
                    voiceState = VoiceAssistantTransitions.cancelListening(voiceState)
                    refreshVoiceUi()
                }
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    launchMicrophonePermissionRequest()
                }
                .show()
        } else {
            launchMicrophonePermissionRequest()
        }
    }

    private fun launchMicrophonePermissionRequest() {
        microphonePermissionRequestPending = true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    private fun startSpeechRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceState = VoiceAssistantTransitions.permissionResult(granted = false)
            status.text = getString(R.string.voice_permission_settings)
            refreshVoiceUi()
            return
        }
        val provider = recognitionProvider
        if (provider == null || !SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.RECOGNITION_UNAVAILABLE)
            status.text = getString(R.string.voice_input_unavailable)
            refreshVoiceUi()
            return
        }

        recognitionGeneration += 1
        val requestId = recognitionGeneration
        releaseSpeechRecognizer()
        try {
            speechRecognizer = when (provider) {
                SpeechRecognitionProvider.ON_DEVICE ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(this)
                    }
                SpeechRecognitionProvider.SYSTEM -> SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (_: RuntimeException) {
            speechRecognizer = null
            voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.RECOGNITION_UNAVAILABLE)
            status.text = getString(R.string.voice_input_unavailable)
            refreshVoiceUi()
            return
        }

        val recognizer = speechRecognizer ?: run {
            voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.RECOGNITION_UNAVAILABLE)
            status.text = getString(R.string.voice_input_unavailable)
            refreshVoiceUi()
            return
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isCurrentRecognition(requestId)) return
                voiceState = VoiceAssistantTransitions.recognitionStarted(voiceState)
                status.text = getString(R.string.voice_listening)
                refreshVoiceUi()
            }

            override fun onBeginningOfSpeech() {
                if (!isCurrentRecognition(requestId)) return
                voiceState = VoiceAssistantTransitions.recognitionStarted(voiceState)
                status.text = getString(R.string.voice_listening)
                refreshVoiceUi()
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!isCurrentRecognition(requestId)) return
                voiceState = VoiceAssistantTransitions.recognitionEnded(voiceState)
                status.text = getString(R.string.voice_processing)
                refreshVoiceUi()
            }

            override fun onError(error: Int) {
                if (!isCurrentRecognition(requestId)) return
                recognitionGeneration += 1
                releaseSpeechRecognizer()
                voiceState = VoiceAssistantTransitions.recognitionFailed(mapRecognitionError(error))
                status.text = getString(recognitionErrorMessage(voiceState.error))
                refreshVoiceUi()
            }

            override fun onResults(results: Bundle?) {
                if (!isCurrentRecognition(requestId)) return
                recognitionGeneration += 1
                releaseSpeechRecognizer()
                val transcriptText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                voiceState = VoiceAssistantTransitions.recognitionCompleted(transcriptText.isNotEmpty())
                if (transcriptText.isNotEmpty()) {
                    messageInput.setText(transcriptText)
                    messageInput.setSelection(messageInput.text.length)
                    status.text = getString(R.string.voice_transcript_ready)
                } else {
                    status.text = getString(R.string.voice_no_match)
                }
                refreshVoiceUi()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrentRecognition(requestId)) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf(String::isNotBlank)
                if (partial != null) status.text = getString(R.string.voice_partial_transcript, partial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        voiceState = VoiceAssistantTransitions.requestListening(
            permissionGranted = true,
            recognitionAvailable = true,
        )
        refreshVoiceUi()
        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLanguageTag(preferredLanguage))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionLanguageTag(preferredLanguage))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try {
            recognizer.startListening(recognitionIntent)
        } catch (_: RuntimeException) {
            if (isCurrentRecognition(requestId)) {
                recognitionGeneration += 1
                releaseSpeechRecognizer()
                voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.RECOGNITION_UNAVAILABLE)
                status.text = getString(R.string.voice_input_unavailable)
                refreshVoiceUi()
            }
        }
    }

    private fun isCurrentRecognition(requestId: Int): Boolean =
        requestId == recognitionGeneration && !isFinishing && !isDestroyed

    private fun cancelVoiceInput(showMessage: Boolean = true) {
        val active = voiceState.mode == VoiceMode.AWAITING_PERMISSION ||
            voiceState.mode == VoiceMode.STARTING ||
            voiceState.mode == VoiceMode.LISTENING ||
            voiceState.mode == VoiceMode.PROCESSING
        if (!active) return
        microphonePermissionRequestPending = false
        recognitionGeneration += 1
        val cancellationFailed = releaseSpeechRecognizer(cancel = true)
        voiceState = VoiceAssistantTransitions.cancelListening(voiceState)
        refreshVoiceUi()
        if (showMessage && ::status.isInitialized) {
            status.text = getString(if (cancellationFailed) R.string.voice_generic_error else R.string.voice_cancelled)
        }
    }

    private fun mapRecognitionError(error: Int): VoiceError = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO
        SpeechRecognizer.ERROR_CLIENT -> VoiceError.UNKNOWN
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.MICROPHONE_PERMISSION_DENIED
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError.NETWORK
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NO_MATCH
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.SERVICE_BUSY
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> VoiceError.LANGUAGE_UNAVAILABLE
        else -> VoiceError.UNKNOWN
    }

    private fun recognitionErrorMessage(error: VoiceError?): Int = when (error) {
        VoiceError.MICROPHONE_PERMISSION_DENIED -> R.string.voice_permission_denied
        VoiceError.MICROPHONE_UNAVAILABLE -> R.string.voice_microphone_unavailable
        VoiceError.RECOGNITION_UNAVAILABLE -> R.string.voice_input_unavailable
        VoiceError.NO_MATCH -> R.string.voice_no_match
        VoiceError.NETWORK -> R.string.voice_network_error
        VoiceError.SERVICE_BUSY -> R.string.voice_service_busy
        VoiceError.AUDIO -> R.string.voice_audio_error
        VoiceError.LANGUAGE_UNAVAILABLE -> R.string.voice_language_error
        VoiceError.SPEECH_OUTPUT_FAILED, VoiceError.UNKNOWN, null -> R.string.voice_generic_error
    }

    private fun refreshVoiceUi() {
        val inputActive = voiceState.mode == VoiceMode.AWAITING_PERMISSION ||
            voiceState.mode == VoiceMode.STARTING ||
            voiceState.mode == VoiceMode.LISTENING ||
            voiceState.mode == VoiceMode.PROCESSING
        voiceInputButton?.text = getString(if (inputActive) R.string.voice_cancel_input else R.string.voice_start)
        voiceIndicator?.text = getString(
            when (voiceState.mode) {
                VoiceMode.AWAITING_PERMISSION -> R.string.voice_status_permission
                VoiceMode.STARTING, VoiceMode.LISTENING -> R.string.voice_status_listening
                VoiceMode.PROCESSING -> R.string.voice_status_processing
                VoiceMode.SPEAKING -> R.string.voice_output_speaking
                VoiceMode.PAUSED -> R.string.voice_output_paused
                VoiceMode.ERROR -> R.string.voice_status_unavailable
                VoiceMode.IDLE -> R.string.voice_status_ready
            },
        )
        pauseSpeechButton?.visibility = if (voiceState.mode == VoiceMode.SPEAKING) View.VISIBLE else View.GONE
        resumeSpeechButton?.visibility = if (voiceState.mode == VoiceMode.PAUSED) View.VISIBLE else View.GONE
        stopSpeechButton?.visibility =
            if (voiceState.mode == VoiceMode.SPEAKING || voiceState.mode == VoiceMode.PAUSED) View.VISIBLE else View.GONE
    }

    private fun speakMessage(message: StoredChatMessage) {
        speakText(message.content)
    }

    private fun speakText(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) {
            status.text = getString(R.string.tts_unavailable)
            return
        }
        if (text.isBlank()) return
        if (!voicePreferences.getBoolean(KEY_TTS_DISCLOSURE_ACCEPTED, false)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.voice_tts_consent_title)
                .setMessage(R.string.voice_tts_consent_message)
                .setNegativeButton(R.string.cancel_label, null)
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    editVoicePreferences { putBoolean(KEY_TTS_DISCLOSURE_ACCEPTED, true) }
                    speakText(text)
                }
                .show()
            return
        }
        cancelVoiceInput(showMessage = false)
        stopSpeech(showMessage = false)
        spokenText = text
        speechResumeOffset = 0
        startSpeechSegment(0)
    }

    private fun startSpeechSegment(offset: Int) {
        val engine = tts
        val text = spokenText
        if (!ttsReady || engine == null || text == null) {
            status.text = getString(R.string.tts_unavailable)
            voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.SPEECH_OUTPUT_FAILED)
            refreshVoiceUi()
            return
        }
        val boundedOffset = offset.coerceIn(0, text.length)
        if (boundedOffset >= text.length) {
            voiceState = VoiceAssistantTransitions.stopPlayback(voiceState)
            spokenText = null
            speechResumeOffset = 0
            refreshVoiceUi()
            status.text = getString(R.string.voice_speech_finished)
            return
        }
        if (!applySpeechSettings()) {
            voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.LANGUAGE_UNAVAILABLE)
            status.text = getString(R.string.tts_language_unavailable)
            refreshVoiceUi()
            return
        }

        speechGeneration += 1
        val utteranceId = "aivo-speech-$speechGeneration"
        activeUtteranceId = utteranceId
        speechBaseOffset = boundedOffset
        speechResumeOffset = boundedOffset
        voiceState = VoiceAssistantTransitions.startPlayback()
        refreshVoiceUi()
        val result = engine.speak(
            text.substring(boundedOffset),
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (result == TextToSpeech.ERROR && activeUtteranceId == utteranceId) {
            activeUtteranceId = null
            voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.SPEECH_OUTPUT_FAILED)
            status.text = getString(R.string.voice_output_error)
            refreshVoiceUi()
        } else {
            status.text = getString(R.string.tts_speaking)
        }
    }

    private fun pauseSpeech() {
        if (voiceState.mode != VoiceMode.SPEAKING) {
            status.text = getString(R.string.voice_not_speaking)
            return
        }
        speechGeneration += 1
        activeUtteranceId = null
        tts?.stop()
        voiceState = VoiceAssistantTransitions.pausePlayback(voiceState)
        refreshVoiceUi()
        status.text = getString(R.string.voice_speech_paused)
    }

    private fun resumeSpeech() {
        if (voiceState.mode != VoiceMode.PAUSED) {
            status.text = getString(R.string.voice_not_speaking)
            return
        }
        startSpeechSegment(speechResumeOffset)
    }

    private fun stopSpeech(showMessage: Boolean = true) {
        val active = voiceState.mode == VoiceMode.SPEAKING || voiceState.mode == VoiceMode.PAUSED
        if (!active && spokenText == null) return
        speechGeneration += 1
        activeUtteranceId = null
        tts?.stop()
        spokenText = null
        speechResumeOffset = 0
        voiceState = VoiceAssistantTransitions.stopPlayback(voiceState)
        refreshVoiceUi()
        if (showMessage && ::status.isInitialized) status.text = getString(R.string.voice_speech_stopped)
    }

    private fun createSpeechProgressListener(): UtteranceProgressListener =
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                runOnUiThread {
                    if (utteranceId == activeUtteranceId && !isFinishing) {
                        voiceState = VoiceAssistantTransitions.startPlayback()
                        refreshVoiceUi()
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                runOnUiThread {
                    if (utteranceId == activeUtteranceId && !isFinishing) {
                        speechResumeOffset = spokenText?.length ?: 0
                        activeUtteranceId = null
                        spokenText = null
                        speechResumeOffset = 0
                        voiceState = VoiceAssistantTransitions.stopPlayback(voiceState)
                        refreshVoiceUi()
                        status.text = getString(R.string.voice_speech_finished)
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                reportSpeechError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                reportSpeechError(utteranceId)
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != activeUtteranceId) return
                runOnUiThread {
                    if (utteranceId == activeUtteranceId && !isFinishing) {
                        speechResumeOffset = (speechBaseOffset + start).coerceAtMost(spokenText?.length ?: 0)
                    }
                }
            }
        }

    private fun reportSpeechError(utteranceId: String?) {
        if (utteranceId != activeUtteranceId) return
        runOnUiThread {
            if (utteranceId == activeUtteranceId && !isFinishing) {
                activeUtteranceId = null
                spokenText = null
                voiceState = VoiceAssistantTransitions.recognitionFailed(VoiceError.SPEECH_OUTPUT_FAILED)
                refreshVoiceUi()
                status.text = getString(R.string.voice_output_error)
            }
        }
    }

    private fun applySpeechSettings(): Boolean {
        val engine = tts ?: return false
        val locale = Locale.forLanguageTag(ttsLanguageTag(preferredLanguage))
        val languageResult = engine.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return false
        }
        val savedVoice = voicePreferences.getString(KEY_OUTPUT_VOICE, null)
        val selectedVoice = engine.voices
            ?.firstOrNull { it.name == savedVoice && it.locale.language == locale.language }
        if (selectedVoice != null) engine.voice = selectedVoice
        engine.setSpeechRate(
            voicePreferences.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
                .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE),
        )
        return true
    }

    private fun showVoiceSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val privacyNote = TextView(this).apply {
            text = getString(R.string.voice_settings_privacy_note)
            textSize = 14f
            setTextColor(getColor(R.color.aivo_muted))
        }
        content.addView(privacyNote, matchWidth())

        val autoSpeak = CheckBox(this).apply {
            text = getString(R.string.voice_auto_speak)
            isChecked = voicePreferences.getBoolean(KEY_AUTO_SPEAK, false)
        }
        content.addView(autoSpeak, matchWidth())

        val externalConsent = CheckBox(this).apply {
            text = getString(R.string.voice_allow_external_processing)
            isChecked = voicePreferences.getBoolean(KEY_EXTERNAL_RECOGNITION_CONSENT, false)
        }
        content.addView(externalConsent, matchWidth())

        val voiceLabel = TextView(this).apply {
            text = getString(R.string.voice_select_output)
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(voiceLabel, matchWidth())
        val voiceChoices = availableVoiceChoices()
        val voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ChatActivity,
                android.R.layout.simple_spinner_dropdown_item,
                voiceChoices,
            )
        }
        val currentVoice = voicePreferences.getString(KEY_OUTPUT_VOICE, null)
        voiceSpinner.setSelection(voiceChoices.indexOfFirst { it.voiceName == currentVoice }.coerceAtLeast(0))
        content.addView(voiceSpinner, matchWidth())

        val initialRate = voicePreferences.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
            .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        val rateLabel = TextView(this).apply {
            text = getString(R.string.voice_rate_value, getString(R.string.voice_rate_label), initialRate)
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(rateLabel, matchWidth())
        val rateControl = SeekBar(this).apply {
            max = SPEECH_RATE_STEPS
            progress = ((initialRate - MIN_SPEECH_RATE) / (MAX_SPEECH_RATE - MIN_SPEECH_RATE) * SPEECH_RATE_STEPS)
                .toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val rate = MIN_SPEECH_RATE +
                        (MAX_SPEECH_RATE - MIN_SPEECH_RATE) * progress / SPEECH_RATE_STEPS
                    rateLabel.text =
                        getString(R.string.voice_rate_value, getString(R.string.voice_rate_label), rate)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        content.addView(rateControl, matchWidth())

        val settingsScroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_settings_title)
            .setView(settingsScroll)
            .setNegativeButton(R.string.cancel_label, null)
            .setPositiveButton(R.string.save_label) { _, _ ->
                val rate = MIN_SPEECH_RATE +
                    (MAX_SPEECH_RATE - MIN_SPEECH_RATE) * rateControl.progress / SPEECH_RATE_STEPS
                val selectedVoice = voiceChoices.getOrNull(voiceSpinner.selectedItemPosition)
                editVoicePreferences {
                    putBoolean(KEY_AUTO_SPEAK, autoSpeak.isChecked)
                    if (autoSpeak.isChecked) putBoolean(KEY_TTS_DISCLOSURE_ACCEPTED, true)
                    putBoolean(KEY_EXTERNAL_RECOGNITION_CONSENT, externalConsent.isChecked)
                    putString(KEY_OUTPUT_VOICE, selectedVoice?.voiceName)
                    putFloat(KEY_SPEECH_RATE, rate)
                }
                if (
                    !externalConsent.isChecked &&
                    recognitionProvider == SpeechRecognitionProvider.SYSTEM
                ) {
                    cancelVoiceInput(showMessage = false)
                }
                if (voiceState.mode == VoiceMode.SPEAKING || voiceState.mode == VoiceMode.PAUSED) {
                    stopSpeech(showMessage = false)
                }
                if (ttsReady) applySpeechSettings()
                status.text = getString(R.string.voice_settings_saved)
            }
            .show()
    }

    private fun availableVoiceChoices(): List<VoiceChoice> {
        val language = Locale.forLanguageTag(ttsLanguageTag(preferredLanguage)).language
        val localVoices = (if (ttsReady) tts?.voices.orEmpty() else emptyList<Voice>())
            .filter { it.locale.language == language && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
            .map {
                VoiceChoice(
                    getString(R.string.voice_choice_label, it.name, it.locale.displayName),
                    it.name,
                )
            }
        return listOf(VoiceChoice(getString(R.string.voice_default_output), null)) + localVoices
    }

    private fun editVoicePreferences(update: SharedPreferences.Editor.() -> Unit) {
        val editor = voicePreferences.edit()
        editor.update()
        editor.apply()
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

    private data class VoiceChoice(val label: String, val voiceName: String?) {
        override fun toString(): String = label
    }

    private object GsonHolder {
        val gson = com.google.gson.Gson()
    }

    private companion object {
        const val STATE_CONVERSATION_ID = "current_conversation_id"
        const val REQUEST_PICK_ATTACHMENT = 4201
        const val REQUEST_RECORD_AUDIO = 4202
        const val DEFAULT_LANGUAGE = "en"
        const val MAX_ATTACHMENT_BYTES = 4 * 1024 * 1024
        const val MAX_ASSISTANT_RESPONSE_LENGTH = 1_000_000
        const val READ_BUFFER_BYTES = 8 * 1024
        const val VOICE_PREFERENCES = "aivo_voice_settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_EXTERNAL_RECOGNITION_CONSENT = "external_recognition_consent"
        const val KEY_TTS_DISCLOSURE_ACCEPTED = "tts_disclosure_accepted"
        const val KEY_AUTO_SPEAK = "auto_speak_responses"
        const val KEY_OUTPUT_VOICE = "output_voice"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val MIN_SPEECH_RATE = 0.75f
        const val MAX_SPEECH_RATE = 1.5f
        const val SPEECH_RATE_STEPS = 100
        val LANGUAGE_CODES = listOf("en", "hi", "ur")
        val ALLOWED_ATTACHMENT_TYPES = arrayOf("application/pdf", "text/plain")

        fun recognitionLanguageTag(language: String): String = when (language) {
            "hi" -> "hi-IN"
            "ur" -> "ur-PK"
            else -> "en-US"
        }

        fun ttsLanguageTag(language: String): String = recognitionLanguageTag(language)
    }
}
