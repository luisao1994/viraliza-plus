package com.viraliza.plus

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Cores do app. Segue o tema do celular; o escuro (preto + rosa) é o principal. */
object T {
    var dark = true
        private set

    var bg = 0; var surface = 0; var surface2 = 0; var border = 0; var nav = 0
    var text = 0; var text2 = 0; var text3 = 0
    var accent = 0; var onAccent = 0; var accentSoft = 0; var accentText = 0
    var good = 0; var goodSoft = 0; var goodText = 0
    var blue = 0; var warn = 0; var bad = 0; var badBorder = 0

    fun init(ctx: Context) {
        dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_NO
        if (dark) {
            bg = Color.parseColor("#0E0E10")
            surface = Color.parseColor("#17171A")
            surface2 = Color.parseColor("#1F1F23")
            border = Color.parseColor("#2A2A2F")
            nav = Color.parseColor("#141416")
            text = Color.parseColor("#F5F2EA")
            text2 = Color.parseColor("#A8A397")
            text3 = Color.parseColor("#7A766E")
            accent = Color.parseColor("#FF4D7A")
            onAccent = Color.parseColor("#FFFFFF")
            accentSoft = Color.parseColor("#2C1219")
            accentText = Color.parseColor("#F2B8C6")
            good = Color.parseColor("#5FD08A")
            goodSoft = Color.parseColor("#142A1D")
            goodText = Color.parseColor("#A9D9BC")
            blue = Color.parseColor("#7FB2F0")
            warn = Color.parseColor("#F0A04B")
            bad = Color.parseColor("#F08A7E")
            badBorder = Color.parseColor("#3A2A28")
        } else {
            bg = Color.parseColor("#F6F3EC")
            surface = Color.parseColor("#FFFFFF")
            surface2 = Color.parseColor("#EFEAE0")
            border = Color.parseColor("#E2DCCF")
            nav = Color.parseColor("#FFFFFF")
            text = Color.parseColor("#1B1A17")
            text2 = Color.parseColor("#6B665C")
            text3 = Color.parseColor("#8E887C")
            accent = Color.parseColor("#E0245E")
            onAccent = Color.parseColor("#FFFFFF")
            accentSoft = Color.parseColor("#FBE1E9")
            accentText = Color.parseColor("#8A1238")
            good = Color.parseColor("#1F8A4C")
            goodSoft = Color.parseColor("#DDF2E4")
            goodText = Color.parseColor("#1F6B3E")
            blue = Color.parseColor("#2F6DB5")
            warn = Color.parseColor("#B8650B")
            bad = Color.parseColor("#C0392B")
            badBorder = Color.parseColor("#F0D3CF")
        }
    }
}

/** Fonte Manrope (embutida) nos pesos usados; cai para a do sistema em celulares antigos. */
object Fonts {
    private val cache = HashMap<Int, Typeface>()
    private var ctx: Context? = null

    fun init(c: Context) { ctx = c.applicationContext }

    fun get(weight: Int): Typeface {
        cache[weight]?.let { return it }
        val tf = try {
            if (Build.VERSION.SDK_INT >= 26) {
                Typeface.Builder(ctx!!.assets, "fonts/manrope.ttf")
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            } else null
        } catch (e: Throwable) { null } ?: when {
            weight >= 700 -> Typeface.create("sans-serif", Typeface.BOLD)
            weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        cache[weight] = tf
        return tf
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1, dashed: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = ctx.dpf(radiusDp.toFloat())
        if (strokeColor != null) {
            if (dashed) setStroke(ctx.dp(strokeDp), strokeColor, ctx.dpf(5f), ctx.dpf(4f))
            else setStroke(ctx.dp(strokeDp), strokeColor)
        }
    }

/** Fundo com cantos só em cima (folha que sobe sobre a mídia). */
fun topRounded(ctx: Context, color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    val r = ctx.dpf(radiusDp.toFloat())
    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
}

fun pressable(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1, dashed: Boolean = false): RippleDrawable {
    val ripple = if (T.dark) Color.argb(40, 255, 255, 255) else Color.argb(30, 0, 0, 0)
    return RippleDrawable(
        ColorStateList.valueOf(ripple),
        rounded(ctx, color, radiusDp, strokeColor, strokeDp, dashed),
        rounded(ctx, Color.WHITE, radiusDp)
    )
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(ctx: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))
}

fun vbox(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    if (padDp > 0) setPadding(ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp), ctx.dp(padDp))
}

fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun text(
    ctx: Context, s: CharSequence, sizeSp: Float = 15f, color: Int = T.text,
    bold: Boolean = false, maxLines: Int = 0, weight: Int = 0
): TextView = TextView(ctx).apply {
    this.text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Fonts.get(if (weight > 0) weight else if (bold) 700 else 500)
    includeFontPadding = false
    if (maxLines > 0) {
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }
    setLineSpacing(0f, 1.18f)
}

fun title(ctx: Context, s: String, sizeSp: Float = 22f) = text(ctx, s, sizeSp, T.text, weight = 800).apply {
    letterSpacing = -0.015f
}

fun sectionLabel(ctx: Context, s: String) = text(ctx, s.uppercase(), 11.5f, T.text2, weight = 700).apply {
    letterSpacing = 0.09f
}

/** Ícone vetorial colorido. */
fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int = 22): ImageView = ImageView(ctx).apply {
    setImageResource(res)
    setColorFilter(color)
    layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
}

/** Botão quadrado só com ícone (44dp, alvo de toque confortável). */
fun iconButton(ctx: Context, res: Int, color: Int, bg: Int, desc: String, sizeDp: Int = 44, radiusDp: Int = 14, iconDp: Int = 20, stroke: Int? = null, onClick: () -> Unit): FrameLayout =
    FrameLayout(ctx).apply {
        background = pressable(ctx, bg, radiusDp, stroke)
        contentDescription = desc
        isClickable = true
        isFocusable = true
        val iv = ImageView(ctx).apply { setImageResource(res); setColorFilter(color) }
        addView(iv, FrameLayout.LayoutParams(ctx.dp(iconDp), ctx.dp(iconDp), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        setOnClickListener { onClick() }
    }

enum class BtnStyle { PRIMARY, SECONDARY, GHOST, DANGER, SOFT }

fun button(ctx: Context, label: String, style: BtnStyle = BtnStyle.SECONDARY, iconRes: Int = 0, onClick: () -> Unit): TextView =
    TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        typeface = Fonts.get(if (style == BtnStyle.PRIMARY) 800 else 700)
        includeFontPadding = false
        minHeight = ctx.dp(48)
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        isClickable = true
        isFocusable = true
        val (fg, bgd) = when (style) {
            BtnStyle.PRIMARY -> T.onAccent to pressable(ctx, T.accent, 16)
            BtnStyle.SECONDARY -> T.text to pressable(ctx, T.surface2, 14)
            BtnStyle.GHOST -> T.text to pressable(ctx, Color.TRANSPARENT, 14, T.border)
            BtnStyle.DANGER -> T.bad to pressable(ctx, Color.TRANSPARENT, 14, T.badBorder)
            BtnStyle.SOFT -> T.accent to pressable(ctx, T.accentSoft, 12)
        }
        setTextColor(fg)
        background = bgd
        if (iconRes != 0) {
            val d = ctx.getDrawable(iconRes)!!.mutate()
            d.setTint(fg)
            d.setBounds(0, 0, ctx.dp(18), ctx.dp(18))
            setCompoundDrawables(d, null, null, null)
            compoundDrawablePadding = ctx.dp(8)
        }
        setOnClickListener { onClick() }
    }

fun input(ctx: Context, hint: String, numeric: Boolean = false, value: String = ""): EditText = EditText(ctx).apply {
    this.hint = hint
    setText(value)
    setTextColor(T.text)
    setHintTextColor(T.text3)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    typeface = Fonts.get(600)
    background = rounded(ctx, T.surface2, 14)
    setPadding(ctx.dp(14), ctx.dp(13), ctx.dp(14), ctx.dp(13))
    isSingleLine = true
    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
}

fun card(ctx: Context, padDp: Int = 16, radiusDp: Int = 22): LinearLayout = vbox(ctx, padDp).apply {
    background = rounded(ctx, T.surface, radiusDp, T.border)
}

fun badge(ctx: Context, s: String, fg: Int, bg: Int): TextView = text(ctx, s, 12.5f, fg, weight = 800).apply {
    background = rounded(ctx, bg, 10)
    setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
}

/** Círculo com iniciais (páginas). */
fun avatar(ctx: Context, name: String, sizeDp: Int = 44): TextView {
    val initials = name.split(Regex("\\s+")).filter { it.isNotBlank() && it[0].isLetterOrDigit() }
        .take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }
    val tints = listOf("#F2C14E", "#8FD3B0", "#B9C4F0", "#F0B38F", "#D7A9E3", "#9FD6E8")
    val tint = Color.parseColor(tints[Math.floorMod(name.hashCode(), tints.size)])
    return text(ctx, initials, (sizeDp / 3f), Color.parseColor("#0E0E10"), weight = 800).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tint) }
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }
}

