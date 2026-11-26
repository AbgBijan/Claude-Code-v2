package app.shieldbrowser

import android.os.Bundle
import android.os.Process
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase

/**
 * Private browsing. Runs in the ":incognito" process with a separate WebView data
 * directory (see App), so it has its own cookie jar, storage and cache. Nothing is
 * cached to disk, history is not kept, screenshots and the recents preview are blocked,
 * and everything is erased when the window closes.
 */
class IncognitoActivity : BrowserActivity() {
    override val incognito = true

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        val closing = isFinishing
        if (closing) {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        }
        super.onDestroy()
        if (closing) {
            App.wipeIncognitoData(this)
            Process.killProcess(Process.myPid())
        }
    }
}
