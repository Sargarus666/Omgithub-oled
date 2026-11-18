package com.sargarus.omgithub

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var root: LinearLayout
    private lateinit var settingsButton: TextView
    private val prefs by lazy { getSharedPreferences("omgithub", MODE_PRIVATE) }
    private val homeUrl = "https://omgithub.com"

    private fun isDarkTheme(): Boolean = when (prefs.getString("theme", "dark")) {
        "light" -> false
        "system" -> (resources.configuration.uiMode and 0x30) == 0x20
        else -> true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildInterface()
        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(homeUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildInterface() {
        val dark = isDarkTheme()
        val background = if (dark) Color.BLACK else Color.rgb(248, 248, 248)
        val foreground = if (dark) Color.WHITE else Color.rgb(20, 20, 20)

        window.statusBarColor = background
        window.navigationBarColor = background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility = if (dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(12), dp(8))
            setBackgroundColor(background)
        }

        val title = TextView(this).apply {
            text = "●  OmGithub"
            textSize = 18f
            setTextColor(foreground)
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            gravity = Gravity.CENTER_VERTICAL
        }

        settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(foreground)
            contentDescription = "Настройки"
            setOnClickListener { showThemeSettings() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        header.addView(title)
        header.addView(settingsButton)

        webView = WebView(this).apply {
            setBackgroundColor(if (dark) Color.BLACK else Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val scheme = request.url.scheme ?: return true
                    return scheme != "http" && scheme != "https"
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.forceDark = if (dark) {
                    android.webkit.WebSettings.FORCE_DARK_ON
                } else {
                    android.webkit.WebSettings.FORCE_DARK_OFF
                }
            }
        }

        root.addView(header)
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showThemeSettings() {
        val options = arrayOf("OLED Black", "Светлая", "Системная")
        val values = arrayOf("dark", "light", "system")
        val selected = values.indexOf(prefs.getString("theme", "dark")).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Тема приложения")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                prefs.edit().putString("theme", values[which]).apply()
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
