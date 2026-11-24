package com.example.gravediggerrc

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        // --- ZONE AJUSTABLE : adresse de l'ESP32 (point d'accès Wi-Fi) ---
        const val ESP_HOST = "192.168.4.1"
        const val ESP_PORT = 4210
    }

    private lateinit var webView: WebView
    private lateinit var bridge: UdpBridge

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        webView = findViewById(R.id.webView)
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isHapticFeedbackEnabled = false
        webView.setOnLongClickListener { true } // pas de menu "copier" lors d'un appui long

        bridge = UdpBridge(
            context = this,
            host = ESP_HOST,
            port = ESP_PORT,
            onStatus = { s ->
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onStatus && window.onStatus(${JSONObject.quote(s)})", null
                    )
                }
            },
            onRx = { line ->
                runOnUiThread {
                    webView.evaluateJavascript(
                        "window.onRx && window.onRx(${JSONObject.quote(line)})", null
                    )
                }
            }
        )
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")

        bridge.start()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        // Sécurité : demande l'arrêt si l'appli passe en arrière-plan.
        bridge.sendUdp("T:0,S:0,B:0000")
        super.onPause()
    }

    override fun onDestroy() {
        bridge.stop()
        webView.destroy()
        super.onDestroy()
    }
}
