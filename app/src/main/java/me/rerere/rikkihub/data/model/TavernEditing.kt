package me.rerere.rikkihub.data.model

import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.AssemblyOptions
import me.rerere.tavern.prompt.DEFAULT_INJECTION_DEPTH
import me.rerere.tavern.prompt.DEFAULT_INJECTION_ORDER
import me.rerere.tavern.prompt.InjectionPosition
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import kotlin.uuid.Uuid

/*
 * 编辑界面用的纯函数：条目的位置/触发、组装选项、正则脚本。界面（App）和网页端共用这套规则，
 * 所以放在模型层并单测，不写进 Composable。
 */

/** 条目的落位方式：相对（按布局顺序）或聊天内（按深度和顺序插进历史）。 */
enum class EntryPlacement { RELATIVE, IN_CHAT }

val PromptEntry.placement: EntryPlacement
    get() = if (injectionPosition == InjectionPosition.ABSOLUTE) EntryPlacement.IN_CHAT else EntryPlacement.RELATIVE

/** 聊天内条目的深度（离聊天末尾几条）；没写按酒馆默认 4。 */
val PromptEntry.depth: Int get() = injectionDepth ?: DEFAULT_INJECTION_DEPTH

/** 聊天内条目同深度内的顺序；没写按酒馆默认 100。 */
val PromptEntry.order: Int get() = injectionOrder ?: DEFAULT_INJECTION_ORDER

/**
 * 换落位方式。深度和顺序一直写进条目，从聊天内切回相对再切回来时值还在；
 * 深度不能为负。
 */
fun PromptEntry.withPlacement(
    placement: EntryPlacement,
    depth: Int = this.depth,
    order: Int = this.order,
): PromptEntry = copy(
    injectionPosition = if (placement == EntryPlacement.IN_CHAT) InjectionPosition.ABSOLUTE else InjectionPosition.RELATIVE,
    injectionDepth = depth.coerceAtLeast(0),
    injectionOrder = order,
)

/** 条目的触发类型；空集合 = 所有类型都发（酒馆里 `injection_trigger` 为空就是全部）。认不得的类型名由 [withTriggers] 原样保留。 */
val PromptEntry.triggers: Set<GenerationType>
    get() = injectionTrigger.orEmpty().mapNotNull { wire -> GenerationType.entries.firstOrNull { it.wire == wire } }.toSet()

/**
 * 改触发类型。没有勾选或勾满六种都存成空列表（全部）；认不得的类型名（别的酒馆版本写的）留在列表末尾不动，
 * 这样导入再导出不丢。
 */
fun PromptEntry.withTriggers(selected: Set<GenerationType>): PromptEntry {
    val known = GenerationType.entries.map { it.wire }.toSet()
    val unknown = injectionTrigger.orEmpty().filter { it !in known }
    val chosen = if (selected.size == GenerationType.entries.size) emptyList() else GenerationType.entries.filter { it in selected }.map { it.wire }
    val next = chosen + unknown
    return copy(injectionTrigger = next)
}

/** 新建的库条目：相对条目，放在系统提示词之后；标识符用 UUID（库条目被助手和会话按它选用）。 */
fun newLibraryEntry(name: String): PromptEntry = PromptEntry(
    identifier = Uuid.random().toString(),
    name = name,
    role = PromptRole.SYSTEM,
    content = "",
    systemPrompt = false,
    marker = false,
    injectionPosition = InjectionPosition.RELATIVE,
    injectionDepth = DEFAULT_INJECTION_DEPTH,
    injectionOrder = 0,
).withLibraryMeta(enabled = true, anchor = LibraryEntryMeta.AFTER_SYSTEM)

/** 新建的助手提示词条目（文本、系统角色、相对位置）。 */
fun newPromptTextEntry(name: String): PromptEntry = PromptEntry(
    identifier = Uuid.random().toString(),
    name = name,
    role = PromptRole.SYSTEM,
    content = "",
    systemPrompt = false,
    marker = false,
)

// ---------------------------------------------------------------------------------------------
// 组装选项

/** 上下文长度不小于它就当作不设限（新助手和迁移出来的助手是 100 万）。 */
val AssemblyOptions.contextUnlimited: Boolean get() = contextSize >= AssistantPresets.UNLIMITED_CONTEXT

/** 换上下文长度；传 null 表示不设限。长度至少 1。 */
fun AssemblyOptions.withContextSize(tokens: Int?): AssemblyOptions =
    copy(contextSize = tokens?.coerceAtLeast(1) ?: AssistantPresets.UNLIMITED_CONTEXT)

fun PromptPreset.withOptions(transform: (AssemblyOptions) -> AssemblyOptions): PromptPreset = copy(options = transform(options))

// ---------------------------------------------------------------------------------------------
// 正则脚本

/** 新建的正则脚本：作用在 AI 输出和推理上，没有查找串。 */
fun newRegexScript(name: String): RegexScript = RegexScript(
    id = Uuid.random().toString(),
    scriptName = name,
    findRegex = "",
    placement = listOf(RegexPlacement.AI_OUTPUT, RegexPlacement.REASONING),
)

/** 脚本是否作用在这个作用点（[RegexPlacement]）。 */
fun RegexScript.affects(point: Int): Boolean = point in placement

/** 开关一个作用点；顺序固定为数字升序，免得同一组设置存出不同的数组。 */
fun RegexScript.withPlacement(point: Int, on: Boolean): RegexScript {
    val next = if (on) (placement + point).distinct() else placement - point
    return copy(placement = next.sorted())
}

/**
 * 用户写的查找串：写成 `/模式/标志` 就原样用，裸模式补成 `/模式/g`（替换全部匹配）。
 * 空白串返回空串。
 */
fun normalizeFindRegex(input: String): String {
    val text = input.trim()
    if (text.isEmpty()) return text
    val closing = text.lastIndexOf('/')
    val looksLikeLiteral = text.startsWith("/") && closing > 0 && text.substring(closing + 1).all { it in "dgimsuvy" }
    return if (looksLikeLiteral) text else "/$text/g"
}

/** 把 `/模式/标志` 拆成（模式，标志）；不是这种写法返回 null。用于报错时单独编译拿到错误信息。 */
fun splitRegexLiteral(input: String): Pair<String, String>? {
    if (!input.startsWith("/")) return null
    val closing = input.lastIndexOf('/')
    if (closing <= 0) return null
    val flags = input.substring(closing + 1)
    if (!flags.all { it in "dgimsuvy" }) return null
    return input.substring(1, closing) to flags
}
