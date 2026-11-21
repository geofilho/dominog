package com.geofilho.dominog.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Voz do aplicativo (TextToSpeech em português do Brasil). */
class Voice(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private val pending = ArrayList<Pair<String, Boolean>>()
    var enabled = true

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val br = Locale("pt", "BR")
        if (tts.setLanguage(br) < 0) tts.setLanguage(Locale("pt"))
        tts.setSpeechRate(1.05f)
        ready = true
        pending.forEach { (t, f) -> speak(t, f) }
        pending.clear()
    }

    /** [flush] = true interrompe o que estava falando. */
    fun speak(text: String, flush: Boolean = false) {
        if (!enabled || text.isBlank()) return
        if (!ready) { if (flush) pending.clear(); pending += text to flush; return }
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle.EMPTY, text.hashCode().toString())
    }

    fun stop() { if (ready) tts.stop() }
    fun shutdown() = tts.shutdown()
}
