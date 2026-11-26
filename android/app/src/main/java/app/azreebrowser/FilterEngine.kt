package app.azreebrowser

import java.io.BufferedReader

/**
 * Network filter engine for uBlock Origin Lite's rules (see tools/gen_filters.py).
 * Plain Kotlin with no Android dependencies, so it can be unit-tested on the JVM.
 *
 * urlFilter syntax (declarativeNetRequest): `||` host anchor, `|` start/end anchor,
 * `*` wildcard, `^` separator. Matching is case-insensitive.
 */
class FilterEngine private constructor() {

    private class Rule(
        val kind: Char,
        pattern: String,
        val reqDomains: Array<String>?,
        val exReqDomains: Array<String>?,
        val initDomains: Array<String>?,
        val exInitDomains: Array<String>?,
        val party: Int,
    ) {
        val hostAnchor = pattern.startsWith("||")
        val startAnchor = !hostAnchor && pattern.startsWith("|")
        val endAnchor = pattern.length > 1 && pattern.endsWith("|")
        val body: String = pattern.removePrefix(if (hostAnchor) "||" else if (startAnchor) "|" else "")
            .let { if (endAnchor) it.dropLast(1) else it }
            .lowercase()

        /** Longest literal piece of the pattern: a URL without it can't match. */
        val needle: String = body.split('*', '^').maxByOrNull { it.length } ?: ""
    }

    private val blockedDomains = HashSet<String>(131072)
    private val tokenIndex = HashMap<String, MutableList<Rule>>()
    private val domainIndex = HashMap<String, MutableList<Rule>>()
    private val untokenized = ArrayList<Rule>()

    val domainCount get() = blockedDomains.size

    /** True if a request for [url], made by a page on [pageHost], should be blocked. */
    fun shouldBlock(url: String, pageHost: String?): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        val host = hostOf(u) ?: return false
        val page = pageHost?.lowercase()
        val thirdParty = page != null && baseDomain(host) != baseDomain(page)

