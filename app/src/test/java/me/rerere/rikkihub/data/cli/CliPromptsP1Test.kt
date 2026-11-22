package me.rerere.rikkihub.data.cli

import me.rerere.rikkihub.data.ai.tools.QUBAGU_RULES_SKILL
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.files.SkillMetadata
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.workspace.RootfsEnvironment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliPromptsP1Test {
    private val baseAssistant = Assistant(
        name = "Rikki",
        promptPreset = AssistantPresets.withSystemPrompt("System base"),
        enabledSkills = setOf("pdf-tools", "git-helper", QUBAGU_RULES_SKILL),
    )

    private val baseConversation = Conversation(
        assistantId = baseAssistant.id,
        messageNodes = emptyList(),
    )

    private val baseSettings = Settings()

    @Test
    fun `device directories, upload guidance, and AGENTS hint are shown when filesScope is null and hidden when confined`() {
        val targets = setOf("/device/photos", "/upload", "/skills")
        val env = RootfsEnvironment(shell = "/bin/sh", distro = "Alpine Linux")

        val unconfined = CliPrompts.systemPrompt(
            assistant = baseAssistant,
            conversation = baseConversation,
            settings = baseSettings,
            exposedTargets = targets,
            filesScope = null,
            rootfsEnvironment = env,
        )
        assertTrue(unconfined, unconfined.contains("/device/photos"))
        assertTrue(unconfined, unconfined.contains("/upload"))
        assertTrue(unconfined, unconfined.contains("READ-ONLY"))
        assertTrue(unconfined, unconfined.contains("/root/.agents/AGENTS.md"))
        assertTrue(unconfined, unconfined.contains("Alpine Linux"))
        assertTrue(unconfined, unconfined.contains("/bin/sh"))

        val confined = CliPrompts.systemPrompt(
            assistant = baseAssistant,
            conversation = baseConversation,
            settings = baseSettings,
            exposedTargets = targets,
            filesScope = "proj",
            rootfsEnvironment = env,
        )
        assertFalse(confined, confined.contains("/device/"))
        assertFalse(confined, confined.contains("/upload"))
        assertFalse(confined, confined.contains("/root/.agents/AGENTS.md"))
        assertFalse(confined, confined.contains("<available_skills>"))
        assertTrue(confined, confined.contains("Alpine Linux"))
    }

    @Test
    fun `available_skills is gated on skills target, deduplicates user over builtin, excludes qubagu-rules, and never mentions use_skill`() {
        val skills = listOf(
            SkillMetadata(
                name = "pdf-tools",
                description = "Builtin PDF helper",
                skillDir = File("/tmp/builtin/pdf-tools"),
                isBuiltin = true,
            ),
            SkillMetadata(
                name = "pdf-tools",
                description = "User override PDF helper",
                skillDir = File("/tmp/user/pdf-tools"),
                isBuiltin = false,
            ),
            SkillMetadata(
                name = "git-helper",
                description = "Builtin git helper",
                skillDir = File("/tmp/builtin/git-helper"),
                isBuiltin = true,
            ),
            SkillMetadata(
                name = QUBAGU_RULES_SKILL,
                description = "Should be excluded from CLI prompt",
                skillDir = File("/tmp/builtin/qubagu-rules"),
                isBuiltin = true,
            ),
        )

        val withoutSkillsTarget = CliPrompts.systemPrompt(
            assistant = baseAssistant,
            conversation = baseConversation,
            settings = baseSettings,
            exposedTargets = setOf("/upload"),
            filesScope = null,
            allSkills = skills,
        )
        assertFalse(withoutSkillsTarget, withoutSkillsTarget.contains("<available_skills>"))

        val withSkillsTarget = CliPrompts.systemPrompt(
            assistant = baseAssistant,
            conversation = baseConversation,
            settings = baseSettings,
            exposedTargets = setOf("/skills", "/builtin_skills"),
            filesScope = null,
            allSkills = skills,
        )
        assertTrue(withSkillsTarget, withSkillsTarget.contains("<available_skills>"))
        assertTrue(withSkillsTarget, withSkillsTarget.contains("User override PDF helper"))
        assertFalse(withSkillsTarget, withSkillsTarget.contains("Builtin PDF helper"))
        assertTrue(withSkillsTarget, withSkillsTarget.contains("/skills/pdf-tools/SKILL.md"))
        assertTrue(withSkillsTarget, withSkillsTarget.contains("/builtin_skills/git-helper/SKILL.md"))
        assertTrue(withSkillsTarget, withSkillsTarget.contains("SKILL.md"))
        assertFalse(withSkillsTarget, withSkillsTarget.contains(QUBAGU_RULES_SKILL))
        assertFalse(withSkillsTarget, withSkillsTarget.contains("use_skill"))
    }

    @Test
    fun `static identity macros are substituted while dynamic macros remain untouched`() {
        val assistant = Assistant(
            name = "Momo",
            promptPreset = AssistantPresets.withSystemPrompt(
                "Hi {{user}} (<USER>, {{nickname}}), I am {{char}} (<BOT>). Time={{time}} Date={{date}} Rand={{random}}"
            ),
        )
        val conversation = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
        )

        val rendered = CliPrompts.systemPrompt(
            assistant = assistant,
            conversation = conversation,
            settings = baseSettings,
            userName = "Alice",
        )
        assertTrue(rendered, rendered.contains("Hi Alice (Alice, Alice), I am Momo (Momo)."))
        assertTrue(rendered, rendered.contains("Time={{time}} Date={{date}} Rand={{random}}"))
    }
}
