package com.geofilho.dominog.diag

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de diagnóstico (Diagnóstico → chave "Registrar o foco do TalkBack").
 * Anota para onde o foco do TalkBack vai, toques e momentos do jogo, para ajudar a resolver
 * problemas de foco. Fica só no aparelho; a pessoa compartilha se quiser.
 */
object DiagLog {
    private const val MAX = 1500
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var enabled = false; private set
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("dominog", Context.MODE_PRIVATE)
        enabled = prefs!!.getBoolean("diag_on", false)
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean("diag_on", on)?.apply()
        add("DIAG", if (on) "registro ligado" else "registro desligado", force = true)
    }

    @Synchronized
    fun add(tag: String, msg: String, force: Boolean = false) {
        if (!enabled && !force) return
        lines.addLast("${time.format(Date())} [$tag] $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "(registro vazio)" else lines.joinToString("\n")

    @Synchronized
    fun clear() { lines.clear() }

    val count: Int @Synchronized get() = lines.size

    /** Eventos de acessibilidade do app (vêm da tela, via delegate na janela). */
    fun a11y(e: AccessibilityEvent) {
        if (!enabled) return
        val type = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "FOCO TalkBack em"
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "foco saiu de"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TOQUE em"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "foco de entrada em"
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> "anúncio"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "janela mudou"
            else -> return // ignora as mudanças de conteúdo (são muitas)
        }
        val text = e.text.joinToString(" ").ifBlank { e.contentDescription?.toString().orEmpty() }
        val cls = e.className?.toString()?.substringAfterLast('.').orEmpty()
        add("A11Y", "$type \"${text.take(120)}\" ($cls)")
    }
}
