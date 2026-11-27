# AIVO voice assistant

## Foreground voice input

Voice input is push-to-talk from the authenticated chat composer. AIVO requests Android's `RECORD_AUDIO` permission only after the user taps **Speak**. When the Android on-device recognizer is available (API 31+), AIVO selects it. Otherwise, AIVO requires explicit consent before using the system speech provider, which may transmit/process audio externally and may have provider-specific retention. Partial/final recognition results stay in the composer; they are not sent to AIVO until the user reviews and taps **Send**. AIVO does not save or receive raw microphone audio.

Recognition is canceled when the chat activity is paused, including when the user switches apps or locks the phone. The recognizer is destroyed when no longer needed. No microphone foreground service is registered, and voice capture does not continue in the background. English (`en-US`), Hindi (`hi-IN`), and Urdu (`ur-PK`) are requested from the selected Android recognition service; actual availability depends on the device, provider, downloaded models, and network.

## Speech output

Responses and existing messages can be read by the installed Android text-to-speech engine. First-time playback shows a disclosure about the engine's own data handling. Voice settings save the chat/speech language, an installed local voice when available, speech rate, explicit permission for automatic reading, and consent for a system recognition provider. Automatic reading is off by default. Android TTS engines can have their own data-processing behavior; AIVO does not operate or configure them.

Pause uses the most recent text range reported by the selected TTS engine; resume starts from that position. Engines that do not report range callbacks can only resume from the beginning of the current utterance. Speech stops when the activity is paused or destroyed.

## Hey AIVO wake-word feasibility

No wake word is implemented or claimed. Android's `SpeechRecognizer` documentation warns that implementations may stream audio to remote servers and that it is not intended for continuous recognition because of battery and bandwidth use ([SpeechRecognizer reference](https://developer.android.com/reference/android/speech/SpeechRecognizer)).

Android's `VoiceInteractionService` is a system voice-interaction integration selected by the user; Android keeps the selected service running to support features such as hotwording ([VoiceInteractionService reference](https://developer.android.com/reference/android/service/voice/VoiceInteractionService)). This is not a regular in-app recognizer and would require separate system-assistant UX, service/session components, OEM/device validation, and an explicit privacy and battery design. Low-power hardware hotword support is device-dependent and cannot be assumed on ordinary third-party apps.

A future wake-word design must use an on-device detector, expose an explicit enable/disable control and persistent listening disclosure, avoid saving/uploading raw audio, enforce battery limits, and use only a supported system or user-visible foreground-service route. Android 14+ requires foreground-service types and permissions; microphone access is subject to while-in-use/background-start restrictions ([foreground-service type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)). AIVO deliberately does not request these service permissions or run a background listener.
