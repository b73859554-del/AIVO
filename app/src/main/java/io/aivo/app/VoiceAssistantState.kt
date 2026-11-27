package io.aivo.app

enum class VoiceMode {
    IDLE,
    AWAITING_PERMISSION,
    STARTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    PAUSED,
    ERROR,
}

enum class VoiceError {
    MICROPHONE_PERMISSION_DENIED,
    MICROPHONE_UNAVAILABLE,
    RECOGNITION_UNAVAILABLE,
    NO_MATCH,
    NETWORK,
    SERVICE_BUSY,
    AUDIO,
    LANGUAGE_UNAVAILABLE,
    SPEECH_OUTPUT_FAILED,
    UNKNOWN,
}

data class VoiceAssistantState(
    val mode: VoiceMode = VoiceMode.IDLE,
    val error: VoiceError? = null,
)

enum class SpeechRecognitionProvider {
    ON_DEVICE,
    SYSTEM,
}

object SpeechPrivacyPolicy {
    fun provider(onDeviceRecognitionAvailable: Boolean): SpeechRecognitionProvider =
        if (onDeviceRecognitionAvailable) {
            SpeechRecognitionProvider.ON_DEVICE
        } else {
            SpeechRecognitionProvider.SYSTEM
        }

    fun mayStart(provider: SpeechRecognitionProvider, externalProcessingConsented: Boolean): Boolean =
        provider == SpeechRecognitionProvider.ON_DEVICE || externalProcessingConsented
}

object VoiceAssistantTransitions {
    fun requestListening(permissionGranted: Boolean, recognitionAvailable: Boolean): VoiceAssistantState =
        when {
            !recognitionAvailable -> VoiceAssistantState(VoiceMode.ERROR, VoiceError.RECOGNITION_UNAVAILABLE)
            !permissionGranted -> VoiceAssistantState(VoiceMode.AWAITING_PERMISSION)
            else -> VoiceAssistantState(VoiceMode.STARTING)
        }

    fun permissionResult(granted: Boolean): VoiceAssistantState =
        if (granted) {
            VoiceAssistantState(VoiceMode.STARTING)
        } else {
            VoiceAssistantState(VoiceMode.ERROR, VoiceError.MICROPHONE_PERMISSION_DENIED)
        }

    fun recognitionStarted(state: VoiceAssistantState): VoiceAssistantState =
        if (state.mode == VoiceMode.STARTING) VoiceAssistantState(VoiceMode.LISTENING) else state

    fun recognitionEnded(state: VoiceAssistantState): VoiceAssistantState =
        if (state.mode == VoiceMode.LISTENING) VoiceAssistantState(VoiceMode.PROCESSING) else state

    fun recognitionCompleted(hasTranscript: Boolean): VoiceAssistantState =
        if (hasTranscript) {
            VoiceAssistantState()
        } else {
            VoiceAssistantState(VoiceMode.ERROR, VoiceError.NO_MATCH)
        }

    fun recognitionFailed(error: VoiceError): VoiceAssistantState =
        VoiceAssistantState(VoiceMode.ERROR, error)

    fun cancelListening(state: VoiceAssistantState): VoiceAssistantState =
        if (
            state.mode == VoiceMode.AWAITING_PERMISSION ||
            state.mode == VoiceMode.STARTING ||
            state.mode == VoiceMode.LISTENING ||
            state.mode == VoiceMode.PROCESSING
        ) {
            VoiceAssistantState()
        } else {
            state
        }

    fun startPlayback(): VoiceAssistantState = VoiceAssistantState(VoiceMode.SPEAKING)

    fun pausePlayback(state: VoiceAssistantState): VoiceAssistantState =
        if (state.mode == VoiceMode.SPEAKING) VoiceAssistantState(VoiceMode.PAUSED) else state

    fun resumePlayback(state: VoiceAssistantState): VoiceAssistantState =
        if (state.mode == VoiceMode.PAUSED) VoiceAssistantState(VoiceMode.SPEAKING) else state

    fun stopPlayback(state: VoiceAssistantState): VoiceAssistantState =
        if (state.mode == VoiceMode.SPEAKING || state.mode == VoiceMode.PAUSED) {
            VoiceAssistantState()
        } else {
            state
        }
}
