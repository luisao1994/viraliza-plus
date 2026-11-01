package com.viraliza.plus

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.Calendar

/** Ajudantes para "ler" a tela do TikTok e entender textos. */
object Tk {
    val PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")

    fun installedPackage(ctx: Context): String? = PACKAGES.firstOrNull {
        runCatching { ctx.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false)
    }

    fun openUrl(ctx: Context, url: String): Boolean {
        val pkg = installedPackage(ctx) ?: return false
        return runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun openApp(ctx: Context): Boolean {
        val pkg = installedPackage(ctx) ?: return false
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Texto sem acento e minúsculo, para comparar. */
    fun norm(s: CharSequence?): String {
        if (s == null) return ""
        return Normalizer.normalize(s.toString(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase().trim()
    }

    fun label(n: AccessibilityNodeInfo): String = norm((n.text ?: "").toString() + " " + (n.contentDescription ?: ""))

    fun rect(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    /** Todos os nós da árvore (largura primeiro). */
    fun all(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        if (root == null) return out
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty() && out.size < 4000) {
            val n = q.removeFirst()
            out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return out
    }

    fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    /** Converte "1.234", "12,3 mil", "1,2K", "3 mi", "4.5M" em número. */
    fun parseCount(raw: String?): Long {
        if (raw == null) return -1
        val s = norm(raw).replace(" ", " ")
        val m = Regex("(\\d{1,3}(?:[.,\\s]\\d{3})+|\\d+(?:[.,]\\d+)?)\\s*(mil|mi|bi|k|m|b)?\\b").find(s) ?: return -1
        var num = m.groupValues[1]
        val suf = m.groupValues[2]
        val value: Double = if (suf.isEmpty()) {
            num.replace(Regex("[.,\\s]"), "").toDoubleOrNull() ?: return -1
        } else {
            num = num.replace(" ", "")
            // com sufixo, vírgula/ponto é decimal
            num.replace(',', '.').toDoubleOrNull() ?: return -1
        }
        val mult = when (suf) {
            "mil", "k" -> 1_000.0
            "mi", "m" -> 1_000_000.0
            "bi", "b" -> 1_000_000_000.0
            else -> 1.0
        }
        return (value * mult).toLong()
    }

    /**
     * Idade de um comentário/post em minutos a partir de "agora", "5min", "3h", "2d", "1 sem", "2-15", "2024-2-15"...
     * Retorna -1 se não reconhecer.
     */
    fun parseAgeMinutes(raw: String?): Long {
        if (raw == null) return -1
        val s = norm(raw).replace("atras", "").replace("ha ", "").replace("·", " ").trim()
        if (s.isEmpty() || s.length > 22) return -1
        if (s == "agora" || s == "now" || s == "agora mesmo" || s == "just now") return 0
        Regex("^(\\d+)\\s*(s|seg|segundos?|sec)$").find(s)?.let { return 0 }
        Regex("^(\\d+)\\s*(min|mins|minutos?|m)$").find(s)?.let { return it.groupValues[1].toLong() }
        Regex("^(\\d+)\\s*(h|hr|hrs|horas?|hours?)$").find(s)?.let { return it.groupValues[1].toLong() * 60 }
        Regex("^(\\d+)\\s*(d|dia|dias|days?)$").find(s)?.let { return it.groupValues[1].toLong() * 1440 }
        Regex("^(\\d+)\\s*(sem|semana|semanas|w|wk|weeks?)$").find(s)?.let { return it.groupValues[1].toLong() * 10080 }
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(s)?.let {
            return minutesSince(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("^(\\d{1,2})-(\\d{1,2})$").find(s)?.let {
            val now = Calendar.getInstance()
            var y = now.get(Calendar.YEAR)
            val mo = it.groupValues[1].toInt(); val d = it.groupValues[2].toInt()
            if (mo > now.get(Calendar.MONTH) + 1) y -= 1
            return minutesSince(y, mo, d)
        }
        Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$").find(s)?.let {
            val d = it.groupValues[1].toInt(); val mo = it.groupValues[2].toInt()
            var y = it.groupValues[3].toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
            if (y < 100) y += 2000
            return minutesSince(y, mo, d)
        }
        return -1
    }

    private fun minutesSince(y: Int, mo: Int, d: Int): Long {
        if (mo !in 1..12 || d !in 1..31) return -1
        val c = Calendar.getInstance(); c.set(y, mo - 1, d, 12, 0, 0)
        return ((System.currentTimeMillis() - c.timeInMillis) / 60000).coerceAtLeast(0)
    }

    /** Despeja a árvore da tela em texto (diagnóstico para calibrar o robô). */
    fun dump(root: AccessibilityNodeInfo?): String {
        if (root == null) return "(sem tela)"
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (sb.length > 400_000) return
            val r = rect(n)
            sb.append("  ".repeat(depth))
            sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?")
            n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
            n.text?.let { if (it.isNotEmpty()) sb.append(" t=\"").append(it.toString().replace("\n", "⏎").take(120)).append('"') }
            n.contentDescription?.let { if (it.isNotEmpty()) sb.append(" d=\"").append(it.toString().replace("\n", "⏎").take(120)).append('"') }
            val flags = buildString {
                if (n.isClickable) append("C"); if (n.isScrollable) append("S"); if (n.isSelected) append("sel")
                if (n.isChecked) append("chk"); if (n.isEditable) append("E")
            }
            if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
            sb.append(" ").append(r.toShortString()).append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return sb.toString()
    }
}
