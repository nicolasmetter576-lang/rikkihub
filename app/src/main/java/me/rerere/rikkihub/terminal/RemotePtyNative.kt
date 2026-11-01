package me.rerere.rikkihub.terminal

/**
 * Bridge into the app's attach-only libtermux.so: a Termux TerminalSession created
 * with command `rikki-remote:<token>` attaches to the PTY master registered here.
 */
object RemotePtyNative {
    init { System.loadLibrary("termux") }

    const val COMMAND_PREFIX = "rikki-remote:"

    /** Transfers ownership of [fd] to native code until a session claims it. */
    @JvmStatic external fun register(token: String, fd: Int)

    /** Unblocks the session's waitFor thread with the shell's exit status. */
    @JvmStatic external fun markExited(token: String, code: Int)

    /** Drops an unclaimed registration (closes its fd). */
    @JvmStatic external fun forget(token: String)
}
