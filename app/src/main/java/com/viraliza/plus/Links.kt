package com.viraliza.plus

import java.net.HttpURLConnection
import java.net.URL

/**
 * Link curto de compartilhar (vt.tiktok.com/…) carrega quem compartilhou: abrindo em outra conta,
 * o TikTok mostra "Fulano compartilhou este vídeo · Seguir de volta".
 * Aqui trocamos pelo link direto do vídeo (tiktok.com/@autor/video/123…), que abre igual em qualquer conta.
 */
object Links {
    private val cache = HashMap<String, String>()

    fun isShort(u: String) = Regex("^https?://(vt|vm)\\.tiktok\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(u.trim())

    /** Link direto do vídeo, sem rastreio. Se não der pra descobrir (sem internet...), devolve o original. Não chamar na tela principal. */
    @Synchronized fun clean(url: String): String {
        val u = url.trim()
        cache[u]?.let { return it }
        val out = if (isShort(u)) resolve(u) ?: u else strip(u)
        cache[u] = out
        if (out != u) Log.i("Link limpo: $out")
        return out
    }

    private fun strip(u: String): String =
        if (Regex("tiktok\\.com/@[^/]+/video/\\d+").containsMatchIn(u)) u.substringBefore("?") else u

    private fun resolve(start: String): String? {
        var cur = start
        repeat(5) {
            val c = (URL(cur).openConnection() as HttpURLConnection)
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 6000; c.readTimeout = 6000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                val code = runCatching { c.responseCode }.getOrNull() ?: return null
                val loc = c.getHeaderField("Location")
                if (code in 300..399 && loc != null) {
                    val next = URL(URL(cur), loc).toString()
                    Regex("https?://(www\\.|m\\.)?tiktok\\.com/@[^/?]+/video/\\d+").find(next)?.let { return it.value.replace("://m.", "://www.") }
                    cur = next
                } else return null
            } finally { c.disconnect() }
        }
        return null
    }
}
