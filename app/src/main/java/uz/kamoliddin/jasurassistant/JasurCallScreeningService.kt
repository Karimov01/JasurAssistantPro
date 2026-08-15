package uz.kamoliddin.jasurassistant

import android.speech.tts.TextToSpeech
import android.telecom.Call
import android.telecom.CallScreeningService
import java.util.Locale

class JasurCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return

        respondToCall(
            callDetails,
            CallScreeningService.CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )

        val settings = SettingsManager(this)
        if (!settings.callAnnounceEnabled) return
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        if (number.isBlank()) return

        val contactName = ContactsHelper(this).lookupNameByNumber(number)
        val phrase = if (!contactName.isNullOrBlank()) {
            "Sizga $contactName telefon qilyapti"
        } else {
            "Sizga ${UzbekNumberReader.phone(number)} raqamidan telefon qilishyapti"
        }

        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale.forLanguageTag(settings.language)
                val r = engine?.setLanguage(locale)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.language = Locale.getDefault()
                }
                engine?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "caller")
            }
        }
    }
}
