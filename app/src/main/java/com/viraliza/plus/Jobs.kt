package com.viraliza.plus

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

/** Um comentário que está visível na tela. */
class Row(
    val key: String,
    val author: String,
    val ageMin: Long,
    val replyRect: Rect,
    val like: AccessibilityNodeInfo?,
    val liked: Boolean?   // null = não deu para saber
)

/** Informações lidas da tela de um vídeo. */
class VideoInfo(
    var author: String = "",
    var caption: String = "",
    var comments: Long = -1,
    var likes: Long = -1,
    var age: String = "",
    var commentBtn: AccessibilityNodeInfo? = null,
    var shareBtn: AccessibilityNodeInfo? = null
)

/** Tudo que o robô sabe fazer no TikTok. */
object Jobs {

    // ======================================================================
    //  Curtir (ou só contar) comentários dos posts da fila
    // ======================================================================

    fun likeQueue(s: BotService, posts: List<Post>, cfg: RunConfig, countOnly: Boolean) {
        val prof = Store.profile(cfg.profile)
        val pacer = Pacer(s, prof)
        Log.i((if (countOnly) "Contagem exata" else "Curtir") + ": ${posts.size} posts · ${cfg.describe()} · perfil ${prof.name} (${prof.summary()})")
        var totalLiked = 0L
        var totalCount = 0L
        posts.forEachIndexed { i, p ->
            s.checkStop()
            val tag = "Post ${i + 1}/${posts.size}"
            s.status(tag, "Abrindo ${p.author.ifBlank { "post" }}…")
            val r = runCatching { doPost(s, p, cfg, countOnly, pacer, tag) }
            r.exceptionOrNull()?.let { if (it is StopException) throw it else Log.i("$tag: erro ${it.message}") }
            val n = r.getOrDefault(0L)
            if (n == 0L) s.autoDump("post-${i + 1}-zero")
            if (countOnly) {
                totalCount += n
                Store.updatePost(p.url) { it.exact = n; it.exactInfo = cfg.describe() }
            } else {
                totalLiked += n
                Store.updatePost(p.url) { it.done = true }
            }
            s.back(); s.nap(700); s.back(); s.nap(900)
            if (pacer.dailyReached()) {
                Log.i("Limite diário do perfil atingido (${prof.dailyLimit}). Parando.")
                s.status("Limite diário atingido", "Curtidas hoje: ${Store.likedToday()}")
                return
            }
        }
        if (countOnly) {
            Log.i("Contagem pronta: $totalCount comentários pra curtir")
            s.status("Contagem pronta ✓", "$totalCount comentários pra curtir · veja no app")
        } else {
            Log.i("Fila concluída: $totalLiked curtidas")
            s.status("Concluído ✓", "$totalLiked curtidas nesta rodada")
        }
    }

