package uz.kamoliddin.jasurassistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

class JasurVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
    }
}
