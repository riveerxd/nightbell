package me.river.nightbell.ui.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

/**
 * 双语语言层。
 *
 * 上游把所有界面文案直接写成 Kotlin 字符串字面量，没有走 stringResource，
 * 所以这里不改任何一个调用点，只把 androidx.compose.material3.Text 的 import
 * 换成本文件里的同名 Composable：它在渲染前按当前语言做一次查表替换。
 *
 * 语言有三个取值：跟随系统（默认，行为与上游汉化前的中文系统表现一致）、
 * 强制中文、强制英文。偏好由 GlobalSettings.language 持久化，
 * NightbellApplication 在进程启动时把它同步到 [setLanguage]。
 *
 * [languageState] 是 Compose 的 SnapshotState：Text 在组合中读取它，切换语言时
 * 所有界面文案自动重组，不需要重建 Activity，也不会丢掉当前页面状态。
 * 通知、小组件这些没有组合上下文的调用点直接读同一个值，所以口径一致。
 *
 * 翻译数据由 tools/gen_zh.py 从 tools/zh_*.tsv 生成到 Zh.kt，不要手改 Zh.kt。
 */
object L10n {

    enum class Lang { SYSTEM, ZH, EN }

    private val languageState = mutableStateOf(Lang.SYSTEM)

    @Volatile
    private var appContext: Context? = null

    /** 在 Application.onCreate 最早调用，供「跟随系统」判断系统 locale。 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun setLanguage(lang: Lang) {
        // Compose SnapshotState 的写入是线程安全的；store 的 Flow 可能在
        // 后台线程发射，重组仍会在主线程发生。
        languageState.value = lang
    }

    fun currentLanguage(): Lang = languageState.value

    /**
     * 是否显示中文。注意这里读取 languageState.value —— Compose 会把这次读取
     * 记入当前组合，语言一变所有 Text 就会重组。
     */
    fun isChinese(): Boolean = when (languageState.value) {
        Lang.ZH -> true
        Lang.EN -> false
        Lang.SYSTEM -> systemIsChinese()
    }

    private fun systemIsChinese(): Boolean {
        val ctx = appContext ?: return false
        val locales = ctx.resources.configuration.locales
        return !locales.isEmpty && locales[0].language == "zh"
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * 插值模板规则：源码里的 "$name is down" 求值后是 "Foo is down"，整句查表
     * 永远命中不了。这里把英文模板编译成正则，匹配成功就把捕获值填回中文模板。
     * 规则由 tools/gen_zh.py 生成，已过滤掉静态文字太短、容易误伤其它文案的条目。
     */
    private val compiledTemplates: List<Triple<Regex, List<String>, String>> by lazy {
        ZH_TEMPLATES.map { Triple(Regex(it.pattern), it.slots, it.zh) }
    }

    // 结果缓存：Compose 重组频繁，避免每次都跑几百条正则。空串表示「未命中」。
    private val templateCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun translate(en: String): String {
        if (en.isEmpty()) return en
        val direct = ZH[en]
        if (direct != null) return direct
        // 源码里有不少 "前半句 " + "后半句" 的跨行拼接，折叠空白后再匹配一次。
        val normalized = WHITESPACE.replace(en.trim(), " ")
        val normalizedHit = ZH_NORMALIZED[normalized]
        if (normalizedHit != null) return normalizedHit
        val cached = templateCache[en]
        if (cached != null) return cached.ifEmpty { en }
        val result = matchTemplate(en)
        templateCache[en] = result
        return result.ifEmpty { en }
    }

    private fun matchTemplate(en: String): String {
        for ((regex, slots, zh) in compiledTemplates) {
            val match = regex.matchEntire(en) ?: continue
            var out = zh
            for (i in slots.indices) {
                val value = match.groupValues.getOrNull(i + 1) ?: return ""
                out = out.replace(slots[i], value)
            }
            return out
        }
        return ""
    }

    /** 整句查表：用于界面上不带插值的英文原文。 */
    fun tr(en: String): String = if (isChinese()) translate(en) else en

    /**
     * 成对提供译文：用于带 ${...} 插值的通知模板，整句查表匹配不上。
     * 两个参数都是已求值字符串，插值照常保留，只选其中一句返回。
     */
    fun pick(en: String, zh: String): String = if (isChinese()) zh else en

    /**
     * 取 strings.xml 资源。values-zh-rCN 是按**系统** locale 选择的，跟应用内
     * 语言无关：中文系统里强制选英文时 getString() 仍会返回中文。所以强制英文
     * 时用一个 locale 固定为 en 的 context 取原文，中文时直接返回译文常量。
     */
    fun res(context: Context, id: Int, zh: String): String =
        if (isChinese()) zh else englishContext(context).getString(id)

    private fun englishContext(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.ENGLISH)
        return context.createConfigurationContext(config)
    }
}

internal val ZH_NORMALIZED: Map<String, String> by lazy {
    val ws = Regex("\\s+")
    ZH.entries.associate { (k, v) -> ws.replace(k.trim(), " ") to v }
}

/**
 * 与 Material3 Text(String) 参数完全一致的替换品，唯一区别是渲染前查一次翻译表。
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    autoSize: TextAutoSize? = null,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = L10n.tr(text),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        autoSize = autoSize,
        onTextLayout = onTextLayout,
        style = style,
    )
}

/**
 * AnnotatedString 重载：富文本里混着链接/样式，不做整句替换，原样交给 Material3。
 *
 * 注意：Material3 的 Text(AnnotatedString) 与 Text(String) 签名并不完全一致——
 * AnnotatedString 版本的 onTextLayout 是**非空**的 `(TextLayoutResult) -> Unit`，
 * 默认值 `{}`；String 版本则是可空的 `((TextLayoutResult) -> Unit)?`，默认 null。
 * 这里必须与 AnnotatedString 版本保持一致，否则编译期报 argument type mismatch。
 */
@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    autoSize: TextAutoSize? = null,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        autoSize = autoSize,
        onTextLayout = onTextLayout,
        style = style,
    )
}
