package me.rerere.rikkihub.data.workspace

import me.rerere.rikkihub.data.cli.CliAgent

/** The four groups of the setup sheet; [BASE] and [RUNTIME] are selected by default. */
enum class WorkspaceEnvGroup { BASE, RUNTIME, OPTIONAL, AGENT }

/**
 * One thing the one-click setup can put into an apt-based rootfs (Ubuntu Base ships 91 packages:
 * no curl, certificates, git, python or node). [check] is a shell condition that holds once the
 * item is usable; [sizeMb] is the measured or estimated installed size shown to the user.
 */
enum class WorkspaceEnvItem(
    val group: WorkspaceEnvGroup,
    val aptPackages: List<String>,
    val check: String,
    /** Measured on the x86_64 emulator (2026-11-13); DROID is an estimate. */
    val sizeMb: Int,
) {
    BASE(
        WorkspaceEnvGroup.BASE,
        listOf(
            "ca-certificates", "curl", "git", "ripgrep", "unzip", "zip", "xz-utils", "bzip2",
            "file", "jq", "less", "nano", "patch", "tree", "tzdata",
        ),
        commandsExist("curl", "git", "rg", "unzip", "zip", "xz", "bzip2", "file", "jq", "less", "nano", "patch", "tree"),
        110,
    ),
    PYTHON(
        WorkspaceEnvGroup.RUNTIME,
        listOf("python3", "python3-pip", "python3-venv"),
        "command -v python3 >/dev/null && python3 -m pip --version >/dev/null 2>&1",
        65,
    ),
    NODE(WorkspaceEnvGroup.RUNTIME, emptyList(), commandsExist("node", "npm"), 155),
    DOCS(
        WorkspaceEnvGroup.OPTIONAL,
        listOf("pandoc", "poppler-utils"),
        commandsExist("pandoc", "pdftotext") + " && python3 -c 'import docx, openpyxl, pypdf' 2>/dev/null",
        200,
    ),
    FONTS(
        WorkspaceEnvGroup.OPTIONAL,
        listOf("fonts-noto-cjk"),
        "[ -e /usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc ]",
        90,
    ),
    MEDIA(WorkspaceEnvGroup.OPTIONAL, listOf("ffmpeg", "imagemagick"), commandsExist("ffmpeg", "convert"), 430),
    BUILD(WorkspaceEnvGroup.OPTIONAL, listOf("build-essential"), commandsExist("gcc", "make"), 250),
    TOOLS(WorkspaceEnvGroup.OPTIONAL, listOf("sqlite3", "openssh-client"), commandsExist("sqlite3", "ssh"), 5),
    AGY(WorkspaceEnvGroup.AGENT, emptyList(), commandsExist("agy"), 255),
    DROID(WorkspaceEnvGroup.AGENT, emptyList(), commandsExist("droid"), 100);

    val id: String get() = name.lowercase()

    /** Items this one cannot be installed without. */
    val requires: Set<WorkspaceEnvItem>
        get() = when (this) {
            NODE, AGY, DROID -> setOf(BASE) // curl + xz
            DOCS -> setOf(PYTHON) // pip for the python libraries
            else -> emptySet()
        }

    companion object {
        val DEFAULT: Set<WorkspaceEnvItem> = setOf(BASE, PYTHON, NODE)

        fun fromId(id: String): WorkspaceEnvItem? = entries.firstOrNull { it.id == id }
    }
}

/** Where apt, pip, npm and the Node tarball come from. */
enum class WorkspaceEnvMirror(
    val ubuntu: String,
    val ubuntuPorts: String,
    val pypi: String?,
    val npmRegistry: String?,
    val node: String,
) {
    OFFICIAL(
        ubuntu = "http://archive.ubuntu.com",
        ubuntuPorts = "http://ports.ubuntu.com",
        pypi = null,
        npmRegistry = null,
        node = "https://nodejs.org/dist",
    ),

    // apt stays on plain http: the first `apt-get update` runs before ca-certificates is installed.
    CHINA(
        ubuntu = "http://mirrors.aliyun.com",
        ubuntuPorts = "http://mirrors.aliyun.com",
        pypi = "https://mirrors.aliyun.com/pypi/simple/",
        npmRegistry = "https://registry.npmmirror.com/",
        node = "https://npmmirror.com/mirrors/node",
    ),
}

