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
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var title: TextView
    private lateinit var settings: TextView
    private val prefs by lazy { getSharedPreferences("omgithub", MODE_PRIVATE) }
    private val url = "https://omgithub.com"

    private fun dark(): Boolean = when (prefs.getString("theme", "dark")) {
        "light" -> false
        "system" -> (resources.configuration.uiMode and 0x30) == 0x20
        else -> true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        val isDark = dark()
        val bg = if (isDark) Color.BLACK else Color.rgb(248, 248, 248)
        val fg = if (isDark) Color.WHITE else Color.rgb(20, 20, 20)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility =
                if (isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(12), dp(8))
            setBackgroundColor(bg)
        }
        title = TextView(this).apply {
            text = "â—  OmGithub"
            textSize = 18f
            setTextColor(fg)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        settings = TextView(this).apply {
            text = "âš™"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(fg)
            contentDescription = "ĞĞ°ÑÑ‚Ñ€Ğ¾Ğ¹ĞºĞ¸"
            setOnClickListener { showSettings() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        header.addView(title)
        header.addView(settings)

        web = WebView(this).apply {
            setBackgroundColor(if (isDark) Color.BLACK else Color.WHITE)
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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme ?: return true
                    if (scheme == "http" || scheme == "https") return false
                    return true
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                settings.forceDark = if (isDark) android.webkit.WebSettings.FORCE_DARK_ON
                    else android.webkit.WebSettings.FORCE_DARK_OFF
            }
        }

        root.addView(header)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(k¢‹JBˆB‚ˆš]˜]H[ˆÚİÔÙ][™ÜÊ
HÂˆ˜[Ü[ÛœÈH\œ˜^SÙŠ“ÓQ›XÚÈ‹´(t,´-t`´.ô,4cÈ‹´(t.4`t`´-t/4/t,4cÈŠBˆ˜[˜[Y\ÈH\œ˜^SÙŠ™\šÈ‹›YÚ‹œŞ\İ[HŠBˆ˜[İ\œ™[H˜[Y\Ëš[™^ÙŠ™YœË™Ù]İš[™Ê[YH‹™\šÈŠJBˆ[\X[ÙËZ[\Š\ÊBˆœÙ]]J´$´/t-tb4/t.4.H4,´.4-ŠBˆœÙ]Ú[™ÛPÚÚXÙR][\ÊÜ[ÛœËİ\œ™[
HÈX[ÙËÚXÚO‚ˆ™YœË™Y]

Kœ]İš[™Ê[YH‹˜[Y\ÖİÚXÚJK˜\J
BˆX[ÙË™\ÛZ\ÜÊ
BˆZ[ZJ
BˆÙX‹›ØY\›
\›
BˆBˆœÙ]™YØ]]™P]ÛŠ´%ô,4.´`4bô`´c‹[
BˆœÚİÊ
BˆB‚ˆš]˜]H[ˆ
˜[YNˆ[
Nˆ[H
˜[YH
ˆ™\Ûİ\˜Ù\Ë™\Ü^SY]šXÜË™[œÚ]JKÒ[

B‚ˆ\™XØ]Y
‘\™XØ]Y[ˆ˜]˜HŠBˆİ™\œšYH[ˆÛ˜XÚÔ™\ÜÙY

HÂˆYˆ
ÙX‹š\Ò[š]X[^™Y	‰ˆÙX‹˜Ø[‘èBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
}
