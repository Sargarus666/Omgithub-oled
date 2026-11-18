package com.sargarus.omgithub

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Base64
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
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var root: FrameLayout
    private lateinit var scrim: View
    private lateinit var drawer: LinearLayout
    private val prefs by lazy { getSharedPreferences("omgithub", MODE_PRIVATE) }
    private val homeUrl = "https://omgithub.com"
    private val githubUrl = "https://github.com"
    private val bookmarksKey = "browser_bookmarks"
    private var drawerWidthPx = 0
    private var drawerOpen = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var trackingEdgeSwipe = false
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserRequest = 4107

    private val theme: String get() = prefs.getString("theme", "oled") ?: "oled"
    private val accent: Int get() {
        val saved = prefs.getString("accent", "system") ?: "system"
        if (theme == "oled") return Color.WHITE
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
        "oled" -> Color.BLACK
        "white" -> Color.rgb(242, 243, 247)
        else -> systemColor("system_neutral2_800", Color.rgb(37, 40, 49))
    }
    private fun foreground(): Int = if (theme == "oled") Color.WHITE
        else if (theme == "monet") systemColor("system_neutral1_50", Color.WHITE)
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
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(DownloadBridge(), "OmGithubNative")
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                    this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = filePathCallback
                    return try {
                        val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        startActivityForResult(intent, fileChooserRequest)
                        true
                    } catch (_: Exception) {
                        this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                        this@MainActivity.fileChooserCallback = null
                        Toast.makeText(this@MainActivity, "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show()
                        false
                    }
                }
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
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
                    view.evaluateJavascript(BLOB_DOWNLOAD_SCRIPT, null)
                }
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
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accent)
            setProgressBackgroundColorSchemeColor(surface())
            setOnRefreshListener { webView.reload() }
        }
        swipeRefresh.addView(webView, -1, -1)
        root.addView(swipeRefresh, FrameLayout.LayoutParams(-1, -1))

        scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        drawer = buildDrawer()
        drawerWidthPx = min(dp(336), resources.displayMetrics.widthPixels - dp(48))
        val drawerParams = FrameLayout.LayoutParams(drawerWidthPx, -1, Gravity.START)
        root.addView(drawer, drawerParams)
        drawer.translationX = -drawerWidthPx.toFloat()
        drawer.isClickable = true
        drawer.isFocusable = true
        setContentView(root)
    }

    private fun buildDrawer(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(16))
            setBackgroundColor(pageBackground())
            elevation = dp(18).toFloat()
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        drawerContent(scroll, mainDrawerContent())
        return panel
    }

    private fun mainDrawerContent(): LinearLayout {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(5), dp(20))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_omgithub)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "OmGithub"
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(TextView(this@MainActivity).apply {
                text = "OmGithub"; textSize = 24f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(foreground()); setPadding(dp(12), dp(2), 0, dp(2))
            })
        })
        content.addView(label("ПРОСТРАНСТВО"))
        content.addView(navItem("⌂", "OmGithub", "omgithub.com") { navigate(homeUrl) })
        content.addView(navItem("⌘", "GitHub", "github.com") { navigate(githubUrl) })
        content.addView(label("БИБЛИОТЕКА"))
        content.addView(navItem(if (isBookmarked()) "★" else "☆",
            if (isBookmarked()) "Убрать из избранного" else "Добавить в избранное",
            "Сохранить открытую страницу") { addBookmark() })
        content.addView(navItem("★", "Избранное", "${readBookmarks().size} сохранённых страниц") {
            showBookmarksInDrawer()
        })
        content.addView(navItem("↓", "Скачанные файлы", "Загрузки устройства") {
            showDownloadsInDrawer()
        })
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        content.addView(navItem("⚙", "Настройки", "Внешний вид и оформление") { showSettingsInDrawer() })
        return content
    }

    private fun drawerContent(scroll: ScrollView, content: View) {
        scroll.removeAllViews()
        scroll.addView(content)
    }

    private fun showSettingsInDrawer() {
        val scroll = drawer.getChildAt(0) as? ScrollView ?: return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(navItem("‹", "Назад", "Главное меню") { drawerContent(scroll, mainDrawerContent()) })
        content.addView(TextView(this).apply {
            text = "Настройки"; textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground()); setPadding(dp(4), dp(16), dp(4), dp(16))
        })
        content.addView(sectionTitle("ВНЕШНИЙ ВИД"))
        content.addView(label("ТЕМА"))
        content.addView(themeOption("OLED black", "Чёрный фон, белые элементы", "oled"))
        content.addView(themeOption("White", "Светлая тема", "white"))
        content.addView(themeOption("Monet", "Цвета Android", "monet"))
        content.addView(label("ЦВЕТ АКЦЕНТА"))
        content.addView(accentPalette())
        drawerContent(scroll, content)
    }

    private fun isBookmarked(): Boolean {
        val url = webView.url ?: return false
        return readBookmarks().any { it.optString("url") == url }
    }

    private fun readBookmarks(): MutableList<JSONObject> {
        return runCatching {
            val array = JSONArray(prefs.getString(bookmarksKey, "[]") ?: "[]")
            MutableList(array.length()) { array.getJSONObject(it) }
        }.getOrDefault(mutableListOf())
    }

    private fun saveBookmarks(items: List<JSONObject>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        prefs.edit().putString(bookmarksKey, array.toString()).apply()
    }

    private fun addBookmark() {
        val url = webView.url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Сначала откройте страницу", Toast.LENGTH_SHORT).show()
            return
        }
        val items = readBookmarks()
        if (items.removeAll { it.optString("url") == url }) {
            saveBookmarks(items)
            Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show()
        } else {
            val title = webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host.orEmpty()
            items.add(0, JSONObject().put("title", title).put("url", url))
            saveBookmarks(items)
            Toast.makeText(this, "Добавлено в избранное", Toast.LENGTH_SHORT).show()
        }
        val scroll = drawer.getChildAt(0) as? ScrollView
        if (scroll != null) drawerContent(scroll, mainDrawerContent())
    }

    private fun showBookmarksInDrawer() {
        val scroll = drawer.getChildAt(0) as? ScrollView ?: return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(navItem("‹", "Назад", "Главное меню") { drawerContent(scroll, mainDrawerContent()) })
        content.addView(TextView(this).apply {
            text = "Избранное"; textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground()); setPadding(dp(4), dp(16), dp(4), dp(12))
        })
        val items = readBookmarks()
        if (items.isEmpty()) content.addView(TextView(this).apply {
            text = "Здесь будут сохранённые страницы. Добавляйте их через пункт «Добавить в избранное»."
            textSize = 14f; setTextColor(muted()); setPadding(dp(5), dp(12), dp(5), dp(12))
        })
        items.forEach { item ->
            val url = item.optString("url")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(navItem("★", item.optString("title", url), Uri.parse(url).host ?: url) {
                closeDrawer(); webView.loadUrl(url)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "×"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(muted())
                setOnClickListener {
                    val updated = readBookmarks()
                    updated.removeAll { it.optString("url") == url }
                    saveBookmarks(updated)
                    showBookmarksInDrawer()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(54)))
            content.addView(row)
        }
        if (items.isNotEmpty()) content.addView(navItem("⌫", "Очистить избранное", "Удалить все сохранённые страницы") {
            saveBookmarks(emptyList()); showBookmarksInDrawer()
        })
        drawerContent(scroll, content)
    }

    private fun showDownloadsInDrawer() {
        val scroll = drawer.getChildAt(0) as? ScrollView ?: return
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or
                DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_SUCCESSFUL or DownloadManager.STATUS_FAILED
        ))
        data class DownloadEntry(val id: Long, val title: String, val status: Int, val progress: String)
        val entries = mutableListOf<DownloadEntry>()
        cursor?.use {
            val idCol = it.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleCol = it.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusCol = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val doneCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (it.moveToNext()) {
                val status = it.getInt(statusCol)
                val done = if (doneCol >= 0) it.getLong(doneCol) else 0L
                val total = if (totalCol >= 0) it.getLong(totalCol) else -1L
                val progress = if (total > 0) "${((done * 100) / total).coerceIn(0, 100)}%" else ""
                entries.add(DownloadEntry(it.getLong(idCol), it.getString(titleCol) ?: "Файл", status, progress))
            }
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(navItem("‹", "Назад", "Главное меню") { drawerContent(scroll, mainDrawerContent()) })
        content.addView(TextView(this).apply {
            text = "Скачанные файлы"; textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground()); setPadding(dp(4), dp(16), dp(4), dp(12))
        })
        if (entries.isEmpty()) content.addView(TextView(this).apply {
            text = "Пока нет загрузок. Скачанные файлы появятся здесь."
            textSize = 14f; setTextColor(muted()); setPadding(dp(5), dp(12), dp(5), dp(12))
        })
        entries.forEach { entry ->
            val statusText = when (entry.status) {
                DownloadManager.STATUS_SUCCESSFUL -> "Готово · нажмите, чтобы открыть"
                DownloadManager.STATUS_FAILED -> "Ошибка загрузки"
                DownloadManager.STATUS_PAUSED -> "Приостановлено"
                else -> "Загрузка · ${entry.progress}"
            }
            content.addView(navItem(if (entry.status == DownloadManager.STATUS_SUCCESSFUL) "✓" else "↓",
                entry.title, statusText) {
                val uri = manager.getUriForDownloadedFile(entry.id)
                if (uri != null) {
                    val mime = manager.getMimeTypeForDownloadedFile(entry.id) ?: "*/*"
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Не найдено приложение для открытия файла", Toast.LENGTH_LONG).show()
                    }
                } else Toast.makeText(this, "Файл ещё не готов", Toast.LENGTH_SHORT).show()
            }.apply {
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity).setItems(arrayOf("Поделиться", "Удалить")) { _, which ->
                        if (which == 0) {
                            val uri = manager.getUriForDownloadedFile(entry.id)
                            if (uri != null) {
                                val mime = manager.getMimeTypeForDownloadedFile(entry.id) ?: "*/*"
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться файлом"))
                            }
                        } else {
                            manager.remove(entry.id)
                            showDownloadsInDrawer()
                        }
                    }.show()
                    true
                }
            })
        }
        drawerContent(scroll, content)
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
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val symbol = TextView(this).apply {
            text = icon
            textSize = 21f
            setTextColor(accent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        texts.addView(TextView(this).apply {
            text = title; textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(foreground())
        })
        texts.addView(TextView(this).apply { text = subtitle; textSize = 11f; setTextColor(muted()) })
        item.addView(symbol)
        item.addView(texts)
        val lp = LinearLayout.LayoutParams(-1, dp(54))
        lp.bottomMargin = dp(1)
        item.layoutParams = lp
        return item
    }

    private fun themeOption(title: String, description: String, value: String): View {
        val selected = theme == value
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            setBackgroundColor(if (theme == "oled") Color.BLACK else if (selected) accent.withAlpha(38) else surface())
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
            setBackgroundColor(if (theme == "oled") Color.BLACK else surface())
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

    private val drawerInterpolator by lazy { DecelerateInterpolator(1.7f) }

    private fun openDrawer() {
        if (!::drawer.isInitialized || drawerOpen) return
        drawerOpen = true
        drawer.animate().cancel()
        scrim.animate().cancel()
        scrim.visibility = View.VISIBLE
        scrim.animate()
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(drawerInterpolator)
            .start()
        drawer.animate()
            .translationX(0f)
            .setDuration(280)
            .setInterpolator(drawerInterpolator)
            .start()
    }

    private fun closeDrawer() {
        if (!::drawer.isInitialized || !drawerOpen) return
        drawerOpen = false
        drawer.animate().cancel()
        scrim.animate().cancel()
        val width = if (drawer.width > 0) drawer.width.toFloat() else drawerWidthPx.toFloat()
        drawer.animate()
            .translationX(-width)
            .setDuration(240)
            .setInterpolator(drawerInterpolator)
            .withEndAction {
                if (!drawerOpen) {
                    scrim.visibility = View.GONE
                    scrim.alpha = 0f
                }
            }
            .start()
        scrim.animate()
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(drawerInterpolator)
            .start()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeDownX = event.rawX
                edgeDownY = event.rawY
                trackingEdgeSwipe = !drawerOpen && event.rawX <= dp(28)
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - edgeDownX
                val dy = event.rawY - edgeDownY
                if (trackingEdgeSwipe && dx > dp(64) && kotlin.math.abs(dy) < dp(90)) {
                    openDrawer()
                }
                trackingEdgeSwipe = false
            }
            MotionEvent.ACTION_CANCEL -> trackingEdgeSwipe = false
        }
        return super.dispatchTouchEvent(event)
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

    private inner class DownloadBridge {
        @JavascriptInterface
        fun saveBlob(base64: String, fileName: String, mimeType: String) {
            Thread {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^a-zA-Z0-9._ -]"), "_").ifBlank { "download" }
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Не удалось создать файл")
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Не удалось записать файл")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Файл сохранён: $safeName", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Ошибка сохранения: " + e.localizedMessage, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != fileChooserRequest) return
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        val results = if (resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            when { clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }; data.data != null -> arrayOf(data.data!!); else -> null }
        } else null
        callback.onReceiveValue(results)
    }
    private fun showDownloads() = showDownloadsInDrawer()

    private fun systemColor(name: String, fallback: Int): Int {
        if (Build.VERSION.SDK_INT < 31) return fallback
        val id = resources.getIdentifier(name, "color", "android")
        return if (id == 0) fallback else runCatching { getColor(id) }.getOrDefault(fallback)
    }
    private fun monetAccent(): Int {
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
        if (drawerOpen) closeDrawer()
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
    private val BLOB_DOWNLOAD_SCRIPT = """
        (function() {
          if (window.__omGithubBlobHook) return;
          window.__omGithubBlobHook = true;
          document.addEventListener("click", function(event) {
            var a = event.target && event.target.closest ? event.target.closest("a[download]") : null;
            if (!a || !a.href || !a.href.startsWith("blob:")) return;
            event.preventDefault(); event.stopPropagation();
            fetch(a.href).then(function(r) { return r.blob(); }).then(function(blob) {
              var reader = new FileReader();
              reader.onload = function() {
                var data = String(reader.result).split(",")[1] || "";
                window.OmGithubNative.saveBlob(data, a.download || "download", blob.type || "application/octet-stream");
              };
              reader.readAsDataURL(blob);
            }).catch(function() {});
          }, true);
        })();
    """.trimIndent()
}