    /** Abre um post, abre os comentários e percorre. Retorna curtidas dadas (ou comentários contados). */
    private fun doPost(s: BotService, p: Post, cfg: RunConfig, countOnly: Boolean, pacer: Pacer, tag: String): Long {
        if (!Tk.openUrl(s, p.url)) {
            Log.i("$tag: não consegui abrir o link no TikTok (${p.url})")
            return 0
        }
        s.nap(2500)
        s.ensureTikTok(8000)
        val info = s.waitFor(15000) { readVideo(s, it).takeIf { v -> v.commentBtn != null } }
        if (info == null) {
            Log.i("$tag: não achei o botão de comentários")
            s.autoDump("sem-botao-comentarios")
            return 0
        }
        Store.updatePost(p.url) {
            if (info.author.isNotBlank()) it.author = info.author
            if (info.caption.isNotBlank()) it.caption = info.caption
            if (info.comments >= 0) it.comments = info.comments
            if (info.likes >= 0) it.likes = info.likes
        }
        Log.i("$tag: @${info.author} · ${info.comments} comentários · botão: ${Tk.label(info.commentBtn!!).take(50)}")
        s.click(info.commentBtn!!)
        var sheet = s.waitFor(5000) { ns -> ns.firstOrNull { isReplyLabel(it) && s.visible(it) } }
        if (sheet == null) {
            // tenta um toque "de dedo" no botão
            Log.i("$tag: comentários não abriram com o clique; tentando toque na tela")
            s.autoDump("apos-clique-comentarios")
            val r = Tk.rect(info.commentBtn!!)
            s.tap(r.centerX().toFloat(), r.centerY().toFloat())
            sheet = s.waitFor(6000) { ns -> ns.firstOrNull { isReplyLabel(it) && s.visible(it) } }
        }
        if (sheet == null) {
            Log.i("$tag: os comentários não abriram (ou não achei o texto 'Responder')")
            s.autoDump("comentarios-nao-abriram")
            return 0
        }
        s.nap(600)
        setOrder(s, cfg.order, tag)

        val seen = HashSet<String>()
        val clickedExpanders = HashSet<String>()
        var mainLeft = Int.MAX_VALUE
        var done = 0L
        var stale = 0
        var oldStreak = 0
        val goal = if (cfg.perPost > 0) cfg.perPost.toLong() else Long.MAX_VALUE
        var firstScan = true
        var noLikeDumped = false
        var clickFails = 0

        while (true) {
            s.checkStop()
            s.ensureTikTok(3000)
            val ns = s.nodes()
            val rows = scanRows(s, ns)
            rows.forEach { mainLeft = minOf(mainLeft, it.replyRect.left) }
            if (firstScan) {
                firstScan = false
                Log.i("$tag: ${rows.size} comentários na tela · com coração: ${rows.count { it.like != null }} · já curtidos: ${rows.count { it.liked == true }}")
                rows.take(3).forEach { r -> Log.i("   · ${r.key.take(60)} · idade=${r.ageMin}min · curtir=${r.like?.let { Tk.rect(it).toShortString() + " " + Tk.label(it).take(40) } ?: "nenhum"}") }
                if (rows.isEmpty() || rows.none { it.like != null }) s.autoDump("comentarios-abertos")
            }
            var acted = false
            var fresh = 0
            for (row in rows) {
                if (!seen.add(row.key)) continue
                fresh++
                val isReply = row.replyRect.left > mainLeft + s.dp(20)
                if (cfg.which == Which.MAIN && isReply) continue
                if (cfg.which == Which.REPLIES && !isReply) continue
                if (cfg.maxAgeMin > 0 && row.ageMin >= 0) {
                    if (row.ageMin > cfg.maxAgeMin) {
                        if (!isReply) oldStreak++
                        if (cfg.order == Order.RECENT && oldStreak >= 4) {
                            Log.i("$tag: chegou nos comentários mais velhos que ${RunConfig.ageLabel(cfg.maxAgeMin)}")
                            return done
                        }
                        continue
                    } else if (!isReply) oldStreak = 0
                }
                if (row.liked == true) continue
                if (countOnly) {
                    done++
                    if (done % 25 == 0L) s.status("$tag · contando", "$done comentários até agora")
                    if (done >= goal) return done
                    continue
                }
                val like = row.like
                if (like == null) {
                    if (!noLikeDumped) { noLikeDumped = true; Log.i("$tag: não achei o coração de '${row.author.take(30)}'"); s.autoDump("sem-coracao") }
                    continue
                }
                if (!s.click(like)) {
                    clickFails++
                    Log.i("$tag: o toque no coração falhou")
                    if (clickFails >= 5) { s.autoDump("toque-falhou"); return done }
                    continue
                }
                done++
                pacer.liked()
                Store.updatePost(p.url) { it.liked++ }
                s.status("$tag · ${done} curtidas", "Hoje: ${Store.likedToday()} · ${row.author.take(24)}")
                if (done >= goal) { Log.i("$tag: meta de ${cfg.perPost} curtidas no post"); return done }
                if (pacer.dailyReached()) return done
                pacer.waitNext(tag)
                acted = true
                break   // a tela pode ter mudado: lê de novo
            }
            if (acted) continue
            if (fresh > 0) stale = 0

            // abrir respostas escondidas ("Ver 3 respostas")
            if (cfg.which != Which.MAIN) {
                val exp = s.nodes().firstOrNull { n ->
                    s.visible(n) && isExpander(n) && clickedExpanders.add(expanderKey(n))
                }
                if (exp != null) { s.click(exp); s.nap(1300); continue }
            }

            // rolar a lista
            if (!scrollComments(s)) {
                Log.i("$tag: não consegui rolar a lista")
            }
            s.nap(900)
            val after = scanRows(s, s.nodes()).count { it.key !in seen }
            if (after == 0) {
                stale++
                if (stale >= 4) {
                    Log.i("$tag: fim dos comentários (${seen.size} lidos)")
                    return done
                }
            } else stale = 0
            if (countOnly && seen.size % 50 == 0) s.status("$tag · contando", "$done de ${seen.size} lidos")
        }
    }

