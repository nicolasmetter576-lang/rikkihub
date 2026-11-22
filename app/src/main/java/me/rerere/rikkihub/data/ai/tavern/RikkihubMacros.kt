package me.rerere.rikkihub.data.ai.tavern

import android.content.Context
import me.rerere.ai.provider.Model
import me.rerere.rikkihub.data.ai.transformers.DefaultPlaceholderProvider
import me.rerere.rikkihub.data.ai.transformers.PlaceholderCtx
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant

/**
 * rikkihub 自有的宏：`cur_date`、`model_id`、`model_name`、`locale`、`timezone`、`system_version`、
 * `device_info`、`battery_level`、`nickname`。并入酒馆宏引擎同一条解析路径；`char`、`user` 是酒馆内置宏，
 * 名字取助手名和用户昵称（和旧占位符一致：空就是 `assistant` / `user`）。
 * 解析函数是惰性的，只有提示词里真的写了这个宏才会去读电量等系统状态。
 */
object RikkihubMacros {
    private val handledByTavern = setOf("char", "user")

    fun resolvers(context: Context, settings: Settings, model: Model, assistant: Assistant): Map<String, () -> String> {
        val ctx = PlaceholderCtx(context, settings.displaySetting.userNickname, model, assistant)
        return DefaultPlaceholderProvider.placeholders
            .filterKeys { it !in handledByTavern }
            .mapValues { (_, info) -> { info.resolver(ctx) } }
    }

    fun userName(settings: Settings): String = settings.displaySetting.userNickname.ifBlank { "user" }

    fun charName(assistant: Assistant): String = assistant.name.ifBlank { "assistant" }

    /** 有角色时 `{{char}}` 是角色的名字，否则是助手的名字。 */
    fun charName(assistant: Assistant, character: ActiveCharacter?): String = character?.name ?: charName(assistant)
}
