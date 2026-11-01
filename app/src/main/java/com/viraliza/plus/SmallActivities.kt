package com.viraliza.plus

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Tela invisível: lê o link que o robô copiou no TikTok e fecha na hora. */
class ClipActivity : Activity() {
    private var delivered = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || delivered) return
        delivered = true
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val txt = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        BotService.instance?.deliverClip(txt)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!delivered) BotService.instance?.deliverClip(null)
        super.onDestroy()
    }
}

/** Recebe "Compartilhar > Viraliza+" do TikTok e coloca o post na fila. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val txt = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val url = Store.extractUrl(txt)
        if (url == null) {
            Toast.makeText(this, "Não achei um link do TikTok aqui", Toast.LENGTH_SHORT).show()
        } else {
            val caption = txt!!.replace(url, "").trim().take(200)
            val isNew = Store.addToQueue(Post(url, caption = caption))
            Toast.makeText(this, if (isNew) "Adicionado à fila do Viraliza+ ✓" else "Esse post já está na fila", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
