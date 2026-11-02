package com.viraliza.plus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Um post do TikTok na fila (ou nos resultados da pesquisa). */
class Post(
    var url: String,
    var author: String = "",
    var caption: String = "",
    var comments: Long = -1,      // total que o TikTok mostra (principais + respostas); -1 = desconhecido
    var likes: Long = -1,
    var age: String = "",         // "3d", "2025-10-2"... como apareceu no TikTok
    var exact: Long = -1,         // contagem exata (varredura) com os filtros usados
    var exactInfo: String = "",   // descrição dos filtros da contagem exata
    var liked: Long = 0,          // quantas curtidas o robô já deu neste post
    var done: Boolean = false,
    var selected: Boolean = true,
    var addedAt: Long = System.currentTimeMillis()
) {
    val key: String get() = Store.normUrl(url)

    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url); put("author", author); put("caption", caption)
        put("comments", comments); put("likes", likes); put("age", age)
        put("exact", exact); put("exactInfo", exactInfo)
        put("liked", liked); put("done", done); put("selected", selected); put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Post(
            o.optString("url"), o.optString("author"), o.optString("caption"),
            o.optLong("comments", -1), o.optLong("likes", -1), o.optString("age"),
            o.optLong("exact", -1), o.optString("exactInfo"),
            o.optLong("liked"), o.optBoolean("done"), o.optBoolean("selected", true), o.optLong("addedAt")
        )
    }
}

/** Perfil de velocidade (um por conta, se quiser). */
class Profile(
    var name: String,
    var minDelay: Double = 1.0,    // segundos entre curtidas (mínimo)
    var maxDelay: Double = 3.0,    // segundos entre curtidas (máximo)
    var workMinutes: Int = 15,     // trabalha X minutos seguidos (0 = sem limite)
    var workLikes: Int = 0,        // ou X curtidas seguidas (0 = sem limite)
    var pauseMin: Int = 20,        // pausa mínima em minutos
    var pauseMax: Int = 30,        // pausa máxima em minutos
    var dailyLimit: Int = 0        // 0 = sem limite
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("minDelay", minDelay); put("maxDelay", maxDelay)
        put("workMinutes", workMinutes); put("workLikes", workLikes)
        put("pauseMin", pauseMin); put("pauseMax", pauseMax); put("dailyLimit", dailyLimit)
    }

    fun summary(): String {
        val d = if (minDelay == maxDelay) "${fmt(minDelay)}s" else "${fmt(minDelay)}–${fmt(maxDelay)}s"
        val work = when {
            workMinutes > 0 && workLikes > 0 -> "curte $workMinutes min ou $workLikes"
            workMinutes > 0 -> "curte $workMinutes min"
            workLikes > 0 -> "curte $workLikes"
            else -> "sem pausas"
        }
        val pause = if (workMinutes > 0 || workLikes > 0) " · pausa $pauseMin–$pauseMax min" else ""
        val daily = if (dailyLimit > 0) " · até $dailyLimit/dia" else ""
        return "$d · $work$pause$daily"
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            o.optString("name"), o.optDouble("minDelay", 1.0), o.optDouble("maxDelay", 3.0),
            o.optInt("workMinutes", 15), o.optInt("workLikes", 0),
            o.optInt("pauseMin", 20), o.optInt("pauseMax", 30), o.optInt("dailyLimit", 0)
        )

        fun fmt(d: Double): String =
            if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.1f", d).replace('.', ',')
    }
}

enum class Which(val label: String) { MAIN("Só principais"), REPLIES("Só respostas"), BOTH("Principais + respostas") }
enum class Order(val label: String) { RECENT("Mais recentes"), RELEVANT("Mais relevantes") }

/** O que curtir em cada post. */
class RunConfig(
    var which: Which = Which.MAIN,
    var order: Order = Order.RECENT,
    var maxAgeMin: Long = 0,      // 0 = qualquer idade
    var perPost: Int = 0,         // 0 = todos
    var profile: String = "Conta forte",
    var fastScroll: Boolean = false   // rolagem rápida
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("which", which.name); put("order", order.name); put("maxAgeMin", maxAgeMin)
        put("perPost", perPost); put("profile", profile); put("fastScroll", fastScroll)
    }

    fun describe(): String {
        val age = if (maxAgeMin > 0) " · até ${ageLabel(maxAgeMin)}" else ""
        return "${which.label} · ${order.label}$age" + if (fastScroll) " · rolagem rápida" else ""
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) RunConfig() else RunConfig(
            runCatching { Which.valueOf(o.optString("which")) }.getOrDefault(Which.MAIN),
            runCatching { Order.valueOf(o.optString("order")) }.getOrDefault(Order.RECENT),
            o.optLong("maxAgeMin"), o.optInt("perPost"), o.optString("profile", "Conta forte"),
            o.optBoolean("fastScroll", false)
        )

        fun ageLabel(min: Long): String = when {
            min < 60 -> "$min min"
            min < 1440 -> "${min / 60}h"
            else -> "${min / 1440} dias"
        }
    }
}

