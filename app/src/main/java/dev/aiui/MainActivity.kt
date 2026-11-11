package dev.aiui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aiui.data.AppPrefs
import dev.aiui.ui.AppNavigation
import dev.aiui.ui.theme.AiUiTheme

/** A FragmentActivity so BiometricPrompt can guard revealing saved API keys. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AiUiApp).container
        setContent {
            val prefs by container.prefs.prefs.collectAsStateWithLifecycle(initialValue = AppPrefs())
            AiUiTheme(themeMode = prefs.theme, dynamicColor = prefs.dynamicColor) {
                AppNavigation(container)
            }
        }
    }
}
