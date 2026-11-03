package com.viraliza.plus

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Balão flutuante por cima do TikTok: mostra o que o robô está fazendo e tem os botões. */
class Overlay(private val svc: BotService) {

    private val wm = svc.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private var shown = false
    private var collapsed = false
    private val ctx = svc

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = ctx.dp(8)
        y = ctx.dp(110)
    }

    private val titleTv: TextView
    private val detailTv: TextView
    private val dot: View
    private val body: LinearLayout
    private val btnRow: LinearLayout
    private val addBtn: TextView
    private val pauseBtn: TextView
    private val stopBtn: TextView
    private val diagBtn: TextView
    private val liveBtn: TextView
    private val liveRow: LinearLayout
    private val liveGo: TextView
    private val liveClose: TextView
    private val speedTv: TextView
    private var liveMode = false
    val root: LinearLayout

    init {
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 18, 16, 20))
                cornerRadius = ctx.dpf(18f)
                setStroke(ctx.dp(1), Color.argb(90, 255, 77, 122))
            }
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF4D7A")) }
        }
        head.addView(dot, LinearLayout.LayoutParams(ctx.dp(9), ctx.dp(9)).apply { rightMargin = ctx.dp(7) })
        titleTv = tv("Viraliza+", 13f, Color.WHITE, true)
        head.addView(titleTv)
        root.addView(head)

        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        detailTv = tv("Abra um vídeo no TikTok e toque ＋ Fila", 11.5f, Color.argb(200, 255, 255, 255), false).apply {
            maxWidth = ctx.dp(210)
            maxLines = 3
        }
        body.addView(detailTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(3) })

        btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        addBtn = pill("＋ Fila") { onAdd() }
        pauseBtn = pill("⏸") { svc.togglePause() }
        stopBtn = pill("■") { svc.stopTask() }
        diagBtn = pill("🩺") { svc.saveDiagnostic() }
        liveBtn = pill("❤ Live") { openLive() }
        btnRow.addView(addBtn)
        btnRow.addView(liveBtn)
        btnRow.addView(pauseBtn)
        btnRow.addView(stopBtn)
        btnRow.addView(diagBtn)
        body.addView(btnRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(6) })

        // controles do Modo Live
        liveRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        liveGo = pill("▶ Curtir") { startLive() }
        val minus = pill("−") { Live.slower(ctx); showSpeed() }
        speedTv = tv("", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; minWidth = ctx.dp(46)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ctx.dp(6) }
        }
        val plus = pill("+") { Live.faster(ctx); showSpeed() }
        liveClose = pill("✕") { closeLive() }
        liveRow.addView(liveGo); liveRow.addView(minus); liveRow.addView(speedTv); liveRow.addView(plus); liveRow.addView(liveClose)
        body.addView(liveRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(6) })
        root.addView(body)
        Live.load(ctx)
        showSpeed()

        setRunning(false)
        attachDrag(head)
    }

    private fun tv(s: String, sp: Float, color: Int, bold: Boolean) = TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = Fonts.get(if (bold) 800 else 600)
        includeFontPadding = false
    }

    private fun pill(s: String, onClick: () -> Unit) = tv(s, 12.5f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        minWidth = ctx.dp(40)
        minHeight = ctx.dp(34)
        setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
        background = GradientDrawable().apply { setColor(Color.argb(60, 255, 255, 255)); cornerRadius = ctx.dpf(12f) }
        layoutParams = LinearLayout.LayoutParams(-2, ctx.dp(34)).apply { rightMargin = ctx.dp(6) }
        // responde assim que o dedo encosta: os toques do robô cancelariam um clique normal
        var last = 0L
        setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - last > 600) { last = now; v.alpha = 0.5f; v.postDelayed({ v.alpha = 1f }, 200); onClick() }
            }
            true
        }
    }

    private fun onAdd() {
        if (svc.busy) { svc.toast("Espere a tarefa atual terminar"); return }
        svc.startTask("Adicionar post") { Jobs.grabCurrent(it) }
    }

    // ---------------- Modo Live ----------------

    private fun showSpeed() { speedTv.text = "${Live.speed}/s" }

    private fun openLive() {
        if (svc.busy) { svc.toast("Espere a tarefa atual terminar"); return }
        liveMode = true
        Live.showTarget(svc)
        refreshLive(false)
        setStatus("Modo Live ❤", "Arraste o ❤ para cima do vídeo da live e toque ▶ Curtir")
    }

    private fun startLive() {
        if (svc.busy) return
        svc.startTask("Curtir live") { Live.run(it) }
    }

    private fun closeLive() {
        if (svc.busy) svc.stopTask()
        liveMode = false
        Live.hideTarget(svc)
        refreshLive(false)
        setStatus("Viraliza+", "")
    }

    private fun refreshLive(running: Boolean) {
        liveRow.visibility = if (liveMode) View.VISIBLE else View.GONE
        liveGo.visibility = if (running) View.GONE else View.VISIBLE
        liveClose.visibility = if (running) View.GONE else View.VISIBLE
        liveBtn.visibility = if (running || liveMode) View.GONE else View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (Math.abs(dx) > ctx.dp(6) || Math.abs(dy) > ctx.dp(6)) moved = true
                    if (moved) {
                        params.x = sx + dx; params.y = sy + dy
                        if (shown) runCatching { wm.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggleCollapse(); true }
                else -> false
            }
        }
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        body.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    fun show() {
        if (shown) return
        try {
            wm.addView(root, params); shown = true
            if (liveMode) Live.showTarget(svc)
            Store.trace("balão: na tela")
        } catch (e: Throwable) {
            Store.trace("balão: falhou ${e.javaClass.simpleName}")
            Store.saveError("Balão não abriu: " + android.util.Log.getStackTraceString(e))
        }
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(root) }
        shown = false
        Live.hideTarget(svc)
    }

    fun setStatus(title: String, detail: String) {
        titleTv.text = title
        detailTv.text = detail
        detailTv.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setRunning(on: Boolean) {
        pauseBtn.visibility = if (on) View.VISIBLE else View.GONE
        stopBtn.visibility = if (on) View.VISIBLE else View.GONE
        addBtn.visibility = if (on || liveMode) View.GONE else View.VISIBLE
        refreshLive(on)
        (dot.background as GradientDrawable).setColor(Color.parseColor(if (on) "#5FD08A" else "#FF4D7A"))
        if (!on) {
            pauseBtn.text = "⏸"
            if (liveMode) setStatus("Modo Live ❤", "Parado · ${Live.taps} toques. ▶ para curtir de novo")
            else if (titleTv.text.isNullOrBlank()) setStatus("Viraliza+", "")
        }
    }

    fun setPaused(p: Boolean) {
        pauseBtn.text = if (p) "▶" else "⏸"
        (dot.background as GradientDrawable).setColor(Color.parseColor(if (p) "#F0A04B" else "#5FD08A"))
        if (p) setStatus("Pausado", "Toque ▶ para continuar")
    }
}
