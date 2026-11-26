package app.shieldbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/** The whole browser: one WebView, an address bar, a shield (block counter) and a menu. */
abstract class BrowserActivity : Activity() {

    abstract val incognito: Boolean

    protected lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var shield: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fullscreen: FrameLayout

    @Volatile private var pageHost: String? = null
    @Volatile private var allowlist: Set<String> = emptySet()
    private val blocked = AtomicInteger()
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val prefs by lazy { getSharedPreferences("browser", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        web = findViewById(R.id.web)
        urlBar = findViewById(R.id.url)
        shield = findViewById(R.id.shield)
        progress = findViewById(R.id.progress)
        fullscreen = findViewById(R.id.fullscreen)
        allowlist = prefs.getStringSet(KEY_ALLOWLIST, emptySet())!!.toSet()

        urlBar.backgroundTintList = ColorStateList.valueOf(getColor(
            if (incognito) R.color.urlbar_incognito else R.color.urlbar))
        findViewById<View>(R.id.incognito_badge).visibility = if (incognito) View.VISIBLE else View.GONE
        updateShield()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            if (incognito) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, userAgent, disposition, mime, _ -> download(url, userAgent, disposition, mime) }
        registerForContextMenu(web)

        urlBar.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                load(toUrl(urlBar.text.toString())); true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, has -> if (!has) showUrl(web.url) }
        shield.setOnClickListener { toggleSite() }
        findViewById<ImageButton>(R.id.menu).setOnClickListener { showMenu(it) }

        val start = intent?.dataString
        when {
            start != null -> load(start)
            savedInstanceState != null && !incognito -> web.restoreState(savedInstanceState)
            else -> load(HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { load(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!incognito) web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        if (!incognito) CookieManager.getInstance().flush()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            incognito -> finishAndRemoveTask()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ navigation

    protected fun load(url: String) {
        web.loadUrl(url)
        showUrl(url)
        web.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun showUrl(url: String?) {
        if (!urlBar.hasFocus()) urlBar.setText(if (url == null || url == HOME) "" else url)
    }

    private fun updateShield() {
        val off = pageHost in allowlist
        shield.text = if (off) "off" else blocked.get().toString()
        shield.compoundDrawableTintList = ColorStateList.valueOf(
            getColor(if (off) R.color.shield_off else R.color.shield_on))
    }

    private fun toggleSite() {
        val host = pageHost ?: return
        allowlist = if (host in allowlist) allowlist - host else allowlist + host
        prefs.edit().putStringSet(KEY_ALLOWLIST, allowlist).apply()
        Toast.makeText(this, getString(
            if (host in allowlist) R.string.blocking_off else R.string.blocking_on, host), Toast.LENGTH_SHORT).show()
        web.reload()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.forward).isEnabled = web.canGoForward()
        menu.menu.add(0, 2, 0, R.string.reload)
        menu.menu.add(0, 3, 0, R.string.share)
        menu.menu.add(0, 4, 0, if (pageHost in allowlist) R.string.enable_blocking else R.string.disable_blocking)
        menu.menu.add(0, 5, 0, if (incognito) R.string.close_incognito else R.string.new_incognito)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> web.goForward()
                2 -> web.reload()
                3 -> web.url?.let { url ->
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                }
                4 -> toggleSite()
                5 -> if (incognito) finishAndRemoveTask() else openIncognito(null)
            }
            true
        }
        menu.show()
    }

    protected fun openIncognito(url: String?) {
        startActivity(Intent(this, IncognitoActivity::class.java)
            .setData(url?.let(Uri::parse))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCreateContextMenu(menu: ContextMenu, v: View, info: ContextMenu.ContextMenuInfo?) {
        val hit = web.hitTestResult
        val link = hit.extra ?: return
        if (hit.type != WebView.HitTestResult.SRC_ANCHOR_TYPE &&
            hit.type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return
        menu.setHeaderTitle(link)
        if (!incognito) menu.add(R.string.open_incognito).setOnMenuItemClickListener { openIncognito(link); true }
        menu.add(R.string.copy_link).setOnMenuItemClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", link)); true
        }
    }

    private fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        if (!URLUtil.isNetworkUrl(url)) return
        val name = URLUtil.guessFileName(url, disposition, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
        getSystemService(DownloadManager::class.java).enqueue(req)
        Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
    }

    private fun exitFullscreen() {
        fullscreen.removeAllViews()
        fullscreen.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    // ------------------------------------------------------------------ WebView callbacks

    private inner class Client : WebViewClient() {
        /** Runs on a WebView background thread for every request: this is the ad blocker. */
        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (req.isForMainFrame) {
                pageHost = req.url.host
                blocked.set(0)
                shield.post { updateShield() }
                return null
            }
            val page = pageHost
            if (page != null && page in allowlist) return null
            if (!App.filters(applicationContext).shouldBlock(req.url.toString(), page)) return null
            blocked.incrementAndGet()
            shield.post { updateShield() }
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val scheme = req.url.scheme ?: return false
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") return false
            // tel:, mailto:, intent:, market: ... hand off to other apps.
            try {
                val intent = Intent.parseUri(req.url.toString(), Intent.URI_INTENT_SCHEME)
                    .addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null)
                intent.selector = null
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this@BrowserActivity, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageHost = Uri.parse(url).host
            showUrl(url)
            updateShield()
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            showUrl(url)
            progress.visibility = View.GONE
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, p: Int) {
            progress.progress = p
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView = view
            customViewCallback = callback
            fullscreen.addView(view)
            fullscreen.visibility = View.VISIBLE
        }

        override fun onHideCustomView() = exitFullscreen()

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                @Suppress("DEPRECATION") startActivityForResult(params.createIntent(), REQ_FILE)
                true
            } catch (_: Exception) {
                fileCallback = null
                false
            }
        }
    }

    companion object {
        const val HOME = "https://duckduckgo.com/"
        private const val SEARCH = "https://duckduckgo.com/?q="
        private const val KEY_ALLOWLIST = "allowlist"
        private const val REQ_FILE = 1

        fun toUrl(input: String): String {
            val t = input.trim()
            return when {
                t.isEmpty() -> HOME
                t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
                !t.contains(' ') && (t.contains('.') || t.startsWith("localhost")) -> "https://$t"
                else -> SEARCH + URLEncoder.encode(t, "UTF-8")
            }
        }
    }
}
