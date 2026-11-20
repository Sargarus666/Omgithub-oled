package com.sargarus.omgithub

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.Context
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

/**
 * Pull-to-refresh is armed only inside a touch band positioned below the
 * OmGithub top bar. Touches at the physical top edge and all other vertical
 * gestures stay with Android's system UI or the WebView.
 */
private class EdgeOnlySwipeRefreshLayout(
    context: Context,
    private val touchBandTopPx: Int,
    private val touchBandHeightPx: Int
) : SwipeRefreshLayout(context) {
    private var gestureStartedInBand = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val y = event.y
            gestureStartedInBand = y >= touchBandTopPx &&
                y <= touchBandTopPx + touchBandHeightPx
            if (!gestureStartedInBand) return false
        } else if (!gestureStartedInBand) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureStartedInBand = false
            }
            return false
        }

        val intercepted = super.onInterceptTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureStartedInBand = false
        }
        return intercepted
    }
}

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
            settings.setSupportZoom(true)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setOffscreenPreRaster(prefs.getBoolean("webview_preload", true))
            setLayerType(if (prefs.getBoolean("webview_hardware", true)) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE, null)
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
                    if (prefs.getBoolean("webview_preload", true)) view.evaluateJavascript(PRELOAD_HINTS_SCRIPT, null)
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
        // Keep the Android status bar and OmGithub header clear. The refresh
        // gesture may start in the tall content band directly below the header.
        swipeRefresh = EdgeOnlySwipeRefreshLayout(this, dp(64), dp(208)).apply {
            setColorSchemeColors(accent)
            setProgressBackgroundColorSchemeColor(surface())
            setProgressViewOffset(false, dp(64), dp(120))
            setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }
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

        content.addView(sectionTitle("БРАУЗЕР"))
        content.addView(settingToggle("Аппаратное ускорение WebView", "Использовать GPU для отрисовки страниц", "webview_hardware", true) {
            webView.setLayerType(if (it) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_SOFTWARE, null)
        })
        content.addView(settingToggle("Предзагрузка страниц", "Подготовка соединений с OmGithub и GitHub", "webview_preload", true) {
            if (it) webView.evaluateJavascript(PRELOAD_HINTS_SCRIPT, null)
        })
        content.addView(navItem("⌫", "Очистка кэша", "Удалить временные файлы WebView") {
            webView.clearCache(true)
            Toast.makeText(this, "Кэш WebView очищен", Toast.LENGTH_SHORT).show()
        })
        content.addView(navItem("◉", "Очистка cookies", "Удалить cookies и выйти из аккаунтов") {
            AlertDialog.Builder(this).setTitle("Очистить cookies?")
                .setMessage("Вы выйдете из аккаунтов на сайтах. Действие нельзя отменить.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Очистить") { _, _ ->
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                        runOnUiThread { Toast.makeText(this, "Cookies очищены", Toast.LENGTH_SHORT).show() }
                    }
                }.show()
        })

        content.addView(View(this), LinearLayout.LayoutParams(1, dp(22)))
        content.addView(View(this).apply {
            setBackgroundColor(muted().withAlpha(65))
        }, LinearLayout.LayoutParams(-1, dp(1)))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(10))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_omgithub)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "OmGithubWeb"
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "OmGithubWeb"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(foreground())
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Версия ${appVersion()}"
                    textSize = 11f
                    setTextColor(muted())
                })
            })
        })
        drawerContent(scroll, content)
    }

    private fun settingToggle(title: String, description: String, key: String, defaultValue: Boolean, onChanged: (Boolean) -> Unit = {}): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(8), dp(9))
            setBackgroundColor(if (theme == "oled") Color.BLACK else surface())
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = title; textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground())
        })
        copy.addView(TextView(this).apply { text = description; textSize = 11f; setTextColor(muted()) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(android.widget.Switch(this).apply {
            isChecked = prefs.getBoolean(key, defaultValue)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChanged(checked)
            }
        })
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        return row
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.1"
    } catch (_: Exception) {
        "1.1"
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
            items.add(0, JSONObject().put("title", title).put("url", url).put("folder", "Основное"))
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
        content.addView(navItem("+", "Новая папка", "Создать папку для закладок") {
            val input = android.widget.EditText(this).apply { hint = "Название папки" }
            AlertDialog.Builder(this).setTitle("Новая папка").setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Создать") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val folders = (readBookmarks().map { it.optString("folder", "Основное") } + name).distinct()
                        prefs.edit().putString("bookmark_folders", JSONArray(folders).toString()).apply()
                        showBookmarksInDrawer()
                    }
                }.show()
        })
        val items = readBookmarks()
        val savedFolders = runCatching {
            val a = JSONArray(prefs.getString("bookmark_folders", "[]"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
        val folderNames = (listOf("Основное") + items.map { it.optString("folder", "Основное") } + savedFolders).distinct()
        folderNames.forEach { folder ->
            val folderItems = items.filter { it.optString("folder", "Основное") == folder }
            content.addView(sectionTitle(folder + " · " + folderItems.size))
            folderItems.forEach { item ->
                val url = item.optString("url")
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(navItem("★", item.optString("title", url), Uri.parse(url).host ?: url) {
                    closeDrawer(); webView.loadUrl(url)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply {
                    text = "⋮"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(muted())
                    setOnClickListener {
                        val updated = readBookmarks()
                        val target = updated.firstOrNull { it.optString("url") == url } ?: return@setOnClickListener
                        AlertDialog.Builder(this@MainActivity)
                            .setItems(arrayOf("Переименовать", "Переместить в папку", "Удалить")) { _, choice ->
                                when (choice) {
                                    0 -> {
                                        val edit = android.widget.EditText(this@MainActivity).apply { setText(target.optString("title")) }
                                        AlertDialog.Builder(this@MainActivity).setTitle("Название закладки").setView(edit)
                                            .setNegativeButton("Отмена", null)
                                            .setPositiveButton("Сохранить") { _, _ ->
                                                target.put("title", edit.text.toString().trim().ifBlank { url })
                                                saveBookmarks(updated); showBookmarksInDrawer()
                                            }.show()
                                    }
                                    1 -> AlertDialog.Builder(this@MainActivity).setTitle("Папка")
                                        .setItems(folderNames.toTypedArray()) { _, index ->
                                            target.put("folder", folderNames[index]); saveBookmarks(updated); showBookmarksInDrawer()
                                        }.show()
                                    2 -> {
                                        updated.removeAll { it.optString("url") == url }
                                        saveBookmarks(updated); showBookmarksInDrawer()
                                    }
                                }
                            }.show()
                    }
                }, LinearLayout.LayoutParams(dp(40), dp(54)))
                content.addView(row)
            }
        }
        if (items.isEmpty()) content.addView(TextView(this).apply {
            text = "Здесь будут сохранённые страницы. Добавляйте их через пункт «Добавить в избранное»."
            textSize = 14f; setTextColor(muted()); setPadding(dp(5), dp(12), dp(5), dp(12))
        })
        if (items.isNotEmpty()) content.addView(navItem("⌫", "Очистить избранное", "Удалить все сохранённые страницы") {
            AlertDialog.Builder(this).setMessage("Удалить все закладки?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ -> saveBookmarks(emptyList()); showBookmarksInDrawer() }.show()
        })
        drawerContent(scroll, content)
    }

    private fun showDownloadsInDrawer() {
        val scroll = drawer.getChildAt(0) as? ScrollView ?: return
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        data class DownloadEntry(val id: Long, val title: String, val status: Int, val done: Long, val total: Long, val uri: String, val reason: Int, val mime: String?)
        val entries = mutableListOf<DownloadEntry>()
        manager.query(DownloadManager.Query()).use { cursor ->
            val idCol = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleCol = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val doneCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
            val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            val mimeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            while (cursor.moveToNext()) entries.add(DownloadEntry(
                cursor.getLong(idCol),
                if (titleCol >= 0) cursor.getString(titleCol) ?: "Файл" else "Файл",
                cursor.getInt(statusCol),
                if (doneCol >= 0) cursor.getLong(doneCol) else 0L,
                if (totalCol >= 0) cursor.getLong(totalCol) else -1L,
                if (uriCol >= 0) cursor.getString(uriCol).orEmpty() else "",
                if (reasonCol >= 0) cursor.getInt(reasonCol) else 0,
                if (mimeCol >= 0) cursor.getString(mimeCol) else null
            ))
        }
        entries.reverse()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(navItem("‹", "Назад", "Главное меню") { drawerContent(scroll, mainDrawerContent()) })
        content.addView(TextView(this).apply {
            text = "Скачанные файлы"; textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(foreground()); setPadding(dp(4), dp(16), dp(4), dp(8))
        })
        content.addView(navItem("↻", "Обновить список", entries.size.toString() + " загрузок") { showDownloadsInDrawer() })
        if (entries.isEmpty()) content.addView(TextView(this).apply {
            text = "Пока нет загрузок. Скачанные файлы появятся здесь."
            textSize = 14f; setTextColor(muted()); setPadding(dp(5), dp(12), dp(5), dp(12))
        })
        entries.forEach { entry ->
            val percent = if (entry.total > 0) ((entry.done * 100) / entry.total).toInt().coerceIn(0, 100) else -1
            val sizeText = if (entry.total > 0) formatBytes(entry.done) + " / " + formatBytes(entry.total) + " · " + percent + "%"
                else if (entry.done > 0) formatBytes(entry.done) else ""
            val statusText = when (entry.status) {
                DownloadManager.STATUS_SUCCESSFUL -> "Готово · " + sizeText
                DownloadManager.STATUS_FAILED -> "Ошибка · код " + entry.reason
                DownloadManager.STATUS_PAUSED -> "Приостановлено · " + entry.reason
                DownloadManager.STATUS_PENDING -> "Ожидание · " + sizeText
                else -> "Загрузка · " + sizeText
            }
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
            val item = navItem(
                if (entry.status == DownloadManager.STATUS_SUCCESSFUL) "✓" else if (entry.status == DownloadManager.STATUS_FAILED) "!" else "↓",
                entry.title, statusText
            ) {
                when (entry.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> openDownloadedFile(manager, entry.id)
                    DownloadManager.STATUS_FAILED -> retryDownload(entry.uri, entry.title, entry.mime)
                    else -> Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
                }
            }
            item.setOnLongClickListener {
                val actions = mutableListOf<String>()
                if (entry.status == DownloadManager.STATUS_FAILED || entry.status == DownloadManager.STATUS_PAUSED) actions.add("Повторить загрузку")
                if (entry.status == DownloadManager.STATUS_SUCCESSFUL) { actions.add("Открыть"); actions.add("Поделиться") }
                actions.add("Удалить из списка")
                AlertDialog.Builder(this@MainActivity).setItems(actions.toTypedArray()) { _, which ->
                    when (actions[which]) {
                        "Повторить загрузку" -> retryDownload(entry.uri, entry.title, entry.mime)
                        "Открыть" -> openDownloadedFile(manager, entry.id)
                        "Поделиться" -> shareDownloadedFile(manager, entry.id)
                        "Удалить из списка" -> { manager.remove(entry.id); showDownloadsInDrawer() }
                    }
                }.show()
                true
            }
            card.addView(item)
            if (entry.status == DownloadManager.STATUS_RUNNING || entry.status == DownloadManager.STATUS_PENDING) {
                card.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = entry.total <= 0
                    if (entry.total > 0) { max = 100; progress = percent.coerceAtLeast(0) }
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(muted().withAlpha(55))
                }, LinearLayout.LayoutParams(-1, dp(3)).apply { leftMargin = dp(12); rightMargin = dp(12) })
            }
            content.addView(card)
        }
        drawerContent(scroll, content)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " Б"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f МБ", mb)
        return String.format(java.util.Locale.getDefault(), "%.2f ГБ", mb / 1024.0)
    }

    private fun openDownloadedFile(manager: DownloadManager, id: Long) {
        val uri = manager.getUriForDownloadedFile(id) ?: run {
            Toast.makeText(this, "Файл ещё не готов", Toast.LENGTH_SHORT).show(); return
        }
        val mime = manager.getMimeTypeForDownloadedFile(id) ?: "*/*"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "Не найдено приложение для открытия файла", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareDownloadedFile(manager: DownloadManager, id: Long) {
        val uri = manager.getUriForDownloadedFile(id) ?: return
        val mime = manager.getMimeTypeForDownloadedFile(id) ?: "*/*"
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться файлом"))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось поделиться файлом", Toast.LENGTH_SHORT).show()
        }
    }

    private fun retryDownload(url: String, title: String, mime: String?) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
            Toast.makeText(this, "Ссылка для повтора недоступна", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val request = DownloadManager.Request(uri)
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
            request.addRequestHeader("User-Agent", webView.settings.userAgentString)
            request.setTitle(title)
            request.setDescription("Повторная загрузка из OmGithub")
            request.setMimeType(mime ?: "application/octet-stream")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, title)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Повторная загрузка началась", Toast.LENGTH_SHORT).show()
            showDownloadsInDrawer()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось повторить загрузку: " + (e.localizedMessage ?: "ошибка"), Toast.LENGTH_LONG).show()
        }
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
    private val PRELOAD_HINTS_SCRIPT = """
        (function() {
          if (!document.head || document.getElementById('omgithub-preload-hints')) return;
          var marker = document.createElement('meta');
          marker.id = 'omgithub-preload-hints';
          document.head.appendChild(marker);
          ['https://omgithub.com', 'https://github.com'].forEach(function(origin) {
            var dns = document.createElement('link');
            dns.rel = 'dns-prefetch'; dns.href = origin; document.head.appendChild(dns);
            var pre = document.createElement('link');
            pre.rel = 'preconnect'; pre.href = origin; pre.crossOrigin = 'anonymous';
            document.head.appendChild(pre);
          });
        })();
    """.trimIndent()

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