fun spacer(ctx: Context, hDp: Int) = View(ctx).apply { layoutParams = lp(h = ctx.dp(hDp)) }

/** Seletor segmentado (abas de largura igual numa trilha). */
class Segmented<V>(
    private val ctx: Context,
    private val options: List<Pair<V, String>>,
    initial: V,
    private val onChange: (V) -> Unit
) {
    var value: V = initial
        private set
    private val views = mutableListOf<TextView>()
    val view: LinearLayout = hbox(ctx).apply {
        background = rounded(ctx, if (T.dark) T.bg else T.surface2, 15, if (T.dark) null else T.border)
        setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        options.forEach { (v, label) ->
            val tv = text(ctx, label, 13.5f, T.text2, weight = 700, maxLines = 1).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dp(38)
                isClickable = true
                setOnClickListener { select(v, true) }
            }
            views.add(tv)
            addView(tv, lp(0, weight = 1f))
        }
    }

    init { paint() }

    fun select(v: V, notify: Boolean) {
        value = v
        paint()
        if (notify) onChange(v)
    }

    private fun paint() {
        options.forEachIndexed { i, (v, _) ->
            val on = v == value
            views[i].setTextColor(if (on) T.onAccent else T.text2)
            views[i].typeface = Fonts.get(if (on) 800 else 600)
            views[i].background = if (on) rounded(ctx, T.accent, 11) else null
        }
    }
}

/** Grupo de opções em "pílulas"; uma selecionada por vez (rolagem horizontal). */
class Choice<V>(
    private val ctx: Context,
    private val options: List<Pair<V, String>>,
    initial: V,
    private val onChange: (V) -> Unit
) {
    var value: V = initial
        private set
    private val views = mutableListOf<TextView>()
    val view: HorizontalScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        val row = hbox(ctx)
        options.forEachIndexed { i, (v, label) ->
            val tv = text(ctx, label, 13.5f, T.text, weight = 700).apply {
                gravity = Gravity.CENTER
                minHeight = ctx.dp(36)
                setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
                isClickable = true
                setOnClickListener { select(v, true) }
            }
            views.add(tv)
            row.addView(tv, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(ctx, r = if (i < options.size - 1) 6 else 0))
        }
        addView(row)
    }

    init { paint() }

    fun select(v: V, notify: Boolean) {
        value = v
        paint()
        if (notify) onChange(v)
    }

    private fun paint() {
        options.forEachIndexed { i, (v, _) ->
            val on = v == value
            val tv = views[i]
            tv.setTextColor(if (on) T.onAccent else T.text2)
            tv.typeface = Fonts.get(if (on) 800 else 600)
            tv.background = pressable(ctx, if (on) T.accent else T.surface2, 18)
        }
    }
}

/** Interruptor simples (liga/desliga) em forma de pílula. */
class Toggle(private val ctx: Context, label: String, initial: Boolean, private val onChange: (Boolean) -> Unit) {
    var checked = initial
        private set
    val view: TextView = text(ctx, label, 13.5f, T.text, weight = 700).apply {
        minHeight = ctx.dp(36)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        setOnClickListener {
            checked = !checked
            paint()
            onChange(checked)
        }
    }
    private val baseLabel = label

    init { paint() }

    private fun paint() {
        view.text = (if (checked) "✓  " else "") + baseLabel
        view.setTextColor(if (checked) T.accent else T.text2)
        view.background = pressable(ctx, if (checked) T.accentSoft else T.surface2, 18)
    }
}

/** Pílula de filtro (País, Mídia, Tempo…). */
fun filterChip(ctx: Context, label: String, dashed: Boolean = false, onClick: () -> Unit): TextView =
    text(ctx, label, 13.5f, if (dashed) T.text2 else T.text, weight = 700, maxLines = 1).apply {
        gravity = Gravity.CENTER
        minHeight = ctx.dp(40)
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        background = if (dashed) pressable(ctx, Color.TRANSPARENT, 20, T.text3, dashed = true)
        else pressable(ctx, T.surface, 20, T.border)
        setOnClickListener { onClick() }
    }

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return String.format("%d:%02d", m, s)
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1f mi", n / 1_000_000.0).replace('.', ',')
    n >= 1_000 -> String.format("%.1f mil", n / 1_000.0).replace('.', ',')
    else -> n.toString()
}
