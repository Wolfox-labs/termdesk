package dev.termdesk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.termdesk.app.data.PairRequest
import dev.termdesk.app.data.AgentNotifications
import dev.termdesk.app.data.OpenRequest
import dev.termdesk.app.data.parseOpenRequest
import dev.termdesk.app.data.parsePairIntent
import dev.termdesk.app.ui.AppRoot
import dev.termdesk.app.ui.AppViewModel
import dev.termdesk.app.ui.theme.TermDeskTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /**
     * A pairing link that arrived while the app was already open.
     *
     * Held in a flow rather than read once, because `onNewIntent` can deliver one
     * at any time and the Compose tree must see it.
     */
    private val pairRequest = MutableStateFlow<PairRequest?>(null)

    /**
     * A conversation a notification tap asked for.
     *
     * Held the same way as [pairRequest] and for the same reason: a tap can arrive while the
     * app is already open (`onNewIntent`), and the Compose tree has to see it.
     */
    private val openRequest = MutableStateFlow<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars so the theme fills the screen, then pad
        // the content back out. Without the padding pass the toolbar slid under
        // the status bar and became unreadable.
        enableEdgeToEdge()
        pairRequest.value = parsePairIntent(intent)
        openRequest.value = parseOpenRequest(intent)

        setContent {
            val mode by vm.themeMode.collectAsState()
            val pair by pairRequest.collectAsState()
            val open by openRequest.collectAsState()
            TermDeskTheme(mode = mode) {
                Surface(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .consumeWindowInsets(WindowInsets.safeDrawing),
                ) {
                    AppRoot(
                        vm = vm,
                        pairRequest = pair,
                        onPairHandled = { pairRequest.value = null },
                        openRequest = open,
                        onOpenHandled = {
                            openRequest.value = null
                            // So a rotation does not replay a tap that was already served.
                            intent?.removeExtra(AgentNotifications.EXTRA_CHAT_ID)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parsePairIntent(intent)?.let { pairRequest.value = it }
        parseOpenRequest(intent)?.let { openRequest.value = it }
    }

    /**
     * Coming back to the app is a reason to retry: the phone may have changed
     * networks while it was in the background, and waiting out the reconnect
     * backoff would look like the app is broken.
     */
    override fun onResume() {
        super.onResume()
        vm.retryNow()
    }
}
