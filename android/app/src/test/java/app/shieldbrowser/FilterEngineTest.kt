package app.shieldbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class FilterEngineTest {
    companion object {
        lateinit var engine: FilterEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            val f = listOf("src/main/assets/filters.txt", "app/src/main/assets/filters.txt")
                .map(::File).first { it.exists() }
            engine = f.bufferedReader().use { FilterEngine.load(it) }
        }
    }

    private fun blocked(url: String, page: String = "news.example.org") = engine.shouldBlock(url, page)

    @Test fun blocksAdAndTrackerDomains() {
        assertTrue(blocked("https://securepubads.g.doubleclick.net/tag/js/gpt.js"))
        assertTrue(blocked("https://www.google-analytics.com/analytics.js"))
        assertTrue(blocked("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(blocked("https://connect.facebook.net/en_US/fbevents.js"))
        assertTrue(blocked("https://static.ads-twitter.com/uwt.js"))
    }

    @Test fun blocksUrlPatterns() {
        assertTrue(blocked("https://cdn.somesite.net/vast.php?idzone=123"))
        assertTrue(blocked("https://www.googletagmanager.com/gtag/js?id=G-XXXX"))
    }

    @Test fun allowsNormalContent() {
        assertFalse(blocked("https://en.wikipedia.org/wiki/Main_Page", "en.wikipedia.org"))
        assertFalse(blocked("https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg", "en.wikipedia.org"))
        assertFalse(blocked("https://www.google.com/search?q=hello", "www.google.com"))
        assertFalse(blocked("https://github.com/uBlockOrigin/uBOL-home", "github.com"))
        assertFalse(blocked("https://fonts.googleapis.com/css2?family=Roboto"))
        assertFalse(blocked("https://cdnjs.cloudflare.com/ajax/libs/jquery/3.7.1/jquery.min.js"))
        assertFalse(blocked("https://i.ytimg.com/vi/abc/hqdefault.jpg", "www.youtube.com"))
    }

    @Test fun ignoresNonHttp() {
        assertFalse(blocked("data:image/png;base64,AAAA"))
        assertFalse(blocked("blob:https://example.com/123"))
    }

    @Test fun helpers() {
        assertTrue(FilterEngine.hostOf("https://user@a.b.com:8080/x?y") == "a.b.com")
        assertTrue(FilterEngine.baseDomain("www.bbc.co.uk") == "bbc.co.uk")
        assertTrue(FilterEngine.baseDomain("a.b.example.com") == "example.com")
    }

    @Test fun isFast() {
        val urls = (0 until 20000).map { "https://cdn$it.example$it.com/assets/js/app.$it.min.js?v=$it&ref=home" }
        urls.take(2000).forEach { blocked(it) } // JIT warm-up
        val t = System.nanoTime()
        urls.forEach { blocked(it) }
        val perUrlMicros = (System.nanoTime() - t) / 1000 / urls.size
        println("~${perUrlMicros}µs per URL, ${engine.domainCount} domains")
        assertTrue("too slow: ${perUrlMicros}µs per URL", perUrlMicros < 100)
    }
}
