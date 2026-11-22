package me.rerere.tavern.macro

/**
 * 旧占位符迁移：把 rikkihub 自有的 11 个键从 `{key}` 改写成 `{{key}}`。
 * 来源：app `PlaceholderTransformer` 的 `DefaultPlaceholderProvider` 键集合。
 *
 * 规则：
 * - 只改这 11 个键的单花括号写法，大小写不敏感，统一写成小写 `{{key}}`
 *   （旧代码 `ignoreCase = true`；新引擎的宏名也不区分大小写——小写是规范形）
 * - 已经是 `{{key}}`（含大小写变体）的不动——双花括号本就直接匹配注册表
 * - 其他花括号表达式（`{foo}`、`{key`、JSON 等）一律不动
 */
object LegacyPlaceholderMigration {
    /** PlaceholderTransformer.kt 实有的 11 个键（char/user 也在内，因为它们也可能是 `{char}`/`{user}` 写的）。 */
    val OWNED_KEYS = listOf(
        "cur_date", "model_id", "model_name", "locale", "timezone",
        "system_version", "device_info", "battery_level", "nickname", "char", "user",
    )

    private val KEY_PATTERN = Regex(
        "(?<!\\{)\\{(${OWNED_KEYS.joinToString("|")})\\}",
        RegexOption.IGNORE_CASE,
    )

    /** 返回迁移后的文本；没有可迁移内容时原样返回。 */
    fun migrate(text: String): String =
        KEY_PATTERN.replace(text) { m -> "{{${m.groupValues[1].lowercase()}}}" }

    /** 文本里是否含需要迁移的 `{key}`。 */
    fun needsMigration(text: String): Boolean = KEY_PATTERN.containsMatchIn(text)
}
