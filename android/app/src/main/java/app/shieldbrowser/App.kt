package app.shieldbrowser

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.webkit.WebView
import kotlin.concurrent.thread

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName().endsWith(INCOGNITO_PROCESS)) {
            // Start every incognito session from nothing, even if the last one was killed.
            wipeIncognitoData(this)
            WebView.setDataDirectorySuffix("incognito")
        }
        thread(name = "filters", priority = Thread.MIN_PRIORITY) { filters(this) }
    }

    companion object {
        const val INCOGNITO_PROCESS = ":incognito"

        @Volatile private var engine: FilterEngine? = null

        /** Loaded once per process; the first caller waits (~0.3 s), later calls are free. */
        fun filters(ctx: Context): FilterEngine = engine ?: synchronized(this) {
            engine ?: ctx.assets.open("filters.txt").bufferedReader()
                .use { FilterEngine.load(it) }
                .also { engine = it }
        }

        fun wipeIncognitoData(ctx: Context) {
            ctx.dataDir.listFiles()?.filter { it.name.startsWith("app_webview_incognito") }
                ?.forEach { it.deleteRecursively() }
            ctx.cacheDir.listFiles()?.filter { it.name.contains("incognito") }
                ?.forEach { it.deleteRecursively() }
        }

        fun incognitoRunning(ctx: Context): Boolean =
            ctx.getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.any { it.processName.endsWith(INCOGNITO_PROCESS) } == true
    }
}
