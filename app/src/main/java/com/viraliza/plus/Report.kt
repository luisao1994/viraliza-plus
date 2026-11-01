package com.viraliza.plus

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Junta registro + telas salvas num arquivo só, para mandar ao Claude. */
object Report {

    fun build(c: Context): String {
        val sb = StringBuilder()
        val ver = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrDefault("?")
        val tk = Tk.installedPackage(c)
        val tkVer = tk?.let { runCatching { c.packageManager.getPackageInfo(it, 0).versionName }.getOrNull() } ?: "-"
        sb.append("=== RELATÓRIO VIRALIZA+ ===\n")
        sb.append("Data: ${Date()}\n")
        sb.append("App: v$ver · Celular: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("TikTok: $tk v$tkVer\n")
        sb.append("Robô conectado: ${BotService.isOn} · ativado nas configurações: ${Store.enabledInSettings(c)}\n")
        val dm = c.resources.displayMetrics
        sb.append("Tela: ${dm.widthPixels}x${dm.heightPixels} densidade ${dm.density}\n")
        sb.append("Config: ${Store.runConfig.describe()} · perfil ${Store.runConfig.profile}\n\n")
        sb.append("=== LINHA DO TEMPO DO ROBÔ ===\n").append(Store.traceText()).append("\n\n")
        val err = Store.lastError()
        if (err.isNotBlank()) sb.append("=== ÚLTIMO ERRO ===\n").append(err).append("\n\n")
        sb.append("=== REGISTRO ===\n").append(Log.all().lines().takeLast(500).joinToString("\n")).append("\n\n")
        for (f in Log.dumps().takeLast(5)) {
            sb.append("=== TELA: ${f.name} ===\n")
            sb.append(runCatching { f.readText() }.getOrDefault("(não li)").take(120_000)).append("\n\n")
        }
        return sb.toString()
    }

    /** Salva em Downloads/Viraliza e devolve o endereço do arquivo. */
    fun save(c: Context): Pair<Uri?, String> {
        val body = build(c)
        val name = "viraliza-relatorio-" + SimpleDateFormat("ddMM-HHmmss", Locale.US).format(Date()) + ".txt"
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
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
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Mandar relatório"
    )
}
