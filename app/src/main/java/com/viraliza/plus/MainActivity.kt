package com.viraliza.plus

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private enum class Tab(val label: String, val emoji: String) {
        QUEUE("Fila", "📋"), SEARCH("Pesquisar", "🔎"), PROFILES("Perfis", "⚡"), SETTINGS("Ajustes", "⚙️")
    }

    private var tab = Tab.QUEUE
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var headerStatus: TextView
    private val onStore: () -> Unit = { render() }

    // rascunhos (sobrevivem às redesenhadas da tela)
    private var linkDraft = ""
    private var searchDraft: SearchConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(this); Fonts.init(this); Store.init(this)

        val root = vbox(this).apply { setBackgroundColor(T.bg) }
        // cabeçalho
        val header = hbox(this).apply { setPadding(dp(20), dp(18), dp(20), dp(10)) }
        header.addView(title(this, "Viraliza+", 24f), lp(0, weight = 1f))
        headerStatus = text(this, "", 12.5f, T.text2, weight = 800).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { openAccessibility() }
        }
        header.addView(headerStatus, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header, lp())

        scroll = ScrollView(this).apply { isFillViewport = true }
        content = vbox(this).apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, lp(h = 0, weight = 1f))

        tabBar = hbox(this).apply { setBackgroundColor(T.nav); setPadding(dp(8), dp(6), dp(8), dp(8)) }
        val tabWrap = vbox(this)
        tabWrap.addView(View(this).apply { setBackgroundColor(T.border) }, lp(h = 1))
        tabWrap.addView(tabBar, lp())
        root.addView(tabWrap, lp())

        setContentView(root)
        // Android 15 desenha atrás das barras: empurra o conteúdo para dentro
        root.setOnApplyWindowInsetsListener { v, ins ->
            val top: Int; val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                top = b.top; bottom = b.bottom
            } else {
                @Suppress("DEPRECATION") run { top = ins.systemWindowInsetTop; bottom = ins.systemWindowInsetBottom }
            }
            v.setPadding(0, top, 0, bottom)
            ins
        }
        window.statusBarColor = T.bg
        window.navigationBarColor = T.nav
        if (Build.VERSION.SDK_INT >= 23 && !T.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        handleShare(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        val url = Store.extractUrl(i?.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        Store.addToQueue(Post(url))
        tab = Tab.QUEUE
    }

    override fun onResume() {
        super.onResume()
        Store.addListener(onStore)
        render()
    }

    override fun onPause() {
        Store.removeListener(onStore)
        super.onPause()
    }

    // =================================================================

    private fun render() {
        val y = scroll.scrollY
        paintHeader()
        paintTabs()
        content.removeAllViews()
        when (tab) {
            Tab.QUEUE -> renderQueue()
            Tab.SEARCH -> renderSearch()
            Tab.PROFILES -> renderProfiles()
            Tab.SETTINGS -> renderSettings()
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun paintHeader() {
        val on = BotService.isOn
        val stuck = !on && Store.enabledInSettings(this)
        headerStatus.text = if (on) "● Robô ligado" else if (stuck) "● Robô não conectou" else "● Robô desligado"
        headerStatus.setTextColor(if (on) T.good else if (stuck) T.warn else T.bad)
        headerStatus.background = rounded(this, if (on) T.goodSoft else T.surface2, 12)
    }

    private fun paintTabs() {
        tabBar.removeAllViews()
        Tab.values().forEach { t ->
            val on = t == tab
            val v = vbox(this).apply {
                gravity = Gravity.CENTER
                minimumHeight = dp(54)
                isClickable = true
                setOnClickListener { tab = t; scroll.scrollTo(0, 0); render() }
                addView(text(this@MainActivity, t.emoji, 18f, T.text).apply { gravity = Gravity.CENTER }, lp(-2))
                addView(text(this@MainActivity, t.label, 12f, if (on) T.accent else T.text2, weight = if (on) 800 else 600), lp(-2).margins(this@MainActivity, t = 3))
            }
            tabBar.addView(v, lp(0, weight = 1f))
        }
    }

    private fun add(v: View, topDp: Int = 12) = content.addView(v, lp().margins(this, t = topDp))

    // =================================================================
    //  FILA
    // =================================================================

    private fun renderQueue() {
        val bot = BotService.instance
        if (!BotService.isOn) add(robotOffCard(), 4)
        if (bot != null && bot.busy) add(busyCard(bot), 4)

        // adicionar por link
        val addCard = card(this)
        addCard.addView(sectionLabel(this, "Adicionar post"))
        val row = hbox(this)
        val inp = input(this, "Cole o link do TikTok", value = linkDraft).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            addTextChangedListener(simpleWatcher { linkDraft = it })
        }
        row.addView(inp, lp(0, weight = 1f))
        row.addView(button(this, "Adicionar", BtnStyle.PRIMARY) {
            val url = Store.extractUrl(inp.text.toString())
            if (url == null) toast("Cole um link do TikTok") else {
                linkDraft = ""
                toast(if (Store.addToQueue(Post(url))) "Adicionado ✓" else "Já está na fila")
            }
        }, lp(-2).margins(this, l = 8))
        addCard.addView(row, lp().margins(this, t = 10))
        addCard.addView(text(this, "Dica: no TikTok toque ＋ Fila no balão do Viraliza+, ou Compartilhar › Mais › Viraliza+. Também dá para usar a aba Pesquisar.", 12.5f, T.text2), lp().margins(this, t = 10))
        add(addCard, 8)

        val q = Store.queue()
        if (q.isEmpty()) {
            add(text(this, "A fila está vazia.", 14f, T.text2).apply { gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(8)) })
            return
        }

        // ações da lista
        val sel = q.filter { it.selected }
        val bar = hbox(this)
        bar.addView(text(this, "${q.size} posts · ${sel.size} selecionados", 13.5f, T.text2, weight = 700), lp(0, weight = 1f))
        bar.addView(smallAction(if (sel.size == q.size) "Nenhum" else "Todos") {
            val all = sel.size != q.size
            Store.saveQueue(q.onEach { it.selected = all })
        })
        bar.addView(smallAction("Apagar") { confirmDelete(q) }, lp(-2).margins(this, l = 6))
        add(bar, 18)

        q.forEach { p -> add(postCard(p, inQueue = true) {
            Store.saveQueue(Store.queue().onEach { if (it.key == p.key) it.selected = !it.selected })
        }, 8) }

        // configuração
        val cfg = Store.runConfig
        val profs = Store.profiles()
        if (profs.none { it.name == cfg.profile }) cfg.profile = profs.first().name
        val cc = card(this)
        cc.addView(sectionLabel(this, "O que curtir"))
        cc.addView(text(this, "Comentários", 13f, T.text2, weight = 700), lp().margins(this, t = 12))
        cc.addView(Segmented(this, listOf(Which.MAIN to "Principais", Which.REPLIES to "Respostas", Which.BOTH to "Os dois"), cfg.which) {
            cfg.which = it; Store.runConfig = cfg; render()
        }.view, lp().margins(this, t = 6))
        cc.addView(text(this, "Ordem", 13f, T.text2, weight = 700), lp().margins(this, t = 12))
        cc.addView(Segmented(this, listOf(Order.RECENT to "Mais recentes", Order.RELEVANT to "Mais relevantes"), cfg.order) {
            cfg.order = it; Store.runConfig = cfg; render()
        }.view, lp().margins(this, t = 6))
        cc.addView(text(this, "Só comentários de até", 13f, T.text2, weight = 700), lp().margins(this, t = 12))
        cc.addView(Choice(this, listOf(0L to "Qualquer", 60L to "1 hora", 360L to "6 horas", 1440L to "24 horas", 4320L to "3 dias", 10080L to "7 dias", 43200L to "30 dias"), cfg.maxAgeMin) {
            cfg.maxAgeMin = it; Store.runConfig = cfg; render()
        }.view, lp().margins(this, t = 6))
        cc.addView(text(this, "Máximo por post (0 = todos)", 13f, T.text2, weight = 700), lp().margins(this, t = 12))
        val per = input(this, "0", numeric = true, value = cfg.perPost.toString())
        per.addTextChangedListener(simpleWatcher { val c = Store.runConfig; c.perPost = it.toIntOrNull() ?: 0; Store.runConfig = c })
        per.setOnFocusChangeListener { _, f -> if (!f) render() }
        cc.addView(per, lp().margins(this, t = 6))
        cc.addView(text(this, "Perfil de velocidade", 13f, T.text2, weight = 700), lp().margins(this, t = 12))
        cc.addView(Choice(this, profs.map { it.name to it.name }, cfg.profile) {
            cfg.profile = it; Store.runConfig = cfg; render()
        }.view, lp().margins(this, t = 6))
        cc.addView(text(this, Store.profile(cfg.profile).summary(), 12.5f, T.text3), lp().margins(this, t = 6))
        add(cc, 20)

        add(summaryCard(sel, cfg), 12)

        val btns = hbox(this)
        btns.addView(button(this, "Contar exato", BtnStyle.GHOST) { start(sel, true) }, lp(0, weight = 1f))
        btns.addView(button(this, "❤  Começar a curtir", BtnStyle.PRIMARY) { start(sel, false) }, lp(0, weight = 1.4f).margins(this, l = 10))
        add(btns, 14)
    }

    private fun summaryCard(sel: List<Post>, cfg: RunConfig): View {
        val prof = Store.profile(cfg.profile)
        var total = 0L; var exactPosts = 0; var unknown = 0; var planned = 0L
        sel.forEach { p ->
            val n = when {
                p.exact >= 0 && p.exactInfo == cfg.describe() -> { exactPosts++; p.exact }
                p.comments >= 0 -> p.comments
                else -> { unknown++; -1L }
            }
            if (n >= 0) {
                total += n
                planned += if (cfg.perPost > 0) minOf(n, cfg.perPost.toLong()) else n
            }
        }
        val c = card(this).apply { background = rounded(this@MainActivity, T.accentSoft, 22) }
        c.addView(sectionLabel(this, "Resumo").apply { setTextColor(T.accentText) })
        val approx = if (exactPosts == sel.size && sel.isNotEmpty()) "" else "~"
        c.addView(text(this, "${sel.size} posts · $approx${formatNum(total)} comentários", 20f, T.text, weight = 800), lp().margins(this, t = 8))
        val lines = mutableListOf<String>()
        if (exactPosts < sel.size && cfg.which != Which.BOTH)
            lines.add("O número do TikTok soma principais + respostas. Use \"Contar exato\" para saber só ${if (cfg.which == Which.MAIN) "os principais" else "as respostas"}.")
        if (cfg.maxAgeMin > 0 && exactPosts < sel.size) lines.add("O filtro de idade só entra na contagem exata.")
        if (unknown > 0) lines.add("$unknown post(s) ainda sem contagem: o robô lê ao abrir.")
        if (exactPosts > 0) lines.add("$exactPosts post(s) com contagem exata.")
        if (cfg.perPost > 0) lines.add("Máximo de ${cfg.perPost} por post → até ${formatNum(planned)} curtidas.")
        val est = Pacer.estimate(prof, planned, sel.size)
        lines.add("⏱ Tempo estimado: ${humanTime(est)} (perfil ${prof.name}, com pausas)")
        if (prof.dailyLimit > 0 && planned > prof.dailyLimit) lines.add("Limite diário de ${prof.dailyLimit}: o robô para ao bater e você continua no outro dia.")
        lines.add("Curtidas hoje: ${Store.likedToday()}")
        lines.forEach { c.addView(text(this, it, 13f, T.accentText), lp().margins(this, t = 6)) }
        return c
    }

    private fun start(sel: List<Post>, countOnly: Boolean) {
        val bot = BotService.instance
        if (bot == null) { robotOffDialog(); return }
        if (sel.isEmpty()) { toast("Selecione pelo menos um post"); return }
        if (Tk.installedPackage(this) == null) { toast("TikTok não encontrado neste celular"); return }
        val cfg = Store.runConfig
        val posts = sel.map { Post.fromJson(it.toJson()) }
        val ok = bot.startTask(if (countOnly) "Contar" else "Curtir") { Jobs.likeQueue(it, posts, cfg, countOnly) }
        if (ok) toast(if (countOnly) "Contando… acompanhe no balão" else "Começando… acompanhe no balão")
    }

    private fun confirmDelete(q: List<Post>) {
        val sel = q.count { it.selected }
        val done = q.count { it.done }
        AlertDialog.Builder(this)
            .setTitle("Apagar da fila")
            .setItems(arrayOf("Apagar selecionados ($sel)", "Apagar concluídos ($done)", "Apagar todos (${q.size})")) { _, w ->
                val keep = when (w) {
                    0 -> q.filter { !it.selected }
                    1 -> q.filter { !it.done }
                    else -> emptyList()
                }
                Store.saveQueue(keep)
            }.show()
    }

    private fun postCard(p: Post, inQueue: Boolean, onToggle: () -> Unit): View {
        val c = card(this, padDp = 14, radiusDp = 18)
        if (p.selected) c.background = rounded(this, T.surface, 18, T.accent, 2)
        c.isClickable = true
        c.setOnClickListener { onToggle() }
        val top = hbox(this)
        top.addView(text(this, if (p.selected) "☑" else "☐", 20f, if (p.selected) T.accent else T.text3), lp(-2).margins(this, r = 10))
        val col = vbox(this)
        col.addView(text(this, if (p.author.isNotBlank()) "@${p.author}" else "Post do TikTok", 15f, T.text, weight = 800, maxLines = 1))
        val cap = p.caption.ifBlank { p.url }
        col.addView(text(this, cap, 13f, T.text2, maxLines = 2), lp().margins(this, t = 3))
        top.addView(col, lp(0, weight = 1f))
        c.addView(top)
        val chips = hbox(this)
        fun chip(s: String, fg: Int = T.text2, bg: Int = T.surface2) = chips.addView(badge(this, s, fg, bg), lp(-2).margins(this, r = 6))
        chip("💬 " + if (p.comments >= 0) formatCount(p.comments) else "?")
        if (p.likes >= 0) chip("❤ " + formatCount(p.likes))
        if (p.age.isNotBlank()) chip("🕒 " + p.age)
        if (inQueue) {
            if (p.exact >= 0) chip("Exato: ${formatNum(p.exact)}", T.accentText, T.accentSoft)
            if (p.done) chip("Feito · ${p.liked}", T.goodText, T.goodSoft)
            else if (p.liked > 0) chip("${p.liked} curtidas", T.goodText, T.goodSoft)
        }
        c.addView(chips, lp().margins(this, t = 10, l = 30))
        if (inQueue) {
            c.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("@${p.author}").setItems(arrayOf("Abrir no TikTok", "Zerar progresso", "Remover da fila")) { _, w ->
                    when (w) {
                        0 -> Tk.openUrl(this, p.url)
                        1 -> Store.updatePost(p.url) { it.done = false; it.liked = 0; it.exact = -1 }
                        2 -> Store.saveQueue(Store.queue().filter { it.key != p.key })
                    }
                }.show()
                true
            }
        }
        return c
    }

    private fun busyCard(bot: BotService): View {
        val c = card(this).apply { background = rounded(this@MainActivity, T.goodSoft, 20) }
        val row = hbox(this)
        row.addView(text(this, "🤖 Robô trabalhando: ${bot.currentTask}", 14f, T.goodText, weight = 800), lp(0, weight = 1f))
        row.addView(button(this, if (bot.paused) "▶" else "⏸", BtnStyle.SECONDARY) { bot.togglePause(); render() }, lp(-2))
        row.addView(button(this, "Parar", BtnStyle.DANGER) { bot.stopTask(); render() }, lp(-2).margins(this, l = 6))
        c.addView(row)
        return c
    }

    // =================================================================
    //  PESQUISAR
    // =================================================================

    private fun renderSearch() {
        if (!BotService.isOn) add(robotOffCard(), 4)
        BotService.instance?.let { if (it.busy) add(busyCard(it), 4) }
        val cfg = searchDraft ?: Store.searchConfig.also { searchDraft = it }
        val c = card(this)
        c.addView(sectionLabel(this, "Pesquisar no TikTok"))
        val inp = input(this, "Ex.: garotas apoiam garotas", value = cfg.term)
        inp.addTextChangedListener(simpleWatcher { cfg.term = it })
        c.addView(inp, lp().margins(this, t = 10))
        c.addView(text(this, "Data de publicação", 13f, T.text2, weight = 700), lp().margins(this, t = 12))
        c.addView(Choice(this, listOf(0 to "Tudo", 1 to "24 horas", 2 to "Semana", 3 to "Mês", 4 to "3 meses", 5 to "6 meses"), cfg.date) { cfg.date = it }.view, lp().margins(this, t = 6))
        c.addView(text(this, "Ordenar por", 13f, T.text2, weight = 700), lp().margins(this, t = 12))
        c.addView(Segmented(this, listOf(0 to "Relevância", 1 to "Mais curtidos"), cfg.sort) { cfg.sort = it }.view, lp().margins(this, t = 6))
        c.addView(text(this, "Quantos posts listar", 13f, T.text2, weight = 700), lp().margins(this, t = 12))
        c.addView(Choice(this, listOf(10 to "10", 20 to "20", 30 to "30", 50 to "50"), cfg.count) { cfg.count = it }.view, lp().margins(this, t = 6))
        c.addView(button(this, "🔎  Pesquisar no TikTok", BtnStyle.PRIMARY) {
            if (cfg.term.isBlank()) { toast("Digite o que pesquisar"); return@button }
            Store.searchConfig = cfg
            val bot = BotService.instance ?: run { robotOffDialog(); return@button }
            val copy = SearchConfig.fromJson(cfg.toJson())
            if (bot.startTask("Pesquisar") { Jobs.search(it, copy) }) toast("O robô vai abrir o TikTok e pesquisar")
        }, lp().margins(this, t = 16))
        c.addView(text(this, "O robô abre o TikTok, pesquisa, aplica os filtros e passa pelos vídeos copiando o link de cada um. Volte aqui quando o balão disser \"Pesquisa pronta\".", 12.5f, T.text2), lp().margins(this, t = 10))
        add(c, 8)

        val res = Store.results()
        if (res.isEmpty()) return
        val sel = res.filter { it.selected }
        val bar = hbox(this)
        bar.addView(text(this, "Resultados: ${res.size}", 15f, T.text, weight = 800), lp(0, weight = 1f))
        bar.addView(smallAction(if (sel.size == res.size) "Nenhum" else "Todos") {
            val all = sel.size != res.size; Store.saveResults(res.onEach { it.selected = all })
        })
        bar.addView(smallAction("Limpar") { Store.saveResults(emptyList()) }, lp(-2).margins(this, l = 6))
        add(bar, 20)
        add(button(this, "＋ Adicionar à fila (${sel.size})", BtnStyle.SOFT) {
            if (sel.isEmpty()) { toast("Toque nos posts para selecionar"); return@button }
            var n = 0
            sel.forEach { p -> if (Store.addToQueue(Post.fromJson(p.toJson()).apply { selected = true })) n++ }
            Store.saveResults(res.filter { !it.selected })
            toast("$n adicionados à fila")
        }, 10)
        res.forEach { p -> add(postCard(p, inQueue = false) {
            Store.saveResults(Store.results().onEach { if (it.key == p.key) it.selected = !it.selected })
        }, 8) }
    }

    // =================================================================
    //  PERFIS
    // =================================================================

    private fun renderProfiles() {
        add(text(this, "Cada conta aguenta um ritmo. Crie um perfil para cada uma e escolha na hora de começar.", 13.5f, T.text2), 4)
        val profs = Store.profiles()
        profs.forEachIndexed { i, p ->
            val c = card(this, padDp = 16, radiusDp = 18)
            c.isClickable = true
            c.setOnClickListener { editProfile(i) }
            c.addView(text(this, p.name, 16f, T.text, weight = 800))
            c.addView(text(this, p.summary(), 13f, T.text2), lp().margins(this, t = 4))
            add(c, 10)
        }
        add(button(this, "＋ Novo perfil", BtnStyle.SOFT) { editProfile(-1) }, 14)
        add(text(this, "⚠️ Curtir rápido demais pode gerar bloqueio temporário de curtidas. Em conta nova, comece devagar.", 12.5f, T.text3), 14)
    }

    private fun editProfile(index: Int) {
        val profs = Store.profiles()
        val p = if (index >= 0) profs[index] else Profile("Conta ${profs.size + 1}")
        val box = vbox(this, 20)
        fun field(label: String, value: String, numeric: Boolean, decimal: Boolean = false): android.widget.EditText {
            box.addView(text(this, label, 12.5f, T.text2, weight = 700), lp().margins(this, t = 10))
            val e = input(this, "", numeric, value)
            if (decimal) e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            box.addView(e, lp().margins(this, t = 4))
            return e
        }
        val name = field("Nome (ex.: nome da conta)", p.name, false)
        val dmin = field("Intervalo mínimo entre curtidas (segundos)", Profile.fmt(p.minDelay).replace(',', '.'), true, true)
        val dmax = field("Intervalo máximo entre curtidas (segundos)", Profile.fmt(p.maxDelay).replace(',', '.'), true, true)
        val wmin = field("Curte por quantos minutos antes de pausar (0 = sem)", p.workMinutes.toString(), true)
        val wlik = field("…ou por quantas curtidas (0 = sem)", p.workLikes.toString(), true)
        val pmin = field("Pausa mínima (minutos)", p.pauseMin.toString(), true)
        val pmax = field("Pausa máxima (minutos)", p.pauseMax.toString(), true)
        val daily = field("Limite por dia (0 = sem limite)", p.dailyLimit.toString(), true)
        val sv = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (index >= 0) "Editar perfil" else "Novo perfil")
            .setView(sv)
            .setPositiveButton("Salvar") { _, _ ->
                val np = Profile(
                    name.text.toString().trim().ifBlank { p.name },
                    dmin.text.toString().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.2) ?: p.minDelay,
                    dmax.text.toString().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.2) ?: p.maxDelay,
                    wmin.text.toString().toIntOrNull() ?: 0,
                    wlik.text.toString().toIntOrNull() ?: 0,
                    pmin.text.toString().toIntOrNull() ?: 0,
                    pmax.text.toString().toIntOrNull() ?: 0,
                    daily.text.toString().toIntOrNull() ?: 0
                )
                val list = Store.profiles()
                if (list.any { it.name == np.name } && (index < 0 || list[index].name != np.name)) { toast("Já existe um perfil com esse nome"); return@setPositiveButton }
                if (index >= 0) {
                    val old = list[index].name
                    list[index] = np
                    val rc = Store.runConfig; if (rc.profile == old) { rc.profile = np.name; Store.runConfig = rc }
                } else list.add(np)
                Store.saveProfiles(list)
            }
            .setNegativeButton("Cancelar", null)
        if (index >= 0 && profs.size > 1) dlg.setNeutralButton("Apagar") { _, _ ->
            Store.saveProfiles(Store.profiles().filterIndexed { i, _ -> i != index })
        }
        dlg.show()
    }

    // =================================================================
    //  AJUSTES
    // =================================================================

    private fun renderSettings() {
        val c = card(this)
        c.addView(sectionLabel(this, "Robô"))
        val on = BotService.isOn
        val inSettings = Store.enabledInSettings(this)
        c.addView(text(this, if (on) "Ligado ✓" else if (inSettings) "Ativado, mas não conectou" else "Desligado", 18f, if (on) T.good else T.bad, weight = 800), lp().margins(this, t = 8))
        c.addView(text(this, "O robô usa a permissão de Acessibilidade para tocar nos botões do TikTok. Ele só age quando você manda.", 13f, T.text2), lp().margins(this, t = 6))
        c.addView(button(this, if (on) "Abrir configurações de acessibilidade" else "Ligar o robô", if (on) BtnStyle.GHOST else BtnStyle.PRIMARY) { openAccessibility() }, lp().margins(this, t = 12))
        c.addView(text(this, "No Xiaomi, ative o Início automático e deixe a bateria \"Sem restrições\", senão o celular desliga o robô quando fecha o app.", 12.5f, T.text3), lp().margins(this, t = 10))
        c.addView(button(this, "Início automático", BtnStyle.GHOST) { openAutostart() }, lp().margins(this, t = 8))
        add(c, 4)

        val err = Store.lastError()
        if (err.isNotBlank()) {
            val ce = card(this)
            val h = hbox(this)
            h.addView(sectionLabel(this, "Último erro").apply { setTextColor(T.bad) }, lp(0, weight = 1f))
            h.addView(smallAction("Copiar") {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("erro", err))
                toast("Erro copiado")
            })
            h.addView(smallAction("Apagar") { Store.clearError(); render() }, lp(-2).margins(this, l = 6))
            ce.addView(h)
            ce.addView(text(this, err.take(2500), 11f, T.text2).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }, lp().margins(this, t = 8))
            add(ce)
        }

        val tk = Tk.installedPackage(this)
        val c2 = card(this)
        c2.addView(sectionLabel(this, "Números"))
        c2.addView(text(this, "TikTok: " + (tk ?: "não encontrado"), 14f, T.text), lp().margins(this, t = 8))
        c2.addView(text(this, "Curtidas hoje: ${Store.likedToday()}", 14f, T.text), lp().margins(this, t = 4))
        c2.addView(text(this, "Curtidas no total: ${formatNum(Store.totalLiked())}", 14f, T.text), lp().margins(this, t = 4))
        add(c2)

        val c3 = card(this)
        c3.addView(sectionLabel(this, "Se o robô não achar algum botão"))
        c3.addView(text(this, "O TikTok muda a tela às vezes. Abra no TikTok a tela onde o robô travou e toque 🩺 no balão. Ele salva um arquivo em Downloads/Viraliza; mande esse arquivo para o Claude ajustar o robô.", 13f, T.text2), lp().margins(this, t = 8))
        add(c3)

        val cr = card(this).apply { background = rounded(this@MainActivity, T.accentSoft, 22) }
        cr.addView(sectionLabel(this, "Deu problema?").apply { setTextColor(T.accentText) })
        cr.addView(text(this, "Gera um arquivo com o registro e as telas do TikTok que o robô salvou quando falhou (${Log.dumps().size} telas salvas). Salva em Downloads/Viraliza e abre para você mandar. Mande esse arquivo para o Claude.", 13f, T.accentText), lp().margins(this, t = 6))
        cr.addView(button(this, "📄  Gerar relatório para o Claude", BtnStyle.PRIMARY) { sendReport() }, lp().margins(this, t = 12))
        cr.addView(button(this, "Apagar registro e telas", BtnStyle.GHOST) { Log.clear(); Log.clearDumps(); Store.clearError(); render() }, lp().margins(this, t = 8))
        add(cr)

        val c4 = card(this)
        val head = hbox(this)
        head.addView(sectionLabel(this, "Registro"), lp(0, weight = 1f))
        head.addView(smallAction("Copiar") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", Log.all()))
            toast("Registro copiado")
        })
        c4.addView(head)
        val lines = Log.all().lines().filter { it.isNotBlank() }.takeLast(150).reversed().joinToString("\n").ifBlank { "Nada ainda." }
        c4.addView(text(this, lines, 11.5f, T.text2).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }, lp().margins(this, t = 10))
        add(c4)
        add(text(this, "Viraliza+ v" + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?"), 12f, T.text3).apply { gravity = Gravity.CENTER }, 16)
    }

    // =================================================================
    //  ajudantes
    // =================================================================

    private fun robotOffCard(): View {
        if (Store.enabledInSettings(this)) return robotStuckCard()
        val c = card(this).apply { background = rounded(this@MainActivity, T.surface, 20, T.accent, 1) }
        c.addView(text(this, "Ligue o robô primeiro", 16f, T.text, weight = 800))
        c.addView(text(this, "Configurações › Acessibilidade › Apps instalados › Viraliza+ robô › Ativar.", 13f, T.text2), lp().margins(this, t = 6))
        c.addView(text(this, "Se aparecer \"Configuração restrita\": abra Informações do app do Viraliza+ › ⋮ (canto de cima) › Permitir configurações restritas, e tente de novo.", 12.5f, T.text3), lp().margins(this, t = 6))
        val row = hbox(this)
        row.addView(button(this, "Ligar o robô", BtnStyle.PRIMARY) { openAccessibility() }, lp(0, weight = 1f))
        row.addView(button(this, "Info do app", BtnStyle.GHOST) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, lp(0, weight = 1f).margins(this, l = 8))
        c.addView(row, lp().margins(this, t = 12))
        return c
    }

    /** Está ligado nas configurações, mas o serviço não está rodando. */
    private fun robotStuckCard(): View {
        val c = card(this).apply { background = rounded(this@MainActivity, T.surface, 20, T.warn, 1) }
        c.addView(text(this, "O robô está ativado, mas não conectou", 16f, T.text, weight = 800))
        c.addView(text(this, "Isso acontece quando o celular fecha o app. Para não acontecer mais:\n1. Toque \"Início automático\" abaixo e ative o Viraliza+.\n2. Em Info do app › Economia de bateria, escolha \"Sem restrições\".\n3. Nos apps recentes, segure o Viraliza+ e toque no cadeado 🔒.\nAgora: em Acessibilidade, desligue e ligue o Viraliza+ robô.", 13f, T.text2), lp().margins(this, t = 6))
        val tr = Store.traceText()
        c.addView(text(this, "Linha do tempo do robô (mande print):", 12.5f, T.text, weight = 800), lp().margins(this, t = 10))
        c.addView(text(this, tr.ifBlank { "(o Android ainda não tentou ligar o robô)" }, 11f, T.text2).apply { typeface = Typeface.MONOSPACE }, lp().margins(this, t = 4))
        val err = Store.lastError()
        if (err.isNotBlank()) {
            c.addView(text(this, "Erro encontrado (mande print para o Claude):", 12.5f, T.bad, weight = 800), lp().margins(this, t = 10))
            c.addView(text(this, err.take(900), 11f, T.text2).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }, lp().margins(this, t = 4))
        }
        val row = hbox(this)
        row.addView(button(this, "Acessibilidade", BtnStyle.PRIMARY) { openAccessibility() }, lp(0, weight = 1f))
        row.addView(button(this, "Início automático", BtnStyle.GHOST) { openAutostart() }, lp(0, weight = 1f).margins(this, l = 8))
        c.addView(row, lp().margins(this, t = 12))
        c.addView(button(this, "Info do app (bateria)", BtnStyle.GHOST) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, lp().margins(this, t = 8))
        return c
    }

    private fun openAutostart() {
        val tries = listOf(
            android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            android.content.ComponentName("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity")
        )
        for (cn in tries) {
            val ok = runCatching { startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
            if (ok) return
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        toast("Procure \"Início automático\" e ative")
    }

    private fun sendReport() {
        toast("Gerando relatório…")
        Thread {
            try {
                val (uri, path) = Report.save(this)
                runOnUiThread {
                    toast("Relatório salvo em $path")
                    if (uri != null) runCatching { startActivity(Report.shareIntent(uri)) }
                }
            } catch (e: Throwable) {
                Store.saveError("Relatório: " + android.util.Log.getStackTraceString(e))
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Não consegui gerar o relatório")
                        .setMessage("${e.javaClass.simpleName}: ${e.message}\n\nMande um print desta mensagem.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun robotOffDialog() {
        AlertDialog.Builder(this).setTitle("Robô desligado")
            .setMessage("Ative o \"Viraliza+ robô\" em Acessibilidade para ele poder tocar no TikTok por você.")
            .setPositiveButton("Abrir") { _, _ -> openAccessibility() }
            .setNegativeButton("Agora não", null).show()
    }

    private fun openAccessibility() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun smallAction(label: String, onClick: () -> Unit) = text(this, label, 13f, T.accent, weight = 800).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = pressable(this@MainActivity, T.accentSoft, 12)
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun simpleWatcher(f: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { f(s?.toString() ?: "") }
    }

    private fun formatNum(n: Long): String = String.format(java.util.Locale("pt", "BR"), "%,d", n)

    private fun humanTime(ms: Long): String {
        if (ms <= 0) return "—"
        val min = ms / 60000
        return when {
            min < 1 -> "menos de 1 min"
            min < 60 -> "$min min"
            min < 48 * 60 -> "${min / 60}h ${min % 60}min"
            else -> "${min / 1440} dias e ${(min % 1440) / 60}h"
        }
    }
}
