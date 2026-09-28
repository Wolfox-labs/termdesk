package dev.termdesk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.termdesk.app.ui.AppRoot
import dev.termdesk.app.ui.AppViewModel
import dev.termdesk.app.ui.theme.TermDeskTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars so the theme fills the screen, then pad
        // the content back out. Without the padding pass the toolbar slid under
        // the status bar and became unreadable.
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val mode by vm.themeMode.collectAsState()
            TermDeskTheme(mode = mode) {
                Surface(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .consumeWindowInsets(WindowInsets.safeDrawing),
                ) {
                    AppRoot(vm)
                }
            }
        }
    }
}
