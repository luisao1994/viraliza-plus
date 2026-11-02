package com.viraliza.plus

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Passa as configurações (perfis, fila, pesquisas, ajustes) para outro celular por um arquivo. */
object Backup {

    class Parsed(
        val profiles: List<Profile>,
        val queue: List<Post>,
        val results: List<Post>,
        val run: JSONObject?,
        val search: JSONObject?,
        val liked: JSONObject?
    ) {
        fun summary() = "${profiles.size} perfis de velocidade · ${queue.size} posts na fila · ${results.size} resultados de pesquisa"
    }

    fun build(): String = JSONObject().apply {
        put("app", "viraliza+")
        put("formato", 1)
        put("data", Date().toString())
        put("profiles", JSONArray().also { a -> Store.profiles().forEach { a.put(it.toJson()) } })
        put("queue", JSONArray().also { a -> Store.queue().forEach { a.put(it.toJson()) } })
        put("results", JSONArray().also { a -> Store.results().forEach { a.put(it.toJson()) } })
        put("run", Store.runConfig.toJson())
        put("search", Store.searchConfig.toJson())
        put("liked", JSONObject(Store.likedDump()))
    }.toString(1)

    /** Salva em Downloads/Viraliza e devolve (endereço pra compartilhar, caminho pra mostrar). */
    fun save(c: Context): Pair<Uri?, String> {
        val body = build()
        val name = "viraliza-config-" + SimpleDateFormat("ddMM-HHmm", Locale.US).format(Date()) + ".json"
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Viraliza")
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            uri?.let { u -> c.contentResolver.openOutputStream(u)?.use { it.write(body.toByteArray()) } }
            uri to "Downloads/Viraliza/$name"
        } else {
            val dir = c.getExternalFilesDir(null) ?: c.filesDir
            val f = File(dir, name)
            f.writeText(body)
            null to f.absolutePath
        }
    }

    fun shareIntent(uri: Uri): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Configurações do Viraliza+ (no outro celular: Ajustes › Importar configurações)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Mandar configurações"
    )

    /** Lê o arquivo escolhido. Lança erro com mensagem amigável se não for do Viraliza+. */
    fun read(c: Context, uri: Uri): Parsed {
        val text = c.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("Não consegui abrir o arquivo")
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || o.optString("app") != "viraliza+") throw IllegalArgumentException("Esse arquivo não é de configurações do Viraliza+")
        fun posts(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { Post.fromJson(a.getJSONObject(it)) } } ?: emptyList()
        val profs = o.optJSONArray("profiles")?.let { a -> List(a.length()) { Profile.fromJson(a.getJSONObject(it)) } } ?: emptyList()
        return Parsed(profs, posts("queue"), posts("results"), o.optJSONObject("run"), o.optJSONObject("search"), o.optJSONObject("liked"))
    }

    /** Aplica. replace = troca tudo; senão junta (perfis pelo nome, posts pelo link). */
    fun apply(p: Parsed, replace: Boolean): String {
        if (replace) {
            if (p.profiles.isNotEmpty()) Store.saveProfiles(p.profiles)
            Store.saveQueue(p.queue)
            Store.saveResults(p.results)
            p.run?.let { Store.runConfig = RunConfig.fromJson(it) }
            p.search?.let { Store.searchConfig = SearchConfig.fromJson(it) }
            p.liked?.let { Store.likedRestore(it, replace = true) }
            return "Configurações substituídas: ${p.summary()}"
        }
        val profs = Store.profiles()
        var newProfs = 0
        for (np in p.profiles) {
            val i = profs.indexOfFirst { it.name == np.name }
            if (i >= 0) profs[i] = np else { profs.add(np); newProfs++ }
        }
        Store.saveProfiles(profs)
        val q = Store.queue()
        val keys = q.map { it.key }.toHashSet()
        val addQ = p.queue.filter { keys.add(it.key) }
        Store.saveQueue(q + addQ)
        val r = Store.results()
        val rkeys = r.map { it.key }.toHashSet()
        Store.saveResults(r + p.results.filter { rkeys.add(it.key) })
        p.liked?.let { Store.likedRestore(it, replace = false) }
        return "Juntado: ${addQ.size} posts novos na fila · $newProfs perfis novos (${p.profiles.size - newProfs} atualizados)"
    }
}