    private fun isReplyLabel(n: AccessibilityNodeInfo): Boolean {
        val t = Tk.norm(n.text)
        return t == "responder" || t == "reply"
    }

    private val EXPANDER = Regex("^(ver|view|exibir|mostrar)\\s+(mais\\s+)?(\\d+\\s+)?(mais\\s+)?(resposta|respostas|reply|replies|more)")

    private fun isExpander(n: AccessibilityNodeInfo): Boolean = EXPANDER.containsMatchIn(Tk.norm(n.text))

    private fun expanderKey(n: AccessibilityNodeInfo): String {
        // texto + o comentário acima dele (a posição muda ao rolar)
        val r = Tk.rect(n)
        val parentText = n.parent?.let { pp -> Tk.all(pp).mapNotNull { it.text?.toString() }.joinToString("|").take(80) } ?: ""
        return Tk.norm(n.text) + "#" + parentText + "#" + (r.left / 10)
    }

    private val SKIP_TEXT = Regex("^(responder|reply|criador|creator|autor|author|fixado|pinned|curtido pelo criador|liked by creator|\\d+|ver .*|view .*|·.*)$")

    /** Lê os comentários visíveis: cada um tem um "Responder" embaixo. */
    fun scanRows(s: BotService, ns: List<AccessibilityNodeInfo>): List<Row> {
        val W = s.W
        val vis = ns.filter { s.visible(it) }
        val replies = vis.filter { isReplyLabel(it) }.map { it to Tk.rect(it) }.sortedBy { it.second.top }
        val texts = vis.filter { !it.text.isNullOrBlank() }.map { it to Tk.rect(it) }
        val out = ArrayList<Row>()
        var prevBottom = -1
        for ((_, rr) in replies) {
            val top = if (prevBottom < 0) rr.top - s.dp(200) else prevBottom - 2
            val block = texts.filter { (n, r) ->
                r.top >= top && r.bottom <= rr.top + s.dp(4) && r.left >= rr.left - s.dp(10) && r.right <= (W * 0.86).toInt() &&
                    !SKIP_TEXT.matches(Tk.norm(n.text))
            }.sortedWith(compareBy({ it.second.top }, { it.second.left }))
            prevBottom = rr.bottom
            if (block.isEmpty()) continue
            val author = block.first().first.text.toString()
            val bodyText = block.drop(1).joinToString(" ") { it.first.text.toString() }
            val key = Tk.norm(author) + "|" + Tk.norm(bodyText).take(100) + if (bodyText.isBlank()) "|y" + (rr.top / 40) else ""

            val rowTop = rr.top - s.dp(8); val rowBot = rr.bottom + s.dp(8)
            // idade: texto na mesma linha, à esquerda do "Responder"
            var age = -1L
            for ((n, r) in texts) {
                if (r.centerY() in rowTop..rowBot && r.right <= rr.left + s.dp(4)) {
                    val a = Tk.parseAgeMinutes(n.text?.toString())
                    if (a >= 0) { age = a; break }
                }
            }
            // botão de curtir: à direita, na mesma linha (o coração vem antes do "não curtir")
            val cands = vis.filter { n ->
                val r = Tk.rect(n)
                r.centerY() in (rr.top - s.dp(22))..(rr.bottom + s.dp(22)) && r.centerX() > W * 0.6
            }.mapNotNull { Tk.clickableAncestor(it) }.distinctBy { Tk.rect(it).flattenToString() }
                .filter { Tk.rect(it).centerX() > W * 0.6 && Tk.rect(it).width() < W * 0.4 }
            val notDislike = cands.filter { c -> !isDislike(c) }
            val like = notDislike.minByOrNull { Tk.rect(it).left }
            out.add(Row(key, author, age, rr, like, like?.let { likedState(it) }))
        }
        return out
    }

    private fun isDislike(n: AccessibilityNodeInfo): Boolean {
        val l = Tk.all(n).joinToString(" ") { Tk.label(it) }
        return Regex("nao curti|nao gost|dislike|descurtir comentario e ocultar").containsMatchIn(l)
    }

    /** true = já curtido; false = não curtido; null = não sei. */
    private fun likedState(n: AccessibilityNodeInfo): Boolean? {
        val all = Tk.all(n)
        if (all.any { it.isSelected || it.isChecked }) return true
        val l = all.joinToString(" ") { Tk.label(it) }
        if (Regex("curtido|liked|descurtir|unlike|remover curtida|remove like").containsMatchIn(l)) return true
        if (Regex("curtir|like").containsMatchIn(l)) return false
        return null
    }