/** What a READY workspace already has, from [WorkspaceEnvSetup.checkScript]. */
data class WorkspaceEnvStatus(
    val installed: Set<WorkspaceEnvItem>,
    val mirror: WorkspaceEnvMirror,
    /** False for rootfs without apt (e.g. Alpine): the setup cannot run there. */
    val aptAvailable: Boolean,
)

private fun commandsExist(vararg names: String): String =
    names.joinToString(" && ") { "command -v $it >/dev/null" }

/**
 * Scripts for the one-click environment setup. They print `::step <id>` before each stage and
 * `::have <id>` / `::mirror <name>` / `::no-apt` when checking, which the service parses.
 */
object WorkspaceEnvSetup {
    const val STEP_MARKER = "::step "
    private const val HAVE_MARKER = "::have "
    private const val MIRROR_MARKER = "::mirror "
    private const val NO_APT_MARKER = "::no-apt"
    private const val NODE_MAJOR = 24
    private val TIME_ZONE_ID = Regex("[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+)*")

    /** Selecting [item] also selects what it needs; deselecting it also drops what needs it. */
    fun toggle(selection: Set<WorkspaceEnvItem>, item: WorkspaceEnvItem, selected: Boolean): Set<WorkspaceEnvItem> =
        if (selected) withRequirements(selection + item)
        else selection.filterNot { it == item || item in requirementsOf(it) }.toSet()

    fun withRequirements(items: Set<WorkspaceEnvItem>): Set<WorkspaceEnvItem> =
        items + items.flatMap(::requirementsOf)

    private fun requirementsOf(item: WorkspaceEnvItem): Set<WorkspaceEnvItem> =
        item.requires + item.requires.flatMap(::requirementsOf)

    /** The id after [STEP_MARKER], or null for ordinary output. */
    fun stepOf(line: String): String? = line.trim().takeIf { it.startsWith(STEP_MARKER) }?.removePrefix(STEP_MARKER)

