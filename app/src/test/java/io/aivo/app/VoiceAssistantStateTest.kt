package io.aivo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistantStateTest {
    @Test
    fun microphonePermissionAndRecognitionAvailabilityAreRequired() {
        assertEquals(
            VoiceAssistantState(VoiceMode.AWAITING_PERMISSION),
            VoiceAssistantTransitions.requestListening(permissionGranted = false, recognitionAvailable = true),
        )
        assertEquals(
            VoiceAssistantState(VoiceMode.ERROR, VoiceError.MICROPHONE_PERMISSION_DENIED),
            VoiceAssistantTransitions.permissionResult(granted = false),
        )
        assertEquals(
            VoiceAssistantState(VoiceMode.ERROR, VoiceError.RECOGNITION_UNAVAILABLE),
            VoiceAssistantTransitions.requestListening(permissionGranted = true, recognitionAvailable = false),
        )
        assertEquals(
            VoiceAssistantState(VoiceMode.ERROR, VoiceError.MICROPHONE_UNAVAILABLE),
            VoiceAssistantTransitions.recognitionFailed(VoiceError.MICROPHONE_UNAVAILABLE),
        )
    }

    @Test
    fun recognitionCanCompleteCancelAndSurfaceErrors() {
        val starting = VoiceAssistantTransitions.requestListening(permissionGranted = true, recognitionAvailable = true)
        val listening = VoiceAssistantTransitions.recognitionStarted(starting)
        assertEquals(VoiceAssistantState(VoiceMode.LISTENING), listening)
        assertEquals(
            VoiceAssistantState(VoiceMode.PROCESSING),
            VoiceAssistantTransitions.recognitionEnded(listening),
        )
        assertEquals(VoiceAssistantState(), VoiceAssistantTransitions.recognitionCompleted(hasTranscript = true))
        assertEquals(
            VoiceAssistantState(VoiceMode.ERROR, VoiceError.NO_MATCH),
            VoiceAssistantTransitions.recognitionCompleted(hasTranscript = false),
        )
        assertEquals(
            VoiceAssistantState(),
            VoiceAssistantTransitions.cancelListening(VoiceAssistantState(VoiceMode.LISTENING)),
        )
        assertEquals(
            VoiceAssistantState(),
            VoiceAssistantTransitions.cancelListening(VoiceAssistantState(VoiceMode.AWAITING_PERMISSION)),
        )
        assertEquals(
            VoiceAssistantState(),
            VoiceAssistantTransitions.cancelListening(VoiceAssistantState(VoiceMode.PROCESSING)),
        )
        assertEquals(
            VoiceAssistantState(VoiceMode.ERROR, VoiceError.NETWORK),
            VoiceAssistantTransitions.recognitionFailed(VoiceError.NETWORK),
        )
    }

    @Test
    fun playbackCanPauseResumeAndStop() {
        val speaking = VoiceAssistantTransitions.startPlayback()
        val paused = VoiceAssistantTransitions.pausePlayback(speaking)
        assertEquals(VoiceAssistantState(VoiceMode.PAUSED), paused)
        assertEquals(VoiceAssistantState(VoiceMode.SPEAKING), VoiceAssistantTransitions.resumePlayback(paused))
        assertEquals(VoiceAssistantState(), VoiceAssistantTransitions.stopPlayback(paused))
    }

    @Test
    fun externalRecognitionRequiresExplicitConsentButOnDeviceDoesNot() {
        val system = SpeechPrivacyPolicy.provider(onDeviceRecognitionAvailable = false)
        val onDevice = SpeechPrivacyPolicy.provider(onDeviceRecognitionAvailable = true)

        assertFalse(SpeechPrivacyPolicy.mayStart(system, externalProcessingConsented = false))
        assertTrue(SpeechPrivacyPolicy.mayStart(system, externalProcessingConsented = true))
        assertTrue(SpeechPrivacyPolicy.mayStart(onDevice, externalProcessingConsented = false))
    }
}
