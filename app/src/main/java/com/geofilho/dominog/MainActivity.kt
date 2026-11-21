package com.geofilho.dominog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.geofilho.dominog.ui.AppViewModel
import com.geofilho.dominog.ui.BluetoothScreen
import com.geofilho.dominog.ui.DominoTheme
import com.geofilho.dominog.ui.GameScreen
import com.geofilho.dominog.ui.HelpScreen
import com.geofilho.dominog.ui.MenuScreen
import com.geofilho.dominog.ui.Screen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DominoTheme {
                when (vm.screen) {
                    Screen.MENU -> MenuScreen(vm)
                    Screen.BLUETOOTH -> BluetoothScreen(vm)
                    Screen.GAME -> GameScreen(vm)
                    Screen.HELP -> HelpScreen(vm)
                }
            }
        }
    }
}