    /**
     * Installs [items] (plus their requirements) and applies the environment settings that come with
     * every run: sources for [mirror], apt without translations, system-wide pip, [timeZone] (an IANA
     * id; anything else is ignored), a default git identity, and apt caches/indexes removed afterwards.
     */
    fun setupScript(items: Set<WorkspaceEnvItem>, mirror: WorkspaceEnvMirror, timeZone: String?): String {
        val selected = withRequirements(items)
        val packages = WorkspaceEnvItem.entries.filter { it in selected }.flatMap { it.aptPackages }
        val zone = timeZone?.takeIf { it.matches(TIME_ZONE_ID) && ".." !in it }
        return buildString {
            appendLine("set -eo pipefail")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("step() { echo \"$STEP_MARKER\$1\"; }")
            appendLine("command -v apt-get >/dev/null 2>&1 || { echo 'apt-get not found: only Ubuntu/Debian rootfs are supported' >&2; exit 3; }")

            appendLine("step prepare")
            appendLine("dpkg --configure -a || true")
            appendLine("printf 'Acquire::Languages \"none\";\\n' > /etc/apt/apt.conf.d/99rikkihub")
            appendLine("for f in /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list; do")
            appendLine("  [ -f \"\$f\" ] || continue")
            appendLine("  sed -E -i '/^(URIs:|deb)/ s#https?://[^ ]+/ubuntu-ports/?( |\$)#${mirror.ubuntuPorts}/ubuntu-ports/\\1#g; /^(URIs:|deb)/ s#https?://[^ ]+/ubuntu/?( |\$)#${mirror.ubuntu}/ubuntu/\\1#g' \"\$f\"")
            appendLine("done")
            appendLine("{ echo '[global]'; echo 'break-system-packages = true'; echo 'root-user-action = ignore'${mirror.pypi?.let { "; echo 'index-url = $it'" }.orEmpty()}; } > /etc/pip.conf")
            if (mirror.npmRegistry != null) {
                appendLine("mkdir -p /usr/local/etc && echo 'registry=${mirror.npmRegistry}' > /usr/local/etc/npmrc")
            } else {
                appendLine("rm -f /usr/local/etc/npmrc")
            }

            if (packages.isNotEmpty()) {
                appendLine("step update")
                appendLine("apt-get update")
                appendLine("step packages")
                appendLine("apt-get install -y --no-install-recommends ${packages.joinToString(" ")}")
            }
            if (zone != null) {
                appendLine("if [ -f /usr/share/zoneinfo/$zone ]; then ln -sf /usr/share/zoneinfo/$zone /etc/localtime; echo $zone > /etc/timezone; fi")
            }
            if (WorkspaceEnvItem.DOCS in selected) {
                appendLine("step ${WorkspaceEnvItem.DOCS.id}")
                appendLine("python3 -m pip install --no-cache-dir --root-user-action=ignore python-docx openpyxl pypdf")
            }
            if (WorkspaceEnvItem.NODE in selected) {
                appendLine("step ${WorkspaceEnvItem.NODE.id}")
                appendLine("case \"\$(uname -m)\" in aarch64) arch=arm64 ;; x86_64) arch=x64 ;; *) echo \"unsupported CPU: \$(uname -m)\" >&2; exit 1 ;; esac")
                appendLine("base=${mirror.node}/latest-v$NODE_MAJOR.x")
                appendLine("line=\$(curl -fsSL --retry 3 \"\$base/SHASUMS256.txt\" | grep -E \" node-v[0-9.]+-linux-\$arch\\.tar\\.xz\$\")")
                appendLine("file=\${line##* }")
                appendLine("curl -fL --retry 3 -o /tmp/node.tar.xz \"\$base/\$file\"")
                appendLine("echo \"\${line%% *}  /tmp/node.tar.xz\" | sha256sum -c -")
                // include/ (C headers, ~78 MB) is only for native addons; node-gyp downloads it when needed
                appendLine("tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --exclude='*/include' --exclude='*/share/doc' --exclude='*/CHANGELOG.md' --exclude='*/README.md' --exclude='*/LICENSE'")
                appendLine("rm -f /tmp/node.tar.xz")
            }
            listOf(WorkspaceEnvItem.AGY to CliAgent.AGY, WorkspaceEnvItem.DROID to CliAgent.DROID).forEach { (item, agentId) ->
                if (item !in selected) return@forEach
                val agent = CliAgent.BUILT_IN.first { it.id == agentId }
                appendLine("step ${item.id}")
                appendLine("( ${agent.installCommand} )")
            }

            appendLine("step finish")
            appendLine("if command -v git >/dev/null; then")
            appendLine("  git config --global user.name >/dev/null || git config --global user.name rikkihub")
            appendLine("  git config --global user.email >/dev/null || git config --global user.email rikkihub@localhost")
            appendLine("fi")
            appendLine("apt-get clean")
            appendLine("rm -rf /var/lib/apt/lists/*")
        }
    }

    /** Prints which items are usable, the current mirror and whether apt exists; never fails. */
    fun checkScript(): String = buildString {
        appendLine("command -v apt-get >/dev/null 2>&1 || echo '$NO_APT_MARKER'")
        WorkspaceEnvItem.entries.forEach { item ->
            appendLine("if ${item.check}; then echo '$HAVE_MARKER${item.id}'; fi")
        }
        val chinaHost = WorkspaceEnvMirror.CHINA.ubuntu.substringAfter("://")
        appendLine("if grep -qs '$chinaHost' /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list; then echo '$MIRROR_MARKER${WorkspaceEnvMirror.CHINA.name}'; fi")
        appendLine("true")
    }

    fun parseCheck(output: String): WorkspaceEnvStatus {
        val lines = output.lineSequence().map { it.trim() }.toList()
        return WorkspaceEnvStatus(
            installed = lines.mapNotNull { line ->
                line.takeIf { it.startsWith(HAVE_MARKER) }?.removePrefix(HAVE_MARKER)?.let(WorkspaceEnvItem::fromId)
            }.toSet(),
            mirror = lines.firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith(MIRROR_MARKER) }?.removePrefix(MIRROR_MARKER)
                    ?.let { name -> WorkspaceEnvMirror.entries.firstOrNull { it.name == name } }
            } ?: WorkspaceEnvMirror.OFFICIAL,
            aptAvailable = NO_APT_MARKER !in lines,
        )
    }
}
