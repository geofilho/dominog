package com.geofilho.dominog.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * true quando um leitor de tela (TalkBack ou similar, que usa "explorar por toque") está ligado.
 * Atualiza na hora se a pessoa ligar/desligar o leitor com o app aberto.
 */
@Composable
fun rememberScreenReaderOn(): Boolean {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var on by remember { mutableStateOf(am.isEnabled && am.isTouchExplorationEnabled) }
    DisposableEffect(am) {
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { on = am.isEnabled && it }
        val state = AccessibilityManager.AccessibilityStateChangeListener { on = it && am.isTouchExplorationEnabled }
        am.addTouchExplorationStateChangeListener(touch)
        am.addAccessibilityStateChangeListener(state)
        onDispose {
            am.removeTouchExplorationStateChangeListener(touch)
            am.removeAccessibilityStateChangeListener(state)
        }
    }
    return on
}
