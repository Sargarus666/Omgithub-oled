package com.sargarus.omgithub

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.widget.Toast
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var scrim: View
    private lateinit var drawer: LinearLayout
    private val prefs by lazy { getSharedPreferences("omgithub", MODE_PRIVATE) }
    private val homeUrl = "https://omgithub.com"
    private val githubUrl = "https://github.com"
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var trackingEdgeSwipe = false

    private val theme: String get() = prefs.getString("theme", "oled") ?: "oled"
    private val accent: Int get() {
        val saved = prefs.getString("accent", "system") ?: "system"
        return if (theme == "monet" && saved == "system") monetAccent()
        else runCatching { Color.parseColor(saved) }.getOrDefault(monetAccent())
    }

    private fun isDark(): Boolean = theme != "white"
    private fun pageBackground(): Int = when (theme) {
        "oled" -> Color.BLACK
        "white" -> Color.rgb(250, 250, 250)
        else -> systemColor("system_neutral1_950", Color.rgb(18, 20, 27))
    }
    private fun surface(): Int = when (theme) {
        "oled" -> Color.rgb(18, 18, 18)
        "white" -> Color.rgb(242, 243, 247)
        else -> systemColor("system_neutral2_800", Color.rgb(37, 40, 49))
    }
    private fun foreground(): Int = if (theme == "monet") systemColor("system_neutral1_50", Color.WHITE)
        else if (isDark()) Color.WHITE else Color.rgb(27, 28, 32)
    private fun muted(): Int = if (theme == "monet") systemColor("system_neutral2_200", Color.rgb(185, 188, 198))
        else if (isDark()) Color.rgb(185, 188, 198) else Color.rgb(92, 95, 105)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildInterface()
        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(homeUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildInterface() {
        val bg = pageBackground()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.decorView.systemUiVisibility = if (isDark()) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        webView = WebView(this).apply {
            setBackgroundColor(if (isDark()) Color.BLACK else Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val popup = WebView(this@MainActivity)
                    popup.settings.javaScriptEnabled = true
                    popup.settings.domStorageEnabled = true
                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url
                            if (target.scheme == "http" || target.scheme == "https") {
                                webView.loadUrl(target.toString())
                            } else {
                                openExternalLink(target)
                            }
                            v.destroy()
                            return true
                        }
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme?.lowercase() ?: return true
                    if (scheme == "http" || scheme == "https") return false
                    openExternalLink(uri)
                    return true
                }
            }
            setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.forceDark = if (isDark()) android.webkit.WebSettings.FORCE_DARK_ON
                    else android.webkit.WebSettings.FORCE_DARK_OFF
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        drawer = buildDrawer()
        val drawerWidth = min(dp(336), resources.displayMetrics.widthPixels - dp(48))
        val drawerParams = FrameLayout.LayoutParams(drawerWidth, -1, Gravity.START)
        root.addView(drawer, drawerParams)
        drawer.translationX = -drawerWidth.toFloat()

        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeDownX = event.x
                    edgeDownY = event.y
                    trackingEdgeSwipe = event.x <= dp(28)
                }
                MotionEvent.ACTION_UP -> {
                    if (trackingEdgeSwipe && event.x - edgeDownX > dp(72) &&
                        kotlin.math.abs(event.y - edgeDownY) < dp(100)) openDrawer()
                    trackingEdgeSwipe = false
                }
                MotionEvent.ACTION_CANCEL -> trackingEdgeSwipe = false
            }
            false
        }
        setContentView(root)
    }

    private fun buildDrawer(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            background = rounded(surface(), dp(26))
            elevation = dp(18).toFloat()
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = TextView(this).apply {
            text = "◉  OmGithub"
            textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground())
            setPadding(dp(4), dp(10), dp(4), dp(5))
        }
        content.addView(brand)
        content.addView(label("ВАШЕ ПРОСТРАНСТВО"))
        content.addView(navItem("⌂", "Главная", "omgithub.com") { navigate(homeUrl) })
        content.addView(navItem("⌘", "GitHub", "github.com") { navigate(githubUrl) })
        content.addView(navItem("↓", "Скачанные файлы", "Загрузки устройства") { closeDrawer(); showDownloads() })
        content.addView(sectionTitle("ВНЕШНИЙ ВИД"))
        content.addView(label("ТЕМА"))
        content.addView(themeOption("OLED black", "Чёрный фон, экономия на OLED", "oled"))
        content.addView(themeOption("White", "Светлая тема", "white"))
        content.addView(themeOption("Monet", "Цвета обоев Android 12+", "monet"))
        content.addView(label("ЦВЕТ АКЦЕНТА"))
        content.addView(accentPalette())
        val hint = TextView(this).apply {
            text = "Проведи пальцем от левого края экрана, чтобы открыть это меню."
            textSize = 12f
            setTextColor(muted())
            setPadding(dp(4), dp(24), dp(4), dp(8))
        }
        content.addView(hint)
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 10f
        letterSpacing = 0.12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(muted())
        setPadding(dp(5), dp(24), dp(5), dp(8))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        letterSpacing = 0.14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(accent)
        setPadding(dp(5), dp(28), dp(5), dp(5))
    }

    private fun navItem(icon: String, title: String, subtitle: String, action: () -> Unit): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = rounded(if (title == "Главная") accent.withAlpha(34) else surface(), dp(18))
            setOnClickListener { action() }
        }
        val symbol = TextView(this).apply {
            text = icon
            textSize = 23f
            setTextColor(accent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        texts.addView(TextView(this).apply {
            text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(foreground())
        })
        texts.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(muted()) })
        item.addView(symbol)
        item.addView(texts)
        val lp = LinearLayout.LayoutParams(-1, dp(64))
        lp.bottomMargin = dp(7)
        item.layoutParams = lp
        return item
    }

    private fun themeOption(title: String, description: String, value: String): View {
        val selected = theme == value
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = rounded(if (selected) accent.withAlpha(38) else surface(), dp(16))
            setOnClickListener {
                prefs.edit().putString("theme", value).apply()
                recreate()
            }
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(foreground())
        })
        copy.addView(TextView(this).apply { text = description; textSize = 11f; setTextColor(muted()) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = if (selected) "●" else "○"; textSize = 20f; setTextColor(accent)
        })
        val lp = LinearLayout.LayoutParams(-1, dp(58))
        lp.bottomMargin = dp(7)
        row.layoutParams = lp
        return row
    }

    private fun accentPalette(): View {
        val colors = listOf("#8AB4F8", "#D0BCFF", "#F2B8B5", "#A8DAB5", "#FDD663", "#FFB86B")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(10), dp(7), dp(10))
            background = rounded(surface(), dp(18))
        }
        colors.forEach { hex ->
            val color = Color.parseColor(hex)
            val dot = TextView(this).apply {
                text = if (prefs.getString("accent", "system") == hex) "✓" else ""
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                background = rounded(color, dp(50), if (prefs.getString("accent", "system") == hex) dp(3) else 0, Color.WHITE, Color.WHITE)
                setOnClickListener { prefs.edit().putString("accent", hex).apply(); recreate() }
                contentDescription = "Цвет акцента $hex"
            }
            val lp = LinearLayout.LayoutParams(0, dp(34), 1f)
            lp.leftMargin = dp(3); lp.rightMargin = dp(3)
            row.addView(dot, lp)
        }
        return row
    }

    private fun openDrawer() {
        if (drawer.translationX == 0f) return
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(180).start()
        drawer.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        val width = drawer.width.toFloat()
        drawer.animate().translationX(-width).setDuration(180).withEndAction {
            scrim.visibility = View.GONE
            scrim.alpha = 0f
        }.start()
    }

    private fun navigate(url: String) {
        closeDrawer()
        webView.loadUrl(url)
    }

    private fun openExternalLink(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .substringAfterLast('/').ifBlank { "download" }
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrBlank()) request.addRequestHeader("Cookie", cookies)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            request.setTitle(fileName)
            request.setDescription("Загрузка из OmGithub")
            request.setMimeType(mimeType ?: "application/octet-stream")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, "Загрузка началась: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось начать загрузку: ${e.localizedMessage ?: "ошибка"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDownloads() {
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_SUCCESSFUL or
                DownloadManager.STATUS_FAILED
        ))
        val ids = mutableListOf<Long>()
        val titles = mutableListOf<String>()
        cursor?.use {
            val idCol = it.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleCol = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val status = it.getInt(statusCol)
                val title = it.getString(titleCol) ?: "Файл"
                ids.add(id)
                titles.add(when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> "✓  $title"
                    DownloadManager.STATUS_FAILED -> "✕  $title — ошибка"
                    DownloadManager.STATUS_PAUSED -> "Ⅱ  $title — пауза"
                    else -> "↓  $title — загружается"
                })
            }
        }
        if (titles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Скачанные файлы")
                .setMessage("Здесь пока пусто. Скачанные файлы будут появляться в этом списке.")
                .setPositiveButton("Понятно", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Скачанные файлы")
            .setItems(titles.toTypedArray()) { _, which ->
                val id = ids[which]
                try {
                    val fileUri = manager.getUriForDownloadedFile(id)
                    if (fileUri == null) {
                        Toast.makeText(this, "Файл ещё не готов или загрузка завершилась ошибкой", Toast.LENGTH_SHORT).show()
                    } else {
                        val mime = manager.getMimeTypeForDownloadedFile(id) ?: "*/*"
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "Не найдено приложение для открытия этого файла", Toast.LENGTH_LONG).show()
                }
            }
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun systemColor(name: String, fallback: Int): Int {\n        if (Build.VERSION.SDK_INT < 31) return fallback\n        val id = resources.getIdentifier(name, "color", "android")\n        return if (id == 0) fallback else runCatching { getColor(id) }.getOrDefault(fallback)\n    }\n\n    private fun monetAccent(): Int {
        if (Build.VERSION.SDK_INT >= 31) {
            val id = resources.getIdentifier("system_accent1_500", "color", "android")
            if (id != 0) return runCatching { getColor(id) }.getOrDefault(Color.rgb(180, 190, 255))
        }
        return Color.rgb(180, 190, 255)
    }

    private fun rounded(color: Int, radius: Int, strokeWidth: Int = 0, strokeColor: Int = color, unused: Int = color) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.translationX == 0f) closeDrawer()
        else if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
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
