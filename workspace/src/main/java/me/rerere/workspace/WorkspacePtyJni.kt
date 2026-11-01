package me.rerere.workspace

internal object WorkspacePtyJni {
    init {
        System.loadLibrary("workspace_pty")
    }

    @JvmStatic
    external fun createSubprocess(
        cmd: String,
        cwd: String,
        args: Array<String>,
        envVars: Array<String>,
        processId: IntArray,
        rows: Int,
        columns: Int,
    ): Int

    @JvmStatic
    external fun setPtyWindowSize(fd: Int, rows: Int, columns: Int)

    @JvmStatic
    external fun waitFor(pid: Int): Int
}
