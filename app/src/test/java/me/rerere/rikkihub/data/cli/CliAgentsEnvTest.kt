package me.rerere.rikkihub.data.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliAgentsEnvTest {

    @Test
    fun `isAllowedEnvName rejects all reserved env names including injection hazards`() {
        val reserved = listOf(
            "HOME",
            "PATH",
            "LD_PRELOAD",
            "LD_LIBRARY_PATH",
            "TERM",
            "BASH_ENV",
            "ENV",
            "NODE_OPTIONS",
            "PYTHONPATH",
            "PYTHONSTARTUP",
            "PERL5OPT",
            "RUBYOPT",
            "ZDOTDIR",
            "CDPATH",
            "IFS",
        )
        for (name in reserved) {
            assertFalse("Expected reserved env name $name to be rejected", CliAgent.isAllowedEnvName(name))
        }
    }

    @Test
    fun `isAllowedEnvName rejects PROOT_ prefix and invalid identifiers`() {
        assertFalse(CliAgent.isAllowedEnvName("PROOT_TMP_DIR"))
        assertFalse(CliAgent.isAllowedEnvName("PROOT_NO_SECCOMP"))
        assertFalse(CliAgent.isAllowedEnvName(""))
        assertFalse(CliAgent.isAllowedEnvName("1INVALID"))
        assertFalse(CliAgent.isAllowedEnvName("BAD-NAME"))
    }

    @Test
    fun `isAllowedEnvName allows valid custom env names and is case-sensitive`() {
        assertTrue(CliAgent.isAllowedEnvName("MY_API_KEY"))
        assertTrue(CliAgent.isAllowedEnvName("ANTHROPIC_BASE_URL"))
        assertTrue(CliAgent.isAllowedEnvName("_CUSTOM_FLAG"))
        assertTrue(CliAgent.isAllowedEnvName("bash_env"))
    }
}
