package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/**
 * Lets programs in the rootfs open links in the Android browser (e.g. `agy` sign-in calls `xdg-open`).
 * The rootfs gets an `xdg-open` that drops the URL as a file into [GUEST_DIR], a bind of an app
 * directory; the app watches that directory and opens each URL.
 */
object WorkspaceUrlHandoff {
    const val GUEST_DIR = "/run/rikkihub/open-url"
    const val REQUEST_SUFFIX = ".url"
    const val MAX_REQUEST_BYTES = 16 * 1024

    private const val XDG_OPEN_PATH = "usr/local/bin/xdg-open"
    private const val MARKER = "# rikkihub-xdg-open"
    private val HTTP_URL = Regex("""^https?://[^/?#\s]+.*""", RegexOption.IGNORE_CASE)

    // Written atomically (temp file + mv) so the app never reads half a URL.
    internal val XDG_OPEN_SCRIPT = """
        |#!/bin/sh
        |$MARKER: written by RikkiHub on every workspace start.
        |# Opens http(s) links in the Android browser. Delete the line above to keep your own edits.
        |dir=$GUEST_DIR
        |case "${'$'}1" in
        |  http://*|https://*) ;;
        |  *) echo "xdg-open: RikkiHub can only open http(s) links: ${'$'}1" >&2; exit 1 ;;
        |esac
        |[ -d "${'$'}dir" ] || { echo "xdg-open: RikkiHub link handoff is not available" >&2; exit 3; }
        |name="${'$'}(date +%s)-${'$'}${'$'}"
        |printf '%s\n' "${'$'}1" > "${'$'}dir/.${'$'}name" && mv "${'$'}dir/.${'$'}name" "${'$'}dir/${'$'}name$REQUEST_SUFFIX" || exit 4
        |
    """.trimMargin()

    /**
     * Prepares a rootfs: creates the mount point (PRoot does not create a missing nested bind target such
     * as `/run/rikkihub/…`, the bind just stays invisible) and installs or refreshes our `xdg-open`.
     * An `xdg-open` file or link the user put there themselves is left alone.
     */
    fun install(linuxDir: File) {
        File(linuxDir, GUEST_DIR.trimStart('/')).mkdirs()
        val target = File(linuxDir, XDG_OPEN_PATH)
        if (Files.isSymbolicLink(target.toPath())) return
        if (target.exists()) {
            val current = target.takeIf { it.isFile }?.readText() ?: return
            if (MARKER !in current) return
            if (current == XDG_OPEN_SCRIPT && target.canExecute()) return
        }
        target.parentFile?.mkdirs()
        target.writeText(XDG_OPEN_SCRIPT)
        target.setReadable(true, false)
        target.setExecutable(true, false)
    }

    /** The URL a request file asks for, or null unless it is a single http(s) link. */
    fun parseRequest(text: String): String? {
        val url = text.trim()
        if (url.any { it.isWhitespace() || it.isISOControl() }) return null
        return url.takeIf { HTTP_URL.matches(it) }
    }
}