    private fun scrollComments(s: BotService): Boolean {
        val ns = s.nodes()
        val list = ns.filter { it.isScrollable && s.visible(it) }
            .map { it to Tk.rect(it) }
            .filter { it.second.top > s.H * 0.2 || it.second.height() > s.H * 0.4 }
            .filter { (n, _) -> Tk.all(n).any { isReplyLabel(it) } }
            .maxByOrNull { it.second.width() * it.second.height() }
        if (list != null && list.first.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        val x = s.W * 0.45f
        return s.swipe(x, s.H * 0.82f, x, s.H * 0.48f, 350)
    }

    private fun setOrder(s: BotService, order: Order, tag: String) {
        val want = if (order == Order.RECENT) Regex("recente|newest|latest|mais novo") else Regex("relevan|top|principa|popular")
        val header = s.nodes().filter { s.visible(it) && Tk.rect(it).top < s.H * 0.6 }.firstOrNull { n ->
            val l = Tk.label(n)
            Regex("comentarios mais|mais recentes|mais relevantes|recentes|relevantes|ordenar|sort|newest|top comments").containsMatchIn(l) &&
                !n.isEditable && l.length < 60
        }
        if (header == null) {
            Log.i("$tag: não achei a ordenação dos comentários; usando a do TikTok")
            s.autoDump("sem-ordenacao")
            return
        }
        if (want.containsMatchIn(Tk.label(header))) {
            Log.i("$tag: ordem já está em ${order.label}")
            return
        }
        s.click(header)
        s.nap(1200)
        val opt = s.waitFor(3000) { ns ->
            ns.filter { s.visible(it) }.firstOrNull { n ->
                val l = Tk.label(n)
                want.containsMatchIn(l) && l.length < 40 && !l.startsWith("comentarios")
            }
        }
        if (opt != null) {
            s.click(opt); s.nap(1500)
            Log.i("$tag: ordem trocada para ${order.label}")
        } else {
            Log.i("$tag: não achei a opção ${order.label}")
            s.back(); s.nap(600)
        }
    }

    // ======================================================================
    //  Ler a tela de um vídeo
    // ======================================================================

    fun readVideo(s: BotService, ns: List<AccessibilityNodeInfo>): VideoInfo {
        val W = s.W; val H = s.H
        val v = VideoInfo()
        val vis = ns.filter { s.visible(it) }
        val right = vis.filter { Tk.rect(it).centerX() > W * 0.72 && !it.isEditable }

        fun countNear(btn: AccessibilityNodeInfo): Long {
            val own = Tk.parseCount(btn.contentDescription?.toString())
            if (own >= 0) return own
            Tk.all(btn).forEach { c -> Tk.parseCount(c.text?.toString()).let { if (it >= 0) return it } }
            val r = Tk.rect(btn)
            vis.filter { n -> val q = Tk.rect(n); q.top >= r.bottom - s.dp(6) && q.top <= r.bottom + s.dp(36) && Math.abs(q.centerX() - r.centerX()) < s.dp(40) }
                .forEach { n -> Tk.parseCount(n.text?.toString()).let { if (it >= 0) return it } }
            return -1
        }

        v.commentBtn = right.firstOrNull { n ->
            val l = Tk.label(n); (l.contains("coment") || l.contains("comment")) && !l.contains("adicionar") && !l.contains("add comment")
        }?.let { Tk.clickableAncestor(it) ?: it }
        v.commentBtn?.let { v.comments = countNear(it) }

        right.firstOrNull { n ->
            val l = Tk.label(n); (l.contains("curt") || l.contains("like")) && !l.contains("coment") && !l.contains("comment")
        }?.let { b -> v.likes = countNear(Tk.clickableAncestor(b) ?: b) }

        v.shareBtn = right.firstOrNull { n ->
            val l = Tk.label(n); l.contains("compartilh") || l.contains("share")
        }?.let { Tk.clickableAncestor(it) ?: it }

        // autor e legenda: textos embaixo à esquerda
        val navWords = Regex("^(inicio|pagina inicial|amigos|caixa de entrada|perfil|loja|seguindo|para voce|explorar|home|friends|inbox|profile|shop)$")
        val bottomLeft = vis.filter { n ->
            val r = Tk.rect(n); val t = Tk.norm(n.text)
            !n.text.isNullOrBlank() && r.top > H * 0.5 && r.bottom < H * 0.92 && r.left < W * 0.5 && r.right < W * 0.9 &&
                !navWords.matches(t) && !t.contains("som original") && !t.contains("original sound") &&
                !(n.viewIdResourceName ?: "").contains("music")
        }.sortedBy { Tk.rect(it).top }
        val byId = vis.firstOrNull { (it.viewIdResourceName ?: "").endsWith(":id/title") && (it.className?.toString() ?: "").contains("Button") && !it.text.isNullOrBlank() }
        val descNode = vis.firstOrNull { (it.viewIdResourceName ?: "").endsWith(":id/desc") && !it.text.isNullOrBlank() }
        val authorNode = byId ?: bottomLeft.firstOrNull { it.text.toString().startsWith("@") }
            ?: bottomLeft.firstOrNull { n -> val t = n.text.toString(); !t.contains(' ') && t.length in 2..30 && Tk.parseCount(t) < 0 }
        v.author = authorNode?.text?.toString()?.removePrefix("@") ?: ""
        v.caption = (descNode?.text?.toString() ?: bottomLeft.filter { it !== authorNode }.maxByOrNull { it.text.length }?.text?.toString() ?: "")
            .replace("\u2060", "").removeSuffix("mais").removeSuffix("…").trim().take(200)
        for (n in bottomLeft) {
            val t = n.text.toString().replace("·", "").trim()
            if (Tk.parseAgeMinutes(t) >= 0) { v.age = t; break }
        }
        return v
    }

    // ======================================================================
    //  Copiar o link do vídeo aberto (Compartilhar > Copiar link)
    // ======================================================================

    fun copyLink(s: BotService, info: VideoInfo): String? {
        val share = info.shareBtn ?: run { Log.i("Não achei o botão Compartilhar"); return null }
        s.click(share)
        val copy = s.waitFor(4000) { ns ->
            ns.filter { s.visible(it) }.firstOrNull { n ->
                val l = Tk.label(n); l == "copiar link" || l.startsWith("copiar link") || l == "copy link" || l.startsWith("copy link") || l == "copiar"
            }
        }
        if (copy == null) {
            Log.i("Não achei 'Copiar link' no Compartilhar")
            s.autoDump("sem-copiar-link")
            s.back(); s.nap(600)
            return null
        }
        s.click(copy)
        s.nap(700)
        val clip = s.readClipboard()
        s.nap(900)
        val url = Store.extractUrl(clip)
        if (url == null) Log.i("A área de transferência não tinha link (${clip?.take(40)})")
        return url
    }

    // ======================================================================
    //  ＋ Fila: adiciona o post que está aberto no TikTok
    // ======================================================================

    fun grabCurrent(s: BotService) {
        s.status("Adicionando…", "Lendo o post")
        s.ensureTikTok(0)
        val info = readVideo(s, s.nodes())
        if (info.shareBtn == null) {
            s.autoDump("fila-sem-compartilhar")
            s.status("Abra um vídeo", "Deixe o vídeo aberto em tela cheia e toque ＋ Fila")
            return
        }
        val url = copyLink(s, info)
        if (url == null) {
            s.status("Não consegui pegar o link", "Tente: Compartilhar › Mais › Viraliza+")
            return
        }
        val isNew = Store.addToQueue(Post(url, info.author, info.caption, info.comments, info.likes, info.age))
        val c = if (info.comments >= 0) " · ${formatCount(info.comments)} coment." else ""
        s.status(if (isNew) "Na fila ✓" else "Já estava na fila", "@${info.author}$c")
        Log.i("Fila: @${info.author} $url")
    }

    // ======================================================================
    //  Pesquisar no TikTok e listar os posts
    // ======================================================================

    private val DATE_LABELS = listOf(
        listOf(),
        listOf("ultimas 24 horas", "ultimo dia", "past 24 hours", "24 horas", "last 24 hours"),
        listOf("esta semana", "ultima semana", "this week", "past week", "last week"),
        listOf("este mes", "ultimo mes", "this month", "past month", "last month"),
        listOf("ultimos 3 meses", "ultimos tres meses", "last 3 months", "past 3 months", "3 meses"),
        listOf("ultimos 6 meses", "ultimos seis meses", "last 6 months", "past 6 months", "6 meses")
    )

    fun search(s: BotService, cfg: SearchConfig) {
        Log.i("Pesquisa: \"${cfg.term}\" data=${cfg.date} ordem=${cfg.sort} qtd=${cfg.count}")
        s.status("Pesquisando", "Abrindo o TikTok…")
        if (!Tk.openApp(s)) { s.status("TikTok não encontrado", ""); return }
        s.nap(3500)
        s.ensureTikTok(8000)

        // botão de lupa no topo
        var lupa = findSearchButton(s)
        if (lupa == null) {
            s.nodes().firstOrNull { s.visible(it) && Regex("^(pagina inicial|inicio|home)$").matches(Tk.label(it)) }?.let { s.click(it); s.nap(1500) }
            lupa = findSearchButton(s)
        }
        if (lupa == null) { Log.i("Não achei a lupa de pesquisa"); s.autoDump("sem-lupa"); s.status("Não achei a pesquisa", "Use 🩺 na tela inicial do TikTok"); return }
        s.click(lupa)
        val edit = s.waitFor(5000) { ns -> ns.firstOrNull { it.isEditable && s.visible(it) } }
        if (edit == null) { Log.i("Campo de pesquisa não apareceu"); return }
        s.click(edit); s.nap(400)
        s.setText(edit, cfg.term); s.nap(700)
        val sent = s.imeEnter(edit) || run {
            val btn = s.nodes().firstOrNull { n ->
                s.visible(n) && !n.isEditable && Regex("^(pesquisar|search|buscar)$").matches(Tk.label(n)) && Tk.rect(n).top < s.H * 0.2
            }
            btn != null && s.click(btn)
        }
        if (!sent) { Log.i("Não consegui enviar a pesquisa"); return }
        s.nap(3000)

        // aba Vídeos
        s.waitFor(5000) { ns -> ns.firstOrNull { s.visible(it) && Regex("^videos?$").matches(Tk.label(it)) } }?.let { s.click(it); s.nap(2500) }
            ?: Log.i("Não achei a aba Vídeos")

        if (cfg.date != 0 || cfg.sort != 0) applyFilters(s, cfg)

        s.status("Pesquisando", "Lendo os resultados…")
        val card = s.waitFor(8000) { ns -> gridCards(s, ns).firstOrNull() }
        if (card == null) { Log.i("Não achei os vídeos do resultado"); s.autoDump("sem-resultados"); s.status("Sem resultados", "Use 🩺 na tela de resultados"); return }
        s.click(card)
        s.nap(2500)

        var got = 0
        var misses = 0
        var lastUrl = ""
        var repeat = 0
        while (got < cfg.count && misses < 6) {
            s.checkStop()
            s.ensureTikTok(3000)
            val info = s.waitFor(6000) { ns -> readVideo(s, ns).takeIf { it.shareBtn != null } }
            if (info == null) { misses++; nextVideo(s); continue }
            val url = copyLink(s, info)
            if (url == null) { misses++; nextVideo(s); continue }
            if (url == lastUrl) { repeat++; if (repeat >= 2) { Log.i("Acabaram os resultados"); break } } else repeat = 0
            lastUrl = url
            Store.addResult(Post(url, info.author, info.caption, info.comments, info.likes, info.age))
            got++
            s.status("Pesquisando · $got/${cfg.count}", "@${info.author} · ${if (info.comments >= 0) formatCount(info.comments) else "?"} coment.")
            nextVideo(s)
        }
        Log.i("Pesquisa terminou: $got posts")
        s.status("Pesquisa pronta ✓", "$got posts · abra o Viraliza+ para escolher")
    }

    private fun nextVideo(s: BotService) {
        val x = s.W * 0.5f
        s.swipe(x, s.H * 0.72f, x, s.H * 0.22f, 260)
        s.nap(1800 + Random.nextLong(0, 700))
    }

    private fun findSearchButton(s: BotService): AccessibilityNodeInfo? =
        s.nodes().filter { s.visible(it) && Tk.rect(it).top < s.H * 0.18 && !it.isEditable }.firstOrNull { n ->
            val l = Tk.label(n); l.contains("pesquis") || l == "search" || l.startsWith("search") || l.contains("buscar")
        }

    private fun gridCards(s: BotService, ns: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> =
        ns.filter { n ->
            if (!n.isClickable || !s.visible(n)) return@filter false
            val r = Tk.rect(n)
            r.width() > s.W * 0.28 && r.width() < s.W * 0.56 && r.height() > r.width() && r.top > s.H * 0.12
        }.sortedWith(compareBy({ Tk.rect(it).top / 50 }, { Tk.rect(it).left }))

    private fun applyFilters(s: BotService, cfg: SearchConfig) {
        val f = s.nodes().filter { s.visible(it) && Tk.rect(it).top < s.H * 0.3 }.firstOrNull { n ->
            val l = Tk.label(n); l.contains("filtro") || l.contains("filter")
        }
        if (f == null) { Log.i("Não achei o botão de filtros; seguindo sem filtro"); s.autoDump("sem-filtros"); return }
        s.click(f); s.nap(1500)
        fun pick(labels: List<String>, name: String) {
            val n = s.waitFor(2500) { ns -> ns.filter { s.visible(it) }.firstOrNull { x -> val l = Tk.label(x); labels.any { l == it || l.startsWith(it) } } }
            if (n != null) { s.click(n); s.nap(600); Log.i("Filtro: $name ✓") } else Log.i("Filtro: não achei \"$name\"")
        }
        if (cfg.sort == 1) pick(listOf("mais curtidos", "mais curtidas", "most liked"), "Mais curtidos")
        if (cfg.date in 1..5) pick(DATE_LABELS[cfg.date], DATE_LABELS[cfg.date].first())
        val apply = s.nodes().firstOrNull { s.visible(it) && Regex("^(aplicar|apply|confirmar|concluir|ok|mostrar resultados|show results)$").matches(Tk.label(it)) }
        if (apply != null) s.click(apply) else s.back()
        s.nap(3000)
    }
}

/** Controla o ritmo: intervalo entre curtidas, ciclos de trabalho/pausa e limite diário. */
class Pacer(private val s: BotService, private val p: Profile) {
    private var sessionStart = System.currentTimeMillis()
    private var sessionLikes = 0

    fun liked() {
        Store.addLikedToday()
        Store.addTotal()
        sessionLikes++
    }

    fun dailyReached() = p.dailyLimit > 0 && Store.likedToday() >= p.dailyLimit

    fun waitNext(tag: String) {
        val byLikes = p.workLikes > 0 && sessionLikes >= p.workLikes
        val byTime = p.workMinutes > 0 && System.currentTimeMillis() - sessionStart >= p.workMinutes * 60_000L
        if (byLikes || byTime) {
            val lo = minOf(p.pauseMin, p.pauseMax); val hi = maxOf(p.pauseMin, p.pauseMax)
            val minutes = if (hi > lo) Random.nextInt(lo, hi + 1) else lo
            val end = System.currentTimeMillis() + minutes * 60_000L
            Log.i("Descanso de $minutes min depois de $sessionLikes curtidas")
            while (System.currentTimeMillis() < end) {
                val left = end - System.currentTimeMillis()
                s.status("Descansando ☕ ${formatDuration(left)}", "$tag · volta sozinho · hoje: ${Store.likedToday()}")
                s.nap(1000)
            }
            sessionStart = System.currentTimeMillis()
            sessionLikes = 0
            Log.i("Voltando a curtir")
        } else {
            val lo = minOf(p.minDelay, p.maxDelay); val hi = maxOf(p.minDelay, p.maxDelay)
            val sec = if (hi > lo) lo + Random.nextDouble() * (hi - lo) else lo
            s.nap((sec * 1000).toLong().coerceAtLeast(150))
        }
    }

    companion object {
        /** Tempo estimado (ms) para dar `likes` curtidas com o perfil. */
        fun estimate(p: Profile, likes: Long, posts: Int): Long {
            if (likes <= 0) return 0
            val per = (p.minDelay + p.maxDelay) / 2.0 + 0.7          // + tempo de leitura/rolagem
            val workMs = likes * per * 1000 + posts * 9000.0           // abrir cada post
            var perSession = Double.MAX_VALUE
            if (p.workLikes > 0) perSession = p.workLikes.toDouble()
            if (p.workMinutes > 0) perSession = minOf(perSession, p.workMinutes * 60.0 / per)
            val pauses = if (perSession == Double.MAX_VALUE) 0.0 else Math.ceil(likes / perSession) - 1
            val pauseMs = pauses.coerceAtLeast(0.0) * (p.pauseMin + p.pauseMax) / 2.0 * 60_000
            return (workMs + pauseMs).toLong()
        }
    }
}