/** Filtros da pesquisa no TikTok. */
class SearchConfig(
    var term: String = "",
    var date: Int = 0,      // 0 tudo, 1 = 24h, 2 = semana, 3 = mês, 4 = 3 meses, 5 = 6 meses
    var sort: Int = 0,      // 0 relevância, 1 mais curtidos
    var count: Int = 20
) {
    fun toJson(): JSONObject = JSONObject().apply { put("term", term); put("date", date); put("sort", sort); put("count", count) }
    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) SearchConfig() else
            SearchConfig(o.optString("term"), o.optInt("date"), o.optInt("sort"), o.optInt("count", 20))
    }
}

/** Guarda tudo no próprio celular (SharedPreferences em JSON). */
object Store {
    private const val P = "viraliza"
    private lateinit var ctx: Context
    private val listeners = mutableListOf<() -> Unit>()

    private var crashHook = false

    fun init(c: Context) {
        ctx = c.applicationContext
        Log.init(c)
        if (!crashHook) {
            crashHook = true
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching { saveError("Travou: " + android.util.Log.getStackTraceString(e)) }
                prev?.uncaughtException(t, e)
            }
        }
    }

    /** Guarda o último erro grave (aparece em Ajustes) para eu conseguir corrigir. */
    fun saveError(s: String) {
        val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
        sp().edit().putString("lastError", "$stamp\n" + s.take(4000)).commit()
    }

    /** Linha do tempo do robô (ligou, conectou, desligou...). */
    fun trace(s: String) {
        val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
        val old = sp().getString("trace", "") ?: ""
        val lines = (old.lines().filter { it.isNotBlank() } + "$stamp  $s").takeLast(12)
        sp().edit().putString("trace", lines.joinToString("\n")).commit()
    }

    fun traceText(): String = sp().getString("trace", "") ?: ""

    fun lastError(): String = sp().getString("lastError", "") ?: ""
    fun clearError() { sp().edit().remove("lastError").apply() }

    /** O robô está marcado como ligado nas configurações de Acessibilidade? */
    fun enabledInSettings(c: Context): Boolean {
        val s = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith(c.packageName + "/") }
    }
    private fun sp() = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() {
        val copy = listeners.toList()
        android.os.Handler(android.os.Looper.getMainLooper()).post { copy.forEach { it() } }
    }

    // --- fila ---
    @Synchronized fun queue(): MutableList<Post> = readPosts("queue")
    @Synchronized fun saveQueue(list: List<Post>) { writePosts("queue", list); changed() }

    /** Adiciona (ou atualiza) um post na fila. Retorna false se já existia. */
    @Synchronized fun addToQueue(p: Post): Boolean {
        val q = queue()
        val old = q.firstOrNull { it.key == p.key }
        if (old != null) {
            if (p.author.isNotBlank()) old.author = p.author
            if (p.caption.isNotBlank()) old.caption = p.caption
            if (p.comments >= 0) old.comments = p.comments
            if (p.likes >= 0) old.likes = p.likes
            if (p.age.isNotBlank()) old.age = p.age
            saveQueue(q)
            return false
        }
        q.add(p)
        saveQueue(q)
        return true
    }

    @Synchronized fun updatePost(url: String, f: (Post) -> Unit) {
        val q = queue()
        val k = normUrl(url)
        q.firstOrNull { it.key == k }?.let { f(it); saveQueue(q) }
    }

    // --- resultados da pesquisa ---
    @Synchronized fun results(): MutableList<Post> = readPosts("results")
    @Synchronized fun saveResults(list: List<Post>) { writePosts("results", list); changed() }
    @Synchronized fun addResult(p: Post) {
        val r = results()
        if (r.none { it.key == p.key }) { p.selected = false; r.add(p); saveResults(r) }
    }

    private fun readPosts(k: String): MutableList<Post> {
        val arr = runCatching { JSONArray(sp().getString(k, "[]")) }.getOrDefault(JSONArray())
        return MutableList(arr.length()) { Post.fromJson(arr.getJSONObject(it)) }
    }

    private fun writePosts(k: String, list: List<Post>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        sp().edit().putString(k, arr.toString()).apply()
    }

    // --- perfis ---
    @Synchronized fun profiles(): MutableList<Profile> {
        val raw = sp().getString("profiles", null)
        if (raw == null) {
            val def = mutableListOf(
                Profile("Conta forte", 1.0, 3.0, 15, 0, 20, 30, 0),
                Profile("Conta nova", 5.0, 10.0, 10, 0, 40, 60, 300)
            )
            saveProfiles(def)
            return def
        }
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return MutableList(arr.length()) { Profile.fromJson(arr.getJSONObject(it)) }
    }

    @Synchronized fun saveProfiles(list: List<Profile>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        sp().edit().putString("profiles", arr.toString()).apply()
        changed()
    }

    fun profile(name: String): Profile = profiles().let { l -> l.firstOrNull { it.name == name } ?: l.firstOrNull() ?: Profile("Padrão") }

    // --- configurações ---
    var runConfig: RunConfig
        get() = RunConfig.fromJson(runCatching { JSONObject(sp().getString("run", "{}")!!) }.getOrNull())
        set(v) { sp().edit().putString("run", v.toJson().toString()).apply() }

    var searchConfig: SearchConfig
        get() = SearchConfig.fromJson(runCatching { JSONObject(sp().getString("search", "{}")!!) }.getOrNull())
        set(v) { sp().edit().putString("search", v.toJson().toString()).apply() }

    // --- contador diário ---
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Synchronized fun likedToday(): Int =
        if (sp().getString("dayKey", "") == today()) sp().getInt("dayCount", 0) else 0

    @Synchronized fun addLikedToday() {
        val n = likedToday() + 1
        sp().edit().putString("dayKey", today()).putInt("dayCount", n).apply()
    }

    @Synchronized fun totalLiked(): Long = sp().getLong("totalLiked", 0)
    @Synchronized fun addTotal() { sp().edit().putLong("totalLiked", totalLiked() + 1).apply() }

    // --- comentários já curtidos por post ---
    // O TikTok não diz pro robô se o coração está vermelho; então guardamos o que já curtimos.
    // Sem isso, rodar o mesmo post de novo DESCURTIRIA os comentários.
    private fun likedFile(url: String): java.io.File {
        val d = java.io.File(ctx.filesDir, "curtidos").apply { mkdirs() }
        return java.io.File(d, Integer.toHexString(normUrl(url).hashCode()) + ".txt")
    }

    @Synchronized fun likedKeys(url: String): MutableSet<String> =
        runCatching { likedFile(url).takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.toHashSet() }
            .getOrNull() ?: HashSet()

    @Synchronized fun addLikedKey(url: String, key: String) {
        runCatching { likedFile(url).appendText(key.replace("\n", " ") + "\n") }
    }

    // --- utilidades ---
    fun normUrl(u: String): String {
        val m = Regex("/video/(\\d+)").find(u)
        if (m != null) return "v:" + m.groupValues[1]
        return u.trim().substringBefore("?").trimEnd('/').lowercase()
    }

    /** Extrai o primeiro link do TikTok de um texto compartilhado. */
    fun extractUrl(s: String?): String? {
        if (s == null) return null
        return Regex("https?://[^\\s]*tiktok\\.com/[^\\s]*").find(s)?.value?.trimEnd('.', ',', ')')
    }
}

