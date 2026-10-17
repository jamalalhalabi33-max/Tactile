package com.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.discovery.TvDiscoveryManager
import com.example.model.Key
import com.example.remote.RemoteManager
import com.example.storage.TvStorage
import com.example.ui.TactileScreen
import com.example.ui.theme.TactileTheme

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var storage: TvStorage
    private lateinit var discoveryManager: TvDiscoveryManager
    private lateinit var remoteManager: RemoteManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on while remote is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system bars for immersion
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        storage = TvStorage(applicationContext)
        discoveryManager = TvDiscoveryManager(applicationContext)
        remoteManager = RemoteManager(applicationContext, storage)

        setContent {
            val isDark by storage.isDarkThemeFlow.collectAsState(initial = true)
            TactileTheme(darkTheme = isDark) {
                TactileScreen(
                    remoteManager = remoteManager,
                    discoveryManager = discoveryManager,
                    isDarkTheme = isDark,
                    onToggleTheme = {
                        lifecycleScope.launch {
                            storage.setDarkTheme(!isDark)
                        }
                    }
                )
            }
        }
    }

    private fun enforceFullScreen() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        enforceFullScreen()
        if (::remoteManager.isInitialized) {
            remoteManager.onResume()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enforceFullScreen()
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    remoteManager.sendKey(Key.VOLUME_UP)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    remoteManager.sendKey(Key.VOLUME_DOWN)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    remoteManager.sendKey(Key.MUTE)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::discoveryManager.isInitialized) {
            discoveryManager.stopScanning()
        }
        if (::remoteManager.isInitialized) {
            remoteManager.disconnect()
        }
    }
}
