package me.rerere.rikkihub.data.workspace

/**
 * Extensions the workspace opens in the in-app text editor (App and Web keep the same list:
 * `web-settings/web-ui/lib/workspace.ts`). 推敲 may only be enabled for these.
 */
val WORKSPACE_TEXT_EXTENSIONS: Set<String> = setOf(
    "txt", "text", "md", "markdown", "rst", "org", "adoc", "asciidoc", "tex", "fountain",
    "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
    "properties", "env", "csv", "tsv", "log", "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
    "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
    "dockerfile", "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql", "proto",
    "diff", "patch", "srt", "vtt",
)

/** Lower-case extension after the last dot; empty when there is none. */
fun workspaceFileExtension(fileName: String): String =
    fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase()
