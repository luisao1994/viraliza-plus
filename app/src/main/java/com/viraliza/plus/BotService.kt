package com.viraliza.plus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.annotation.TargetApi
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StopException : RuntimeException()

/**
 * O "robô": serviço de acessibilidade que lê a tela do TikTok e toca nos botões.
 * As tarefas (curtir, contar, pesquisar, adicionar post) rodam numa linha separada.
 */
class BotService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotService? = null
        val isOn: Boolean get() = instance != null
    }

    val main = Handler(Looper.getMainLooper())
    lateinit var overlay: Overlay
    val hasOverlay: Boolean get() = ::overlay.isInitialized
    @Volatile private var worker: Thread? = null
    @Volatile var stopRequested = false
    @Volatile var paused = false
    @Volatile var currentTask: String = ""
    @Volatile private var foregroundPkg: String = ""
    /** Quantas vezes o TikTok saiu da frente (outro app, tela inicial...). */
    @Volatile var awayCount = 0

    private var clipLatch: CountDownLatch? = null
    @Volatile private var clipText: String? = null

    val busy: Boolean get() = worker?.isAlive == true

    val W: Int get() = resources.displayMetrics.widthPixels
    val H: Int get() = realHeight()

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Store.trace("robô: criado")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Store.init(this)
        Store.trace("robô: conectado ✓")
        try {
            T.init(this)
            Fonts.init(this)
            overlay = Overlay(this)
            main.postDelayed(visCheck, 500)
            Log.i("Robô ligado")
        } catch (e: Throwable) {
            Store.saveError("Erro ao ligar o robô: " + android.util.Log.getStackTraceString(e))
            Log.i("Erro ao ligar o robô: ${e.message}")
        }
        Store.changed()
    }

    private val visCheck = Runnable { updateOverlayVisibility() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val t = event.eventType
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            main.removeCallbacks(visCheck)
            main.postDelayed(visCheck, 350)
        }
    }

    /** App que está de verdade na frente (ignora teclado, barra do sistema e o balão). */
    private fun topAppPackage(): String? {
        return try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }
                .firstNotNullOfOrNull { it.root?.packageName?.toString() }
        } catch (_: Throwable) { null }
    }

    /** Balão só aparece com o TikTok na frente (ou enquanto o robô trabalha). */
    private fun updateOverlayVisibility() {
        if (!hasOverlay) return
        val top = topAppPackage() ?: return   // sem certeza: não mexe
        if (foregroundPkg in Tk.PACKAGES && top !in Tk.PACKAGES) awayCount++
        foregroundPkg = top
        if (top in Tk.PACKAGES || busy) overlay.show() else overlay.hide()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Store.trace("robô: desconectado")
        stopTask()
        instance = null
        main.post { if (hasOverlay) runCatching { overlay.hide() } }
        Store.changed()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Store.trace("robô: destruído")
        stopTask()
        instance = null
        if (hasOverlay) runCatching { overlay.hide() }
        Store.changed()
        super.onDestroy()
    }

    // ---------------- tarefas ----------------

    fun startTask(name: String, block: (BotService) -> Unit): Boolean {
        if (busy) {
            toast("O robô já está trabalhando. Pare a tarefa atual primeiro.")
            return false
        }
        stopRequested = false
        paused = false
        currentTask = name
        main.post { if (hasOverlay) { overlay.show(); overlay.setRunning(true) } }
        worker = Thread {
            try {
                Log.i("▶ $name")
                block(this)
            } catch (e: StopException) {
                Log.i("■ $name parado")
                status("Parado", "")
            } catch (e: Throwable) {
                Log.i("✖ Erro em $name: ${e.javaClass.simpleName} ${e.message}")
                status("Erro", e.message ?: e.javaClass.simpleName)
            } finally {
                currentTask = ""
                Store.changed()
                main.post { if (hasOverlay) overlay.setRunning(false); updateOverlayVisibility() }
            }
        }.apply { isDaemon = true; start() }
        Store.changed()
        return true
    }

    fun stopTask() {
        stopRequested = true
        paused = false
        worker?.interrupt()
    }

    fun togglePause() {
        paused = !paused
        main.post { if (hasOverlay) overlay.setPaused(paused) }
        Log.i(if (paused) "⏸ Pausado por você" else "▶ Continuando")
    }

    fun status(title: String, detail: String) {
        main.post { if (hasOverlay) overlay.setStatus(title, detail) }
    }

    fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // ---------------- espera / controle ----------------

    fun checkStop() {
        if (stopRequested || Thread.currentThread().isInterrupted) throw StopException()
    }

    /** Dorme em pedaços, respeitando parar e pausar. */
    fun nap(ms: Long) {
        var left = ms
        while (left > 0) {
            checkStop()
            if (paused) { waitWhilePaused(); continue }
            val step = minOf(left, 200L)
            try { Thread.sleep(step) } catch (e: InterruptedException) { throw StopException() }
            left -= step
        }
        checkStop()
    }

    private fun waitWhilePaused() {
        while (paused) {
            checkStop()
            try { Thread.sleep(250) } catch (e: InterruptedException) { throw StopException() }
        }
    }

    /** Garante que o TikTok está na frente; se não estiver, espera o usuário voltar. */
    fun ensureTikTok(waitMs: Long = 0): AccessibilityNodeInfo {
        val start = System.currentTimeMillis()
        var warned = false
        var away = false
        while (true) {
            checkStop()
            tkRoot()?.let { if (warned) Log.i("TikTok de volta"); if (away) awayCount++; return it }
            away = true
            if (System.currentTimeMillis() - start > waitMs && !warned) {
                warned = true
                status("Esperando o TikTok", "Volte para o TikTok para eu continuar")
                Log.i("TikTok saiu da frente; esperando…")
            }
            nap(500)
        }
    }

    /** Todas as janelas do TikTok, da de cima para a de baixo (a lista de comentários abre numa janela própria). */
    fun tkRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() in Tk.PACKAGES) out.add(w.layer to r)
            }
        } catch (_: Throwable) {}
        if (out.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null && r.packageName?.toString() in Tk.PACKAGES) return listOf(r)
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    /** Raiz da janela do TikTok que está por cima. */
    fun tkRoot(): AccessibilityNodeInfo? = tkRoots().firstOrNull()

    /** Nós de todas as janelas do TikTok (as de cima primeiro). */
    fun nodes(): List<AccessibilityNodeInfo> = tkRoots().flatMap { Tk.all(it) }

    fun visible(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val r = Tk.rect(n)
        return r.width() > 0 && r.height() > 0 && r.right > 0 && r.left < W && r.bottom > 0 && r.top < H
    }

    /** Espera até `ms` algo aparecer na tela do TikTok. */
    fun <R> waitFor(ms: Long, f: (List<AccessibilityNodeInfo>) -> R?): R? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            checkStop()
            val r = runCatching { f(nodes()) }.getOrNull()
            if (r != null) return r
            if (System.currentTimeMillis() > end) return null
            nap(350)
        }
    }

    // ---------------- print da tela (Android 11+) ----------------

    @Volatile private var lastShot = 0L
    @Volatile var shotError: Int = 0

    val canScreenshot: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Tira um print da tela inteira (só fica na memória). null se não der. */
    fun screenshot(): Bitmap? {
        checkStop()
        if (paused) waitWhilePaused()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        // o Android só deixa ~3 prints por segundo
        val wait = lastShot + 380 - System.currentTimeMillis()
        if (wait > 0) nap(wait)
        val bmp = shot30()
        lastShot = System.currentTimeMillis()
        return bmp
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun shot30(): Bitmap? {
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        out = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                    } catch (_: Throwable) {}
                    runCatching { r.hardwareBuffer.close() }
                    shotError = 0
                    latch.countDown()
                }
                override fun onFailure(code: Int) { shotError = code; latch.countDown() }
            })
        } catch (_: Throwable) { shotError = -1; return null }
        try { latch.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { throw StopException() }
        return out
    }

    // ---------------- ações ----------------

    fun click(n: AccessibilityNodeInfo): Boolean {
        checkStop()
        if (paused) waitWhilePaused()
        val c = Tk.clickableAncestor(n)
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Tk.rect(n)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return gesture(GestureDescription.StrokeDescription(p, 0, 60))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return gesture(GestureDescription.StrokeDescription(p, 0, dur))
    }

    private fun gesture(stroke: GestureDescription.StrokeDescription): Boolean {
        checkStop()
        if (paused) waitWhilePaused()
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(stroke).build()
        main.post {
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (!sent) latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }

    fun setText(n: AccessibilityNodeInfo, s: String): Boolean {
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun imeEnter(n: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return false
    }

    // ---------------- link copiado ----------------

    /** Abre uma tela invisível nossa para ler a área de transferência (o Android só deixa o app da frente ler). */
    fun readClipboard(): String? {
        clipText = null
        val latch = CountDownLatch(1)
        clipLatch = latch
        main.post {
            startActivity(Intent(this, ClipActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        latch.await(5, TimeUnit.SECONDS)
        clipLatch = null
        return clipText
    }

    fun deliverClip(s: String?) {
        clipText = s
        clipLatch?.countDown()
    }

    // ---------------- diagnóstico ----------------

    /** Guarda a "foto" dos botões da tela atual (vai junto no relatório). */
    fun autoDump(reason: String) {
        val sb = StringBuilder("motivo=$reason · tela=${W}x$H\n")
        try {
            for (w in windows) {
                val r = w.root
                sb.append("\n##### JANELA tipo=${w.type} camada=${w.layer} ativa=${w.isActive} app=${r?.packageName} titulo=${w.title}\n")
                if (r != null && r.packageName?.toString() != packageName) sb.append(Tk.dump(r))
            }
        } catch (e: Throwable) {
            sb.append("(erro lendo janelas: ${e.message})\n").append(Tk.dump(rootInActiveWindow))
        }
        Log.saveDump(reason, sb.toString())
    }

    /** Botão 🩺: salva a tela atual e gera o relatório em Downloads/Viraliza. */
    fun saveDiagnostic() {
        Thread {
            autoDump("manual")
            try {
                val (_, path) = Report.save(this)
                toast("Relatório salvo em $path")
                Log.i("Relatório salvo: $path")
            } catch (e: Throwable) {
                Store.saveError("Relatório: " + android.util.Log.getStackTraceString(e))
                toast("Não consegui salvar o relatório: ${e.message}")
            }
        }.start()
    }

    private fun realHeight(): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            wm.maximumWindowMetrics.bounds.height()
        } else {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val p = android.graphics.Point()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
            p.y
        }
    }

    fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    fun inRect(r: Rect, x: Int, y: Int) = r.contains(x, y)
}
