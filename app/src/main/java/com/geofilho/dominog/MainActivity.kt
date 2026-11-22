package com.geofilho.dominog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.geofilho.dominog.ui.AppViewModel
import com.geofilho.dominog.ui.BluetoothScreen
import com.geofilho.dominog.ui.DiagnosticScreen
import com.geofilho.dominog.ui.DominoTheme
import com.geofilho.dominog.ui.GameScreen
import com.geofilho.dominog.ui.HelpScreen
import com.geofilho.dominog.ui.MenuScreen
import com.geofilho.dominog.ui.Screen
import com.geofilho.dominog.ui.rememberScreenReaderOn

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DominoTheme {
                // Sem leitor de tela, a voz do app fica em silêncio (exceto avisos do modo parceiro).
                vm.screenReaderOn = rememberScreenReaderOn()
                when (vm.screen) {
                    Screen.MENU -> MenuScreen(vm)
                    Screen.BLUETOOTH -> BluetoothScreen(vm)
                    Screen.GAME -> GameScreen(vm)
                    Screen.HELP -> HelpScreen(vm)
                    Screen.DIAGNOSTIC -> DiagnosticScreen(vm)
                }
            }
        }
    }
}
