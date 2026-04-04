package dev.c0redev.pteraandroid

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.c0redev.pteraandroid.theme.PteraTheme
import dev.c0redev.pteraandroid.ui.AppNavGraph
import dev.c0redev.pteraandroid.ui.ConnectionViewModel

class MainActivity : ComponentActivity() {

    private val vm: ConnectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PteraTheme {
                Surface(modifier = Modifier, color = MaterialTheme.colorScheme.background) {
                    AppNavGraph(vm = vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handleRouteIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRouteIntent(intent)
    }

    private fun handleRouteIntent(intent: Intent?) {
        if (intent == null) return
        val profile = intent.getStringExtra(EXTRA_QUICK_PROFILE)?.trim().orEmpty()
        if (profile.isNotEmpty()) {
            vm.requestQuickConnect(profile)
            intent.removeExtra(EXTRA_QUICK_PROFILE)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS_QUICK, false)) {
            vm.requestNavigateToQuickTilesSettings()
            intent.removeExtra(EXTRA_OPEN_SETTINGS_QUICK)
        }
    }

    companion object {
        const val EXTRA_QUICK_PROFILE = "dev.c0redev.pteraandroid.EXTRA_QUICK_PROFILE"
        const val EXTRA_OPEN_SETTINGS_QUICK = "dev.c0redev.pteraandroid.EXTRA_OPEN_SETTINGS_QUICK"
    }
}