        var blocked = matchesDomainSet(host)
        var allowed = false
        val hostStart = u.indexOf("://") + 3
        fun visit(rules: List<Rule>?) {
            if (rules == null) return
            for (r in rules) {
                if (r.kind == 'b' && blocked) continue
                if (r.kind != 'b' && allowed) continue
                if (r.needle.isNotEmpty() && !u.contains(r.needle)) continue
                if (!conditionsMatch(r, host, page, thirdParty)) continue
                if (r.body.isNotEmpty() && !patternMatches(r, u, hostStart, host.length)) continue
                if (r.kind == 'b') blocked = true else allowed = true
            }
        }
        forEachParent(host) { visit(domainIndex[it]) }
        for (t in tokensOf(u)) visit(tokenIndex[t])
        visit(untokenized)
        return blocked && !allowed
    }

    private fun matchesDomainSet(host: String): Boolean {
        var found = false
        forEachParent(host) { if (!found && it in blockedDomains) found = true }
        return found
    }

    private fun conditionsMatch(r: Rule, host: String, page: String?, thirdParty: Boolean): Boolean {
        if (r.party == 1 && thirdParty) return false
        if (r.party == 3 && !thirdParty) return false
        if (r.reqDomains != null && !inDomains(host, r.reqDomains)) return false
        if (r.exReqDomains != null && inDomains(host, r.exReqDomains)) return false
        if (r.initDomains != null && (page == null || !inDomains(page, r.initDomains))) return false
        if (r.exInitDomains != null && page != null && inDomains(page, r.exInitDomains)) return false
        return true
    }

    private fun inDomains(host: String, list: Array<String>): Boolean =
        list.any { host == it || (host.endsWith(it) && host[host.length - it.length - 1] == '.') }

    private fun patternMatches(r: Rule, u: String, hostStart: Int, hostLen: Int): Boolean {
        if (r.hostAnchor) {
            // Must start at the beginning of a host label.
            var i = hostStart
            val hostEnd = hostStart + hostLen
            while (i < hostEnd) {
                if (globMatch(r.body, 0, u, i, r.endAnchor)) return true
                val dot = u.indexOf('.', i)
                if (dot < 0 || dot >= hostEnd) break
                i = dot + 1
            }
            return false
        }
        if (r.startAnchor) return globMatch(r.body, 0, u, 0, r.endAnchor)
        val lit = r.body.takeWhile { it != '*' && it != '^' }
        if (lit.isEmpty()) {
            for (i in 0..u.length) if (globMatch(r.body, 0, u, i, r.endAnchor)) return true
            return false
        }
        var i = u.indexOf(lit)
        while (i >= 0) {
            if (globMatch(r.body, 0, u, i, r.endAnchor)) return true
            i = u.indexOf(lit, i + 1)
        }
        return false
    }

    private fun globMatch(p: String, pi0: Int, u: String, ui0: Int, endAnchor: Boolean): Boolean {
        var pi = pi0
        var ui = ui0
        while (pi < p.length) {
            when (val c = p[pi]) {
                '*' -> {
                    while (pi < p.length && p[pi] == '*') pi++
                    if (pi == p.length) return true
                    for (k in ui..u.length) if (globMatch(p, pi, u, k, endAnchor)) return true
                    return false
                }
                '^' -> {
                    if (ui == u.length) {
                        // '^' also matches the end of the URL.
                        pi++
                        continue
                    }
                    if (!isSeparator(u[ui])) return false
                    pi++; ui++
                }
                else -> {
                    if (ui >= u.length || u[ui] != c) return false
                    pi++; ui++
                }
            }
        }
        return !endAnchor || ui == u.length
    }

    private fun addRule(r: Rule) {
        val token = bestToken(r)
        when {
            token != null -> tokenIndex.getOrPut(token) { ArrayList(1) }.add(r)
            r.reqDomains != null -> r.reqDomains.forEach { domainIndex.getOrPut(it) { ArrayList(1) }.add(r) }
            else -> untokenized.add(r)
        }
    }

    /** Longest literal run in the pattern that is guaranteed to be a whole URL token. */
    private fun bestToken(r: Rule): String? {
        val b = r.body
        var best: String? = null
        var i = 0
        while (i < b.length) {
            if (!isTokenChar(b[i])) { i++; continue }
            var j = i
            while (j < b.length && isTokenChar(b[j])) j++
            val leftOk = if (i == 0) r.hostAnchor || r.startAnchor else b[i - 1] != '*'
            val rightOk = if (j == b.length) r.endAnchor else b[j] != '*'
            if (leftOk && rightOk && j - i >= 2 && (best == null || j - i > best.length)) best = b.substring(i, j)
            i = j
        }
        return best
    }

    companion object {
        fun load(reader: BufferedReader): FilterEngine {
            val e = FilterEngine()
            fun arr(s: String) = if (s.isEmpty()) null else s.split(',').toTypedArray()
            reader.forEachLine { line ->
                if (line.isEmpty() || line[0] == '#') return@forEachLine
                if (line[0] == 'd') {
                    e.blockedDomains.add(line.substring(2))
                    return@forEachLine
                }
                val f = line.split('\t')
                if (f.size < 7) return@forEachLine
                // 'p' (allow-all on a page) is treated as a request exception for simplicity.
                e.addRule(Rule(if (f[0] == "p") 'a' else f[0][0], f[1],
                    arr(f[2]), arr(f[3]), arr(f[4]), arr(f[5]), f[6].toIntOrNull() ?: 0))
            }
            return e
        }

        fun hostOf(url: String): String? {
            val s = url.indexOf("://")
            if (s < 0) return null
            var start = s + 3
            var end = start
            while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
            val at = url.lastIndexOf('@', end - 1)
            if (at >= start) start = at + 1
            var h = url.substring(start, end)
            if (h.startsWith("[")) return h
            val colon = h.indexOf(':')
            if (colon >= 0) h = h.substring(0, colon)
            return h.trimEnd('.').ifEmpty { null }
        }

        /** Approximate registrable domain ("eTLD+1") without a public suffix list. */
        fun baseDomain(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val sld = parts[parts.size - 2]
            val n = if (parts.last().length == 2 && sld.length <= 3 && parts.size >= 3) 3 else 2
            return parts.takeLast(n).joinToString(".")
        }

        private inline fun forEachParent(host: String, f: (String) -> Unit) {
            var h = host
            while (true) {
                f(h)
                val dot = h.indexOf('.')
                if (dot < 0) return
                h = h.substring(dot + 1)
            }
        }

        private fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'
        private fun isSeparator(c: Char) = !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

        private fun tokensOf(u: String): Set<String> {
            val out = HashSet<String>()
            var i = 0
            while (i < u.length) {
                if (!isTokenChar(u[i])) { i++; continue }
                var j = i
                while (j < u.length && isTokenChar(u[j])) j++
                if (j - i >= 2) out.add(u.substring(i, j))
                i = j
            }
            return out
        }
    }
}
