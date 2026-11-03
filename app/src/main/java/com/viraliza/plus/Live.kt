package com.viraliza.plus

import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Modo Live: toca sem parar num ponto da tela (em cima do vídeo da live)
 * para soltar os coraçõezinhos. O alvo ❤ é arrastável.
 */
object Live {

    /** Toques por segundo que dá para escolher. */
    val SPEEDS = intArrayOf(3, 5, 8, 10, 12, 15, 18, 20, 25, 30)

    @Volatile var speed: Int = 15
    @Volatile var taps: Long = 0
    @Volatile var aiming = false

    private var target: TextView? = null
    private var tParams: WindowManager.LayoutParams? = null
    private const val SIZE = 64

    private fun sp(c: Context) = c.getSharedPreferences("live", Context.MODE_PRIVATE)

    fun load(c: Context) {
        speed = sp(c).getInt("speed", 15)
    }

    fun faster(c: Context) = setSpeed(c, SPEEDS.firstOrNull { it > speed } ?: SPEEDS.last())
    fun slower(c: Context) = setSpeed(c, SPEEDS.lastOrNull { it < speed } ?: SPEEDS.first())

    private fun setSpeed(c: Context, v: Int) {
        speed = v
        sp(c).edit().putInt("speed", v).apply()
    }

    /** Centro do alvo na tela (onde o robô toca). */
    fun point(svc: BotService): Pair<Float, Float> {
        val p = tParams
        val size = svc.dp(SIZE)
        val half = size / 2f
        if (p != null) return (p.x + half) to (p.y + half)
        val s = sp(svc)
        val x = s.getInt("x", svc.W / 2 - size / 2)
        val y = s.getInt("y", (svc.H * 0.45f).toInt() - size / 2)
        return (x + half) to (y + half)
    }

    // ---------------- alvo ----------------

    @SuppressLint("ClickableViewAccessibility")
    fun showTarget(svc: BotService) {
        if (target != null) { setTouchable(svc, true); return }
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = svc.dp(SIZE)
        val sp = sp(svc)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt("x", svc.W / 2 - size / 2)
            y = sp.getInt("y", (svc.H * 0.45f).toInt() - size / 2)
        }
        val v = TextView(svc).apply {
            text = "❤"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(110, 255, 77, 122))
                setStroke(svc.dp(2), Color.WHITE)
            }
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (e.rawX - tx).toInt()
                    params.y = sy + (e.rawY - ty).toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                }
                MotionEvent.ACTION_UP -> sp.edit().putInt("x", params.x).putInt("y", params.y).apply()
            }
            true
        }
        runCatching { wm.addView(v, params) }.onSuccess {
            target = v; tParams = params
        }.onFailure { Store.saveError("Alvo da live não abriu: ${it.message}") }
    }

    /** Enquanto curte, o alvo fica "fantasma": os toques passam por ele e vão pra live. */
    fun setTouchable(svc: BotService, on: Boolean) {
        val v = target ?: return
        val p = tParams ?: return
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        v.alpha = if (on) 1f else 0.35f
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hideTarget(svc: BotService) {
        val v = target ?: return
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(v) }
        target = null; tParams = null
    }

    // ---------------- curtir ----------------

    /** Roda na linha do robô até você tocar ■. */
    fun run(svc: BotService) {
        taps = 0
        svc.main.post { setTouchable(svc, false) }
        svc.nap(400)
        val started = System.currentTimeMillis()
        var lastShow = 0L
        try {
            while (true) {
                svc.nap(1)               // respeita ⏸ e ■
                svc.ensureTikTok(1500)   // se sair do TikTok, para de tocar e espera você voltar
                val (cx, cy) = point(svc)
                val sent = burst(svc, cx, cy)
                taps += sent
                val now = System.currentTimeMillis()
                if (now - lastShow > 700) {
                    lastShow = now
                    val secs = (now - started) / 1000
                    svc.status("Curtindo a live ❤", "$taps toques · $speed/s · ${secs / 60}min ${secs % 60}s")
                }
            }
        } finally {
            svc.main.post { setTouchable(svc, true) }
            Log.i("Live: $taps toques")
        }
    }

    /** Manda um pacote de toques rápidos num gesto só (cerca de meio segundo). */
    private fun burst(svc: BotService, cx: Float, cy: Float): Int {
        val tps = speed.coerceIn(1, 40)
        val gap = 1000L / tps                       // tempo entre um toque e outro
        val press = minOf(25L, gap / 2).coerceAtLeast(5L)
        val n = (600L / gap).toInt().coerceIn(1, 18) // fica abaixo do limite de 20 do Android
        val j = svc.dp(10)
        val b = GestureDescription.Builder()
        for (i in 0 until n) {
            val x = (cx + Random.nextInt(-j, j + 1)).coerceIn(1f, svc.W - 2f)
            val y = (cy + Random.nextInt(-j, j + 1)).coerceIn(1f, svc.H - 2f)
            val p = Path().apply { moveTo(x, y) }
            b.addStroke(GestureDescription.StrokeDescription(p, i * gap, press))
        }
        val g = b.build()
        val latch = CountDownLatch(1)
        var ok = false
        svc.main.post {
            val sent = svc.dispatchGesture(g, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (!sent) latch.countDown()
        }
        try { latch.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { throw StopException() }
        if (!ok) svc.nap(150)
        return if (ok) n else 0
    }
}