/** Registro do que o robô fez. Fica salvo num arquivo, então não some quando o app fecha. */
object Log {
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
    private var dir: java.io.File? = null

    fun init(c: Context) { dir = c.applicationContext.filesDir }
    private fun file(): java.io.File? = dir?.let { java.io.File(it, "registro.txt") }

    @Synchronized fun i(msg: String) {
        android.util.Log.i("Viraliza", msg)
        val f = file() ?: return
        runCatching {
            f.appendText(fmt.format(Date()) + "  " + msg + "\n")
            if (f.length() > 250_000) {
                val keep = f.readLines().takeLast(1200)
                f.writeText(keep.joinToString("\n") + "\n")
            }
        }
    }

    @Synchronized fun all(): String = runCatching { file()?.readText() ?: "" }.getOrDefault("")
    @Synchronized fun clear() { runCatching { file()?.delete() } }

    // --- fotos da tela (árvore de botões) salvas quando algo dá errado ---
    private fun dumpsDir(): java.io.File? = dir?.let { java.io.File(it, "telas").apply { mkdirs() } }

    @Synchronized fun saveDump(reason: String, body: String) {
        val d = dumpsDir() ?: return
        val name = SimpleDateFormat("ddMM-HHmmss", Locale.US).format(Date()) + "-" + reason.replace(Regex("[^a-zA-Z0-9-]"), "-").take(40) + ".txt"
        runCatching {
            java.io.File(d, name).writeText(body)
            d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        }
        i("📸 tela salva: $reason")
    }

    @Synchronized fun dumps(): List<java.io.File> =
        dumpsDir()?.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()

    @Synchronized fun clearDumps() { dumpsDir()?.listFiles()?.forEach { it.delete() } }
}
