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
    val liked: Boolean?,  // null = não deu para saber
    val likeTap: Boolean = false,  // true = tocar com o "dedo" (o botão não aceita clique direto)
    val indent: Int = replyRect.left   // esquerda do nome do autor (respostas ficam mais para a direita)
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
            r.exceptionOrNull()?.let { if (it is StopException) throw it else Log.i("$tag: erro ${it.javaClass.simpleName}: ${it.message} @ ${it.stackTrace.firstOrNull { e -> e.className.contains("viraliza") }}") }
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
        // pausa o vídeo antes de abrir os comentários (um toque só; dois toques curtiriam o vídeo)
        s.tap(s.W * 0.4f, s.H * 0.42f)
        Log.i("$tag: vídeo pausado")
        s.nap(800)
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
        val orderOk = setOrder(s, cfg.order, tag)
        val likedBefore = Store.likedKeys(p.url)
        if (likedBefore.isNotEmpty()) Log.i("$tag: ${likedBefore.size} comentários já curtidos antes neste post (vou pular)")

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
            closeSortMenu(s, tag)
            val ns = s.nodes()
            val rows = scanRows(s, ns)
            rows.forEach { mainLeft = minOf(mainLeft, it.indent) }
            if (firstScan) {
                firstScan = false
                Log.i("$tag: ${rows.size} comentários na tela · com coração: ${rows.count { it.like != null }} · já curtidos: ${rows.count { it.liked == true }}")
                rows.take(3).forEach { r -> Log.i("   · ${r.key.take(60)} · idade=${r.ageMin}min · curtir=${r.like?.let { Tk.rect(it).toShortString() + " " + Tk.label(it).take(40) + if (r.likeTap) " (toque)" else "" } ?: "nenhum"} · estado=${r.liked}") }
                if (rows.isEmpty() || rows.none { it.like != null }) s.autoDump("comentarios-abertos")
            }
            var acted = false
            var fresh = 0
            for (row in rows) {
                if (!seen.add(row.key)) continue
                fresh++
                val isReply = row.indent > mainLeft + s.dp(20)
                if (cfg.which == Which.MAIN && isReply) continue
                if (cfg.which == Which.REPLIES && !isReply) continue
                if (cfg.maxAgeMin > 0 && row.ageMin >= 0) {
                    if (row.ageMin > cfg.maxAgeMin) {
                        if (!isReply) oldStreak++
                        // só dá pra parar aqui se a lista está mesmo em "Mais novos"
                        if (cfg.order == Order.RECENT && orderOk && oldStreak >= 4) {
                            Log.i("$tag: chegou nos comentários mais velhos que ${RunConfig.ageLabel(cfg.maxAgeMin)}")
                            return done
                        }
                        continue
                    } else if (!isReply) oldStreak = 0
                }
                if (row.liked == true || row.key in likedBefore) continue
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
                val clicked = if (row.likeTap) {
                    // toca no desenho do coração (não no número), que é o alvo certo
                    val heart = Tk.all(like).drop(1).firstOrNull { (it.className?.toString() ?: "").contains("Image") && Tk.rect(it).width() > 0 }
                    val r = Tk.rect(heart ?: like)
                    s.tap(r.centerX().toFloat(), r.centerY().toFloat())
                } else s.click(like)
                if (!clicked) {
                    clickFails++
                    Log.i("$tag: o toque no coração falhou")
                    if (clickFails >= 5) { s.autoDump("toque-falhou"); return done }
                    continue
                }
                done++
                pacer.liked()
                Store.addLikedKey(p.url, row.key)
                likedBefore.add(row.key)
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

    private val SKIP_TEXT = Regex("^(responder|reply|criador|creator|autor|author|fixado|pinned|curtido pelo criador|liked by creator|\\d+|ver .*|view .*|exibir .*|·.*)$")
    private val COUNT_HEADER = Regex("^[\\d.,\\s]+(mil\\s+|mi\\s+|k\\s+)?(comentarios?|comments?)$")

    /** A lista rolável dos comentários (só o que está dentro dela é comentário). */
    fun commentList(s: BotService, ns: List<AccessibilityNodeInfo>): Rect? =
        ns.filter { it.isScrollable && s.visible(it) }
            .map { it to Tk.rect(it) }
            .filter { (n, r) -> r.top > s.H * 0.2 && Tk.all(n).any { isReplyLabel(it) } }
            .minByOrNull { it.second.height() }   // a mais interna
            ?.second

    /**
     * Lê os comentários visíveis. Cada comentário é um bloco (linha da lista) que tem
     * nome, texto, idade, "Responder" e o coração à direita.
     * Só entram comentários que estão INTEIROS dentro da lista — os cortados no topo/fundo
     * ficam para depois da rolagem (antes o robô tocava no ícone de ordenação achando que era um coração).
     */
    fun scanRows(s: BotService, ns: List<AccessibilityNodeInfo>): List<Row> {
        val W = s.W
        val list = commentList(s, ns) ?: Rect(0, (s.H * 0.35).toInt(), W, (s.H * 0.88).toInt())
        val inList = { r: Rect -> r.height() > 0 && r.width() > 0 && r.top >= list.top && r.bottom <= list.bottom }
        val replies = ns.filter { isReplyLabel(it) && s.visible(it) && inList(Tk.rect(it)) }
            .map { it to Tk.rect(it) }.sortedBy { it.second.top }
        val out = ArrayList<Row>()
        val usedRows = HashSet<String>()
        for ((rn, rr) in replies) {
            // sobe até o bloco do comentário (a linha larga da lista)
            var row: AccessibilityNodeInfo? = rn.parent
            var depth = 0
            while (row != null && depth < 5 && Tk.rect(row).width() < W * 0.7) { row = row.parent; depth++ }
            if (row == null) continue
            val rowRect = Tk.rect(row)
            if (!usedRows.add(rowRect.flattenToString())) continue
            val kids = Tk.all(row).filter { s.visible(it) }

            // nome + texto
            val texts = kids.filter { n ->
                !n.text.isNullOrBlank() && !SKIP_TEXT.matches(Tk.norm(n.text)) && Tk.parseAgeMinutes(n.text?.toString()) < 0 &&
                    Tk.rect(n).bottom <= rr.top + s.dp(4) && Tk.rect(n).right <= (W * 0.97).toInt()
            }.sortedWith(compareBy({ Tk.rect(it).top }, { Tk.rect(it).left }))
            if (texts.isEmpty()) continue
            val authorNode = kids.firstOrNull { (it.viewIdResourceName ?: "").endsWith(":id/title") && !it.text.isNullOrBlank() } ?: texts.first()
            val author = authorNode.text.toString()
            val bodyText = texts.filter { it !== authorNode && it.text.toString() != author }.joinToString(" ") { it.text.toString() }
            if (!inList(Tk.rect(authorNode))) continue   // começo do comentário cortado lá em cima

            // idade: na linha do "Responder"
            var age = -1L
            var ageText = ""
            for (n in kids) {
                val r = Tk.rect(n)
                if (Math.abs(r.centerY() - rr.centerY()) < s.dp(14) && r.right <= rr.left + s.dp(4)) {
                    val a = Tk.parseAgeMinutes(n.text?.toString())
                    if (a >= 0) { age = a; ageText = Tk.norm(n.text); break }
                }
            }
            val key = Tk.norm(author) + "|" + Tk.norm(bodyText).take(100) + if (bodyText.isBlank()) "|$ageText" else ""

            // coração: o botão que tem o número de curtidas (fica à direita, dentro do mesmo bloco)
            var like: AccessibilityNodeInfo? = kids.firstOrNull { (it.viewIdResourceName ?: "").endsWith(":id/tv_like_count") }?.parent
            if (like != null && Tk.rect(like).centerX() <= W * 0.6) like = null
            if (like == null) {
                val cands = kids.filter { n ->
                    val r = Tk.rect(n)
                    r.centerX() > W * 0.62 && r.width() in 1..(W * 0.3).toInt() && r.height() > 0 && !isDislike(n) &&
                        (n.className?.toString() ?: "").let { c -> c.contains("Button") || c.contains("Image") || n.isClickable }
                }
                like = cands.filter { val l = Tk.label(it); l.contains("curt") || l.contains("like") }.firstOrNull()
                    ?: cands.minWithOrNull(compareBy({ Tk.rect(it).left / s.dp(24) }, { Tk.rect(it).top }))
            }
            if (like != null) {
                val lr = Tk.rect(like)
                if (!inList(lr) || lr.height() < s.dp(14)) continue   // coração cortado: espera rolar
            }
            val tap = like != null && !like.isClickable
            out.add(Row(key, author, age, rr, like, like?.let { likedState(it) }, tap, Tk.rect(authorNode).left))
        }
        return out
    }

    private fun isDislike(n: AccessibilityNodeInfo): Boolean {
        val l = Tk.all(n).joinToString(" ") { Tk.label(it) }
        return Regex("nao curti|nao gost|dislike|descurtir comentario e ocultar|joinha|compartilh|share").containsMatchIn(l)
    }

    /** true = já curtido; false = não curtido; null = não sei (o TikTok atual não informa). */
    private fun likedState(n: AccessibilityNodeInfo): Boolean? {
        val all = Tk.all(n)
        if (all.any { it.isSelected || it.isChecked }) return true
        val l = all.joinToString(" ") { Tk.label(it) }
        if (Regex("curtido|liked|descurtir|unlike|remover curtida|remove like").containsMatchIn(l)) return true
        if (Regex("curtir|like").containsMatchIn(l)) return false
        return null
    }

    /** Rola a lista mais ou menos meia tela, devagar (sem embalo), para não pular comentários. */
    private fun scrollComments(s: BotService): Boolean {
        val ns = s.nodes()
        val list = commentList(s, ns)
        if (list != null && list.height() > s.dp(120)) {
            val x = s.W * 0.45f
            val y1 = list.bottom - list.height() * 0.12f
            val y2 = list.top + list.height() * 0.38f
            if (s.swipe(x, y1, x, y2, 750)) return true
        }
        val node = ns.firstOrNull { it.isScrollable && s.visible(it) && Tk.all(it).any { c -> isReplyLabel(c) } && Tk.rect(it).top > s.H * 0.2 }
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        val x = s.W * 0.45f
        return s.swipe(x, s.H * 0.78f, x, s.H * 0.55f, 750)
    }

    private val SORT_OPTIONS = Regex("^(melhores|mais novos|mais recentes|mais relevantes|com midia|do criador|top|newest|best)$")

    /** Menu de ordenação (Melhores / Mais novos / ...) está aberto? */
    private fun sortMenuOpen(s: BotService, ns: List<AccessibilityNodeInfo>): Boolean =
        ns.count { s.visible(it) && SORT_OPTIONS.matches(Tk.norm(it.text)) } >= 2

    /** Se o menu de ordenação ficou aberto por engano, fecha antes de continuar. */
    private fun closeSortMenu(s: BotService, tag: String) {
        if (sortMenuOpen(s, s.nodes())) {
            Log.i("$tag: menu de ordenação aberto por engano; fechando")
            s.back(); s.nap(700)
        }
    }

    /** Troca a ordem dos comentários uma vez, ao abrir o post. Retorna true se ficou na ordem pedida. */
    private fun setOrder(s: BotService, order: Order, tag: String): Boolean {
        val want = if (order == Order.RECENT) Regex("^(mais novos|mais recentes|recentes|newest|latest)$")
                   else Regex("^(melhores|mais relevantes|relevantes|top|best|top comments)$")
        val ns = s.nodes()
        // cabeçalho "15.828 comentários" + ícone de filtro ao lado (o conjunto é clicável)
        val header = ns.firstOrNull { n -> s.visible(n) && Tk.rect(n).top < s.H * 0.6 && COUNT_HEADER.matches(Tk.norm(n.text)) }
        val target = header?.let { h ->
            Tk.clickableAncestor(h)
                ?: h.parent?.let { p -> Tk.all(p).firstOrNull { it !== h && (it.className?.toString() ?: "").contains("Image") } }
        } ?: ns.firstOrNull { n ->
            val l = Tk.label(n)
            s.visible(n) && Tk.rect(n).top < s.H * 0.6 && !n.isEditable && l.length < 60 &&
                Regex("comentarios mais|ordenar|sort|mais recentes|mais relevantes").containsMatchIn(l)
        }
        if (target == null) {
            Log.i("$tag: não achei o botão de ordenação; usando a ordem do TikTok")
            s.autoDump("sem-ordenacao")
            return false
        }
        if (!s.click(target)) { val r = Tk.rect(target); s.tap(r.centerX().toFloat(), r.centerY().toFloat()) }
        val opt = s.waitFor(3000) { l -> l.firstOrNull { s.visible(it) && want.matches(Tk.norm(it.text)) } }
        if (opt == null) {
            Log.i("$tag: o menu de ordenação não abriu (ou não achei ${order.label})")
            s.autoDump("sem-opcao-ordem")
            if (sortMenuOpen(s, s.nodes())) { s.back(); s.nap(600) }
            return false
        }
        s.click(opt)
        s.nap(1800)
        if (sortMenuOpen(s, s.nodes())) { s.back(); s.nap(600) }
        Log.i("$tag: ordem: ${opt.text} ✓")
        return true
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
