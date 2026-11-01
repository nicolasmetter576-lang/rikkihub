package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.MemoryWriteResult
import java.time.Instant

/** 记忆库工具的稳定 id（也是默认名称，可在工具设置里改名） */
object MemoryToolNames {
    const val READ = BuiltinToolIds.MEMORY_READ
    const val WRITE = BuiltinToolIds.MEMORY_WRITE
    const val STR_REPLACE = BuiltinToolIds.MEMORY_STR_REPLACE
    const val APPEND = BuiltinToolIds.MEMORY_APPEND
    const val DELETE = BuiltinToolIds.MEMORY_DELETE
    const val LIST = BuiltinToolIds.MEMORY_LIST

    val ALL = listOf(READ, WRITE, STR_REPLACE, APPEND, DELETE, LIST)

    /** 会改动记忆库的工具 */
    val WRITES = setOf(WRITE, STR_REPLACE, APPEND, DELETE)
}

private const val MAX_READ_PATHS = 20

private const val IF_VERSION_DESCRIPTION =
    "The version returned by the most recent read or write of this file"

private fun description(id: String) = BuiltinToolCatalog.require(id).defaultDescription

/**
 * 记忆库的读写工具，[memoryId] 决定操作哪个记忆库（助手自己的或全局的）。
 *
 * 何时读、何时写由 system prompt 里的记忆片段约束，这里只描述每个工具本身。
 */
fun buildMemoryTools(
    repository: MemoryRepository,
    memoryId: String,
): List<Tool> = listOf(
    Tool(
        name = MemoryToolNames.READ,
        description = description(MemoryToolNames.READ),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("maxItems", MAX_READ_PATHS)
                        put("description", "Paths of the files to read, e.g. [\"/topics/food.md\"]")
                    })
                },
                required = listOf("path")
            )
        },
        execute = { args ->
            // 有些模型只读一个文件时会直接传字符串
            val paths = when (val path = args.jsonObject["path"]) {
                is JsonArray -> path.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(path.contentOrNull)
                else -> emptyList()
            }.distinct().take(MAX_READ_PATHS)
            if (paths.isEmpty()) error("path is required")

            val files = paths.associateWith { repository.getFile(memoryId, it) }
            toolOutput {
                put("files", buildJsonArray {
                    files.values.filterNotNull().distinctBy { it.path }.forEach { file ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("version", file.version)
                            put("content", file.content)
                        })
                    }
                })
                val notFound = files.filterValues { it == null }.keys
                if (notFound.isNotEmpty()) {
                    put("not_found", buildJsonArray { notFound.forEach { add(it) } })
                }
            }
        }
    ),
    Tool(
        name = MemoryToolNames.WRITE,
        description = description(MemoryToolNames.WRITE),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file, e.g. /topics/food.md"))
                    put("content", stringSchema("The complete file content"))
                    put("if_version", stringSchema("\"new\" for a new file, otherwise the file's current version"))
                },
                required = listOf("path", "content", "if_version")
            )
        },
        execute = { args ->
            val path = args.string("path")
            val content = args.string("content")
            guarded(content) {
                repository.writeFile(memoryId, path, content, args.string("if_version"))
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.STR_REPLACE,
        description = description(MemoryToolNames.STR_REPLACE),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("old_str", stringSchema("Text to replace; must match exactly one place"))
                    put("new_str", stringSchema("Replacement text; empty to delete"))
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "old_str", "new_str", "if_version")
            )
        },
        execute = { args ->
            val path = args.string("path")
            val newStr = args.string("new_str")
            guarded(newStr) {
                repository.replaceInFile(memoryId, path, args.string("old_str"), newStr, args.string("if_version"))
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.APPEND,
        description = description(MemoryToolNames.APPEND),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("content", stringSchema("The line to append"))
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "content", "if_version")
            )
        },
        execute = { args ->
            val path = args.string("path")
            val content = args.string("content")
            if (content.isBlank()) error("content must not be blank")
            guarded(content) {
                repository.appendToFile(memoryId, path, content, args.string("if_version"))
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.DELETE,
        description = description(MemoryToolNames.DELETE),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "if_version")
            )
        },
        execute = { args ->
            val path = args.string("path")
            repository.deleteFile(memoryId, path, args.string("if_version")).toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.LIST,
        description = description(MemoryToolNames.LIST),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path_prefix", stringSchema("Only list files whose path starts with this, e.g. /people/"))
                }
            )
        },
        execute = { args ->
            val prefix = (args.jsonObject["path_prefix"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val files = repository.getFiles(memoryId).filter { it.path.startsWith(prefix) }
            toolOutput {
                put("files", buildJsonArray {
                    files.forEach { file ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("size", file.content.toByteArray().size)
                            put("updated_at", Instant.ofEpochMilli(file.updatedAt).toString())
                        })
                    }
                })
            }
        }
    ),
)

/**
 * 这次工具调用成功改动的记忆文件路径；不是写工具、还没执行或写入被拒绝时返回 null。
 * 主对话里的工具可能被改过名，用 [overrides] 认回稳定 id。
 */
fun UIMessagePart.Tool.memoryWritePath(overrides: Map<String, ToolOverride> = emptyMap()): String? {
    if (resolveBuiltinToolId(toolName, overrides) !in MemoryToolNames.WRITES || !isExecuted) return null
    val result = runCatching {
        Json.parseToJsonElement(output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
    }.getOrNull() as? JsonObject ?: return null
    if ("error" in result) return null
    return (result["path"] as? JsonPrimitive)?.contentOrNull
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun JsonElement.string(key: String): String =
    (jsonObject[key] as? JsonPrimitive)?.contentOrNull ?: error("$key is required")

private fun toolOutput(builder: JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))

private inline fun guarded(content: String, write: () -> MemoryWriteResult): MemoryWriteResult {
    val forbidden = findForbiddenMemoryContent(content) ?: return write()
    return MemoryWriteResult.Rejected(
        "Refused: the content looks like it contains $forbidden, which is never stored in memory. " +
            "Leave it out, and tell the user in one sentence that it cannot be saved."
    )
}

// 冲突时带上最新内容和版本，模型可以在同一轮里合并后重试
private fun MemoryWriteResult.toToolOutput(path: String): List<UIMessagePart> = toolOutput {
    when (val result = this@toToolOutput) {
        is MemoryWriteResult.Success -> {
            val file = result.file
            put("path", file?.path ?: MemoryFile.normalizePath(path) ?: path)
            if (file != null) put("version", file.version) else put("deleted", true)
        }

        is MemoryWriteResult.Conflict -> putConflict(path, result.current)
        is MemoryWriteResult.Rejected -> put("error", result.reason)
    }
}

private fun JsonObjectBuilder.putConflict(path: String, current: MemoryFile?) {
    if (current == null) {
        put(
            "error",
            "$path does not exist. Create it with ${MemoryToolNames.WRITE} and " +
                "if_version=\"${MemoryRepository.NEW_FILE_VERSION}\"."
        )
        return
    }
    put(
        "error",
        "Version conflict: $path already exists or has changed since you read it. " +
            "Merge your change into current_content and retry with current_version."
    )
    put("current_version", current.version)
    put("current_content", current.content)
}
