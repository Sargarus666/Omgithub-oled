package com.sargarus.omgithub

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Base64
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
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
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
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
    private lateinit var tabsContainer: FrameLayout
    private data class BrowserTab(val id: Int, val view: WebView)
    private val tabs = mutableListOf<BrowserTab>()
    private var nextTabId = 1
    private lateinit var scrim: View
    private lateinit var drawer: LinearLayout
    private val prefs by lazy { getSharedPreferences("omgithub", MODE_PRIVATE) }
    private val homeUrl = "https://omgithub.com"
    private val githubUrl = "https://github.com"
    private val historyKey = "browser_history"
    private val bookmarksKey = "browser_bookmarks"
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var trackingEdgeSwipe = false
    @Volatile private var trustedPageHost: String = ""
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var mobileUserAgent = ""
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
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.offscreenPreRaster = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mobileUserAgent = settings.userAgentString
            applyDesktopMode()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            val downloadBridge = DownloadBridge()
            addJavascriptInterface(downloadBridge, "OmGithubNative")
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
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    trustedPageHost = runCatching { Uri.parse(url).host?.lowercase().orEmpty() }.getOrDefault("")
                    downloadBridge.updateHost(url)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(BLOB_DOWNLOAD_SCRIPT, null)
                    rememberHistory(url)
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
        applyDesktopMode()
        tabsContainer = FrameLayout(this)
        tabsContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))
        tabs.add(BrowserTab(nextTabId++, webView))
        root.addView(tabsContainer, FrameLayout.LayoutParams(-1, -1))

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
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(pageBackground())
            elevation = dp(18).toFloat()
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = TextView(this).apply {
            text = "◉  OmGithubWeb"
            textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground())
            setPadding(dp(4), dp(10), dp(4), dp(5))
        }
        content.addView(brand)
        content.addView(label("ВАШЕ ПРОСТРАНСТВО"))
        content.addView(navItem("⌂", "Главная", "omgithub.com") { navigate(homeUrl) })
        content.addView(navItem("⌘", "GitHub", "Главная GitHub") { navigate(githubUrl) })
        content.addView(navItem("◉", "Мои репозитории", "Репозитории аккаунта") { navigate("https://github.com/your-repositories") })
        content.addView(navItem("★", "Звёзды", "Сохранённые репозитории GitHub") { navigate("https://github.com/stars") })
        content.addView(label("ИНСТРУМЕНТЫ"))
        content.addView(navItem("☆", "Добавить в избранное", "Сохранить текущую страницу") { addBookmark() })
        content.addView(navItem("▣", "Избранное", "Сохранённые страницы") { closeDrawer(); showSavedPages(bookmarksKey, "Избранное") })
        content.addView(navItem("◷", "История", "Недавно открытые страницы") { closeDrawer(); showSavedPages(historyKey, "История") })
        content.addView(navItem("↓", "Скачанные файлы", "Загрузки устройства") { closeDrawer(); showDownloads() })
        addBrowserTools(content)
        content.addView(label("ВКЛАДКИ"))
        content.addView(navItem("▣", "Вкладки (${tabs.size})", "Переключение между страницами") { closeDrawer(); showTabsDialog() })
        content.addView(navItem("+", "Новая вкладка", "Открыть главную страницу отдельно") { createTab(homeUrl) })
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        content.addView(navItem("⚙", "Настройки", "Внешний вид и оформление") { showSettingsInDrawer() })
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    private fun showSettingsInDrawer() {
        val scroll = drawer.getChildAt(0) as? ScrollView ?: return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(navItem("‹", "Назад", "Главное меню") { showMainDrawerContent() })
        content.addView(TextView(this).apply {
            text = "Настройки"; textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground()); setPadding(dp(4), dp(16), dp(4), dp(16))
        })
        content.addView(sectionTitle("ВНЕШНИЙ ВИД"))
        content.addView(label("ТЕМА"))
        content.addView(themeOption("OLED black", "Абсолютно чёрный фон, белые надписи и иконки", "oled"))
        content.addView(themeOption("White", "Светлая тема", "white"))
        content.addView(themeOption("Monet", "Цвета обоев Android 12+", "monet"))
        if (theme == "oled") {
            content.addView(label("ЦВЕТ АКЦЕНТА"))
            content.addView(TextView(this).apply {
                text = "Белый акцент закреплён для True OLED"
                textSize = 13f; setTextColor(Color.WHITE)
                setPadding(dp(5), dp(8), dp(5), dp(12))
            })
        } else {
            content.addView(label("ЦВЕТ АКЦЕНТА"))
            content.addView(accentPalette())
        }
        scroll.removeAllViews(); scroll.addView(content)
    }

    private fun showMainDrawerContent() {
        val scroll = drawer.getChildAt(0) as? ScrollView ?: return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply {
            text = "◉  OmGithub"; textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground()); setPadding(dp(4), dp(10), dp(4), dp(5))
        })
        content.addView(label("ВАШЕ ПРОСТРАНСТВО"))
        content.addView(navItem("⌂", "Главная", "omgithub.com") { navigate(homeUrl) })
        content.addView(navItem("⌘", "GitHub", "Главная GitHub") { navigate(githubUrl) })
        content.addView(navItem("◉", "Мои репозитории", "Репозитории аккаунта") { navigate("https://github.com/your-repositories") })
        content.addView(navItem("★", "Звёзды", "Сохранённые репозитории GitHub") { navigate("https://github.com/stars") })
        content.addView(label("ИНСТРУМЕНТЫ"))
        content.addView(navItem("☆", "Добавить в избранное", "Сохранить текущую страницу") { addBookmark() })
        content.addView(navItem("▣", "Избранное", "Сохранённые страницы") { closeDrawer(); showSavedPages(bookmarksKey, "Избранное") })
        content.addView(navItem("◷", "История", "Недавно открытые страницы") { closeDrawer(); showSavedPages(historyKey, "История") })
        content.addView(navItem("↓", "Скачанные файлы", "Загрузки устройства") { closeDrawer(); showDownloads() })
        addBrowserTools(content)
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        content.addView(navItem("⚙", "Настройки", "Внешний вид и оформление") { showSettingsInDrawer() })
        scroll.removeAllViews(); scroll.addView(content)
    }

    private fun addBrowserTools(content: LinearLayout) {
        content.addView(label("БРАУЗЕР"))
        content.addView(navItem("←", "Назад", "Предыдущая страница") {
            closeDrawer()
            if (webView.canGoBack()) webView.goBack()
        })
        content.addView(navItem("→", "Вперёд", "Следующая страница") {
            closeDrawer()
            if (webView.canGoForward()) webView.goForward()
        })
        content.addView(navItem("↻", "Обновить", "Перезагрузить страницу") {
            closeDrawer()
            webView.reload()
        })
        content.addView(navItem("⇧", "Поделиться страницей", "Отправить текущую ссылку") {
            closeDrawer()
            shareCurrentPage()
        })
        content.addView(navItem("▤", "Копировать ссылку", "Скопировать адрес страницы") {
            closeDrawer()
            copyCurrentPage()
        })
        val desktop = prefs.getBoolean("desktop_mode", false)
        content.addView(navItem("▣", if (desktop) "Мобильная версия" else "Версия для ПК",
            if (desktop) "Вернуться к мобильному сайту" else "Запросить настольный сайт") {
            prefs.edit().putBoolean("desktop_mode", !prefs.getBoolean("desktop_mode", false)).apply()
            applyDesktopMode()
            closeDrawer()
            webView.reload()
            Toast.makeText(this, if (prefs.getBoolean("desktop_mode", false)) "Режим ПК включён" else "Мобильный режим включён", Toast.LENGTH_SHORT).show()
        })
    }

    private fun applyDesktopMode() {
        if (!::webView.isInitialized || mobileUserAgent.isBlank()) return
        webView.settings.userAgentString = if (prefs.getBoolean("desktop_mode", false)) {
            mobileUserAgent.replace(Regex("Android [^;)]+;\\s*"), "")
                .replace("Mobile", "")
                .replace("Linux;", "X11; Linux x86_64;")
        } else mobileUserAgent
    }

    private fun shareCurrentPage() {
        val url = webView.url ?: return
        val title = webView.title?.takeIf { it.isNotBlank() } ?: url
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }, "Поделиться страницей"))
    }

    private fun copyCurrentPage() {
        val url = webView.url ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Ссылка", url))
        Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
    }

    private fun createTab(url: String = homeUrl) {
        val bridge = DownloadBridge()
        val page = WebView(this).apply {
            setBackgroundColor(if (isDark()) Color.BLACK else Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.offscreenPreRaster = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.safeBrowsingEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) settings.forceDark =
                if (isDark()) android.webkit.WebSettings.FORCE_DARK_ON else android.webkit.WebSettings.FORCE_DARK_OFF
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(bridge, "OmGithubNative")
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(v: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = callback
                    return try {
                        val intent = params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                        }
                        intent.addCategory(Intent.CATEGORY_OPENABLE)
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        startActivityForResult(intent, fileChooserRequest)
                        true
                    } catch (_: Exception) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, pageUrl: String, favicon: android.graphics.Bitmap?) {
                    bridge.updateHost(pageUrl)
                }
                override fun onPageFinished(view: WebView, pageUrl: String) {
                    view.evaluateJavascript(BLOB_DOWNLOAD_SCRIPT, null)
                    rememberHistory(pageUrl)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "http" || uri.scheme == "https") return false
                    openExternalLink(uri)
                    return true
                }
            }
            setDownloadListener(DownloadListener { downloadUrl, userAgent, disposition, mime ->
                enqueueDownload(downloadUrl, userAgent, disposition, mime)
            })
        }
        tabs.add(BrowserTab(nextTabId++, page))
        tabsContainer.addView(page, FrameLayout.LayoutParams(-1, -1))
        webView.visibility = View.GONE
        webView = page
        mobileUserAgent = page.settings.userAgentString
        applyDesktopMode()
        page.loadUrl(url)
        closeDrawer()
        Toast.makeText(this, "Новая вкладка (\${tabs.size})", Toast.LENGTH_SHORT).show()
    }

    private fun showTabsDialog() {
        val labels = tabs.mapIndexed { index, tab ->
            val title = tab.view.title?.takeIf { it.isNotBlank() }
                ?: tab.view.url?.let { Uri.parse(it).host } ?: "Новая вкладка"
            "\${index + 1}. $title"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Открытые вкладки (\${tabs.size})")
            .setItems(labels) { _, which -> switchTab(which) }
            .setPositiveButton("+ Новая вкладка") { _, _ -> createTab(homeUrl) }
            .setNegativeButton("Закрыть текущую") { _, _ -> closeCurrentTab() }
            .show()
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.forEach { it.view.visibility = View.GONE }
        webView = tabs[index].view
        webView.visibility = View.VISIBLE
        mobileUserAgent = webView.settings.userAgentString
        closeDrawer()
    }

    private fun closeCurrentTab() {
        if (tabs.size <= 1) {
            webView.loadUrl(homeUrl)
            return
        }
        val index = tabs.indexOfFirst { it.view === webView }.coerceAtLeast(0)
        val removed = tabs.removeAt(index)
        removed.view.stopLoading()
        tabsContainer.removeView(removed.view)
        removed.view.destroy()
        switchTab((index - 1).coerceAtLeast(0))
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
            setBackgroundColor(if (theme == "oled") Color.BLACK else if (title == "Главная") accent.withAlpha(34) else surface())
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
            request.setDescription("Загрузка из OmGithubWeb")
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

    private fun isTrustedWebHost(): Boolean {
        val host = trustedPageHost
        return host == "omgithub.com" || host.endsWith(".omgithub.com") ||
            host == "github.com" || host.endsWith(".github.com")
    }

    private inner class DownloadBridge {
        @Volatile private var trustedHost = ""
        fun updateHost(url: String) { trustedHost = runCatching { Uri.parse(url).host?.lowercase().orEmpty() }.getOrDefault("") }
        @JavascriptInterface
        fun saveBlob(base64: String, fileName: String, mimeType: String) {
            val host = trustedHost
            if (!(host == "omgithub.com" || host.endsWith(".omgithub.com") || host == "github.com" || host.endsWith(".github.com"))) return
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
    private fun rememberHistory(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val items = readSavedItems(historyKey)
        items.removeAll { it.optString("url") == url }
        val title = webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host.orEmpty()
        items.add(0, JSONObject().put("title", title).put("url", url))
        while (items.size > 60) items.removeAt(items.lastIndex)
        writeSavedItems(historyKey, items)
    }

    private fun addBookmark() {
        val url = webView.url ?: run {
            Toast.makeText(this, "Страница ещё не загружена", Toast.LENGTH_SHORT).show()
            return
        }
        val title = webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host.orEmpty()
        val items = readSavedItems(bookmarksKey)
        if (items.any { it.optString("url") == url }) {
            Toast.makeText(this, "Уже есть в избранном", Toast.LENGTH_SHORT).show()
            return
        }
        items.add(0, JSONObject().put("title", title).put("url", url))
        writeSavedItems(bookmarksKey, items)
        closeDrawer()
        Toast.makeText(this, "Добавлено в избранное", Toast.LENGTH_SHORT).show()
    }

    private fun readSavedItems(key: String): MutableList<JSONObject> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.getJSONObject(index) }
        }.getOrDefault(mutableListOf())
    }

    private fun writeSavedItems(key: String, items: List<JSONObject>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun showSavedPages(key: String, title: String) {
        val items = readSavedItems(key)
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle(title)
                .setMessage(if (key == bookmarksKey) "Сохраняй страницы через «Добавить в избранное» в боковом меню." else "Здесь появятся недавно открытые страницы.")
                .setPositiveButton("Понятно", null).show()
            return
        }
        val labels = items.map { it.optString("title", it.optString("url")) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title).setItems(labels) { _, index ->
            navigate(items[index].optString("url"))
        }.setNeutralButton("Очистить") { _, _ ->
            writeSavedItems(key, emptyList())
            Toast.makeText(this, "$title очищено", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Закрыть", null).show()
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
            val downloadedCol = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val status = it.getInt(statusCol)
                val title = it.getString(titleCol) ?: "Файл"
                val downloaded = if (downloadedCol >= 0) it.getLong(downloadedCol) else 0L
                val total = if (totalCol >= 0) it.getLong(totalCol) else -1L
                val progress = if (total > 0) " — ${((downloaded * 100) / total).coerceIn(0, 100)}%" else ""
                ids.add(id)
                titles.add(when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> "✓  $title"
                    DownloadManager.STATUS_FAILED -> "✕  $title — ошибка"
                    DownloadManager.STATUS_PAUSED -> "Ⅱ  $title — пауза$progress"
                    else -> "↓  $title — загружается$progress"
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
                val fileUri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull()
                val mime = runCatching { manager.getMimeTypeForDownloadedFile(id) }.getOrNull() ?: "*/*"
                val actions = if (fileUri != null) arrayOf("Открыть", "Поделиться", "Удалить загрузку")
                    else arrayOf("Удалить загрузку", "Закрыть")
                AlertDialog.Builder(this)
                    .setTitle(titles[which].substringBefore(" — "))
                    .setItems(actions) { _, action ->
                        when (actions[action]) {
                            "Открыть" -> try {
                                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: Exception) {
                                Toast.makeText(this, "Не найдено приложение для открытия файла", Toast.LENGTH_LONG).show()
                            }
                            "Поделиться" -> try {
                                val share = Intent(Intent.ACTION_SEND).setType(mime)
                                    .putExtra(Intent.EXTRA_STREAM, fileUri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                startActivity(Intent.createChooser(share, "Поделиться файлом"))
                            } catch (_: Exception) {
                                Toast.makeText(this, "Не удалось поделиться файлом", Toast.LENGTH_LONG).show()
                            }
                            "Удалить загрузку" -> {
                                manager.remove(id)
                                Toast.makeText(this, "Загрузка удалена", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.setNegativeButton("Назад", null).show()
            }
            .setPositiveButton("Закрыть", null)
            .show()
    }

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
        if (drawer.translationX == 0f) closeDrawer()
        else if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else if (tabs.size > 1) closeCurrentTab()
        else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            tabs.forEach { it.view.stopLoading(); it.view.destroy() }
            tabs.clear()
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
