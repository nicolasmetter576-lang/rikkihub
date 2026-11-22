package me.rerere.rikkihub.ui.pages.chat.cli

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.CustomHeader
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.cli.AcpRuntimeInfo
import me.rerere.rikkihub.data.cli.CliAgent
import me.rerere.rikkihub.data.cli.CliEnvOverride
import me.rerere.rikkihub.data.cli.CliPermissionMode
import me.rerere.rikkihub.data.cli.CliProcessState
import me.rerere.rikkihub.data.cli.CustomCliAgentConfig
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import org.koin.compose.koinInject
import me.rerere.rikkihub.ui.components.ui.CompactListItem

/** What the top bar shows for a chat's workspace CLI. */
enum class CliBarState { OFF, DORMANT, RUNNING, AWAITING_APPROVAL }

/** Top bar entry of the workspace CLI: tinted when the chat uses a CLI, a dot while its agent runs or waits for approval. */
@Composable
fun CliTopBarButton(state: CliBarState, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("cli_top_bar_button")) {
        BadgedBox(
            badge = {
                when (state) {
                    CliBarState.OFF, CliBarState.DORMANT -> Unit
                    CliBarState.RUNNING -> Badge(containerColor = MaterialTheme.colorScheme.primary)
                    CliBarState.AWAITING_APPROVAL -> Badge()
                }
            },
        ) {
            Icon(
                imageVector = HugeIcons.CommandLine,
                contentDescription = stringResource(R.string.cli_title),
                tint = if (state == CliBarState.OFF) LocalContentColor.current else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Takes the model picker's place in a CLI chat: the model the agent reports while its process
 * runs, else the agent's name. Opens the CLI panel, where the mode and the rest are.
 */
@Composable
fun CliModelCapsule(agentName: String, runtime: AcpRuntimeInfo?, onClick: () -> Unit) {
    val label = runtime?.currentModelName ?: runtime?.currentModeName ?: agentName
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.testTag("cli_model_capsule"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(HugeIcons.CommandLine, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
    }
}

/** Which installed programs the workspace has, looked up in the login shell PATH plus ~/.local/bin. */
private suspend fun WorkspaceRepository.installedExecutables(workspaceId: String, executables: List<String>): Set<String> {
    if (executables.isEmpty()) return emptySet()
    val names = executables.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    val result = executeCommand(
        workspaceId,
        "export PATH=\"\$HOME/.local/bin:\$PATH\"; for e in $names; do command -v -- \"\$e\" >/dev/null 2>&1 && printf '%s\\n' \"\$e\"; done",
        timeoutMillis = 30_000,
    )
    return result.stdout.lines().filter { it.isNotBlank() }.toSet()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CliSessionSheet(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    workspaceId: String?,
    processState: CliProcessState?,
    onDismiss: () -> Unit,
    onSelectAgent: (String?) -> Unit,
    onExitCliToOrdinaryChat: (() -> Unit)? = null,
    onPermissionMode: (CliPermissionMode) -> Unit,
    onConfigOption: (String, String) -> Unit,
    onModel: (String) -> Unit,
    onMode: (String) -> Unit,
    onRestart: () -> Unit,
    onAssistantDefault: (Boolean) -> Unit,
    onOpenTerminal: (command: String?) -> Unit,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
    stderrTail: () -> String?,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()
    val agents = remember(settings.customCliAgents, settings.builtInCliEnvOverrides) { CliAgent.all(settings) }
    var installed by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(workspaceId, agents.map { it.executable }) {
        installed = if (workspaceId == null) {
            emptySet()
        } else {
            runCatching { workspaceRepository.installedExecutables(workspaceId, agents.map { it.executable }.distinct()) }
                .getOrDefault(emptySet())
        }
    }
    var showManage by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val cli = conversation.cliSession
    val hasCharacter = conversation.characterId != null
    val selected = cli?.let { current -> agents.firstOrNull { it.id == current.agentId } }
    val live = processState?.takeIf { it.conversationId == conversation.id }
    val runtime by (live?.runtime ?: remember { MutableStateFlow(AcpRuntimeInfo()) }).collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.cli_title), style = MaterialTheme.typography.titleLarge)
            if (workspaceId == null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cli_error_no_workspace),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        if (cli != null) {
                            Button(
                                onClick = {
                                    if (conversation.messageNodes.isNotEmpty() && onExitCliToOrdinaryChat != null) {
                                        onExitCliToOrdinaryChat()
                                    } else {
                                        onSelectAgent(null)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier.testTag("cli_exit_ordinary_chat"),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text(stringResource(R.string.cli_exit_ordinary_chat))
                            }
                        }
                    }
                }
            }
            if (hasCharacter) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.cli_character_incompatible),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (conversation.messageNodes.isNotEmpty()) {
                Text(
                    stringResource(R.string.cli_new_chat_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AgentRow(
                title = stringResource(R.string.cli_off),
                subtitle = null,
                selected = cli == null,
                onClick = { onSelectAgent(null) },
                tag = "cli_agent_off",
            )
            agents.forEach { agent ->
                val status = when {
                    installed == null -> stringResource(R.string.cli_status_checking)
                    agent.executable in installed.orEmpty() -> stringResource(R.string.cli_status_installed)
                    else -> stringResource(R.string.cli_status_missing)
                }
                val state = when {
                    live?.agentId != agent.id -> null
                    live.busy -> stringResource(R.string.cli_state_running)
                    else -> stringResource(R.string.cli_state_idle)
                }
                AgentRow(
                    title = agent.name,
                    subtitle = listOfNotNull("${agent.executable} · $status", state).joinToString(" · "),
                    selected = cli?.agentId == agent.id,
                    enabled = !hasCharacter && workspaceId != null,
                    onClick = { onSelectAgent(agent.id) },
                    tag = "cli_agent_${agent.executable}",
                )
            }

            if (cli != null && selected != null) {
                HorizontalDivider()
                Text(stringResource(R.string.cli_permission), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = cli.permissionMode == CliPermissionMode.ASK,
                        onClick = { onPermissionMode(CliPermissionMode.ASK) },
                        label = { Text(stringResource(R.string.cli_permission_ask)) },
                        modifier = Modifier.testTag("cli_permission_ask"),
                    )
                    FilterChip(
                        selected = cli.permissionMode == CliPermissionMode.AUTO,
                        onClick = { onPermissionMode(CliPermissionMode.AUTO) },
                        label = { Text(stringResource(R.string.cli_permission_auto)) },
                        modifier = Modifier.testTag("cli_permission_auto"),
                    )
                }

                HorizontalDivider()
                Text(stringResource(R.string.cli_session_settings), style = MaterialTheme.typography.titleSmall)
                val hasChoices = runtime.configOptions.isNotEmpty() || runtime.models.isNotEmpty() || runtime.modes.isNotEmpty()
                if (live == null || !hasChoices) {
                    Text(
                        stringResource(R.string.cli_session_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (runtime.configOptions.isNotEmpty()) {
                    runtime.configOptions.forEach { option ->
                        Text(option.name, style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            option.options.forEach { choice ->
                                FilterChip(
                                    selected = option.currentValue == choice.id,
                                    onClick = { onConfigOption(option.id, choice.id) },
                                    label = { Text(choice.name) },
                                )
                            }
                        }
                    }
                } else {
                    if (runtime.models.isNotEmpty()) {
                        Text(stringResource(R.string.cli_model), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            runtime.models.forEach { model ->
                                FilterChip(
                                    selected = runtime.currentModelId == model.id,
                                    onClick = { onModel(model.id) },
                                    label = { Text(model.name) },
                                )
                            }
                        }
                    }
                    if (runtime.modes.isNotEmpty()) {
                        Text(stringResource(R.string.cli_mode), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            runtime.modes.forEach { mode ->
                                FilterChip(
                                    selected = runtime.currentModeId == mode.id,
                                    onClick = { onMode(mode.id) },
                                    label = { Text(mode.name) },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                CompactListItem(
                    headlineContent = { Text(stringResource(R.string.cli_default_for_assistant)) },
                    trailingContent = {
                        Switch(
                            checked = assistant.defaultCliAgentId == cli.agentId,
                            onCheckedChange = onAssistantDefault,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                CompactListItem(
                    headlineContent = { Text(stringResource(R.string.cli_restart)) },
                    supportingContent = { Text(stringResource(R.string.cli_restart_desc)) },
                    modifier = Modifier.testTag("cli_restart"),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    trailingContent = { TextButton(onClick = onRestart) { Text(stringResource(R.string.confirm)) } },
                )
            }

            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (workspaceId != null) {
                    val installCommand = selected?.installCommand?.takeIf { selected.executable !in installed.orEmpty() }
                    TextButton(onClick = { onOpenTerminal(installCommand ?: selected?.loginCommand) }) {
                        Text(stringResource(R.string.cli_open_terminal))
                    }
                }
                TextButton(onClick = { showManage = true }, modifier = Modifier.testTag("cli_manage")) {
                    Text(stringResource(R.string.cli_manage))
                }
                if (live != null) {
                    TextButton(onClick = { showLog = !showLog }) { Text(stringResource(R.string.cli_stderr)) }
                }
            }
            if (showLog) {
                SelectionContainer {
                    Text(
                        text = stderrTail()?.takeLast(4_000)?.ifBlank { null } ?: stringResource(R.string.cli_stderr_empty),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }

    if (showManage) {
        CliAgentsSheet(settings = settings, onDismiss = { showManage = false }, onUpdateSettings = onUpdateSettings)
    }
}

@Composable
private fun AgentRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    CompactListItem(
        headlineContent = { Text(title, color = if (enabled) Color.Unspecified else muted) },
        supportingContent = subtitle?.let { { Text(it, color = if (enabled) Color.Unspecified else muted) } },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier = Modifier
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun envText(vars: List<CustomHeader>): String = vars.joinToString("\n") { "${it.name}=${it.value}" }

private fun parseEnv(text: String): List<CustomHeader> = text.lines().mapNotNull { line ->
    val index = line.indexOf('=')
    if (index <= 0) return@mapNotNull null
    CustomHeader(line.substring(0, index).trim(), line.substring(index + 1))
}.filter { CliAgent.isAllowedEnvName(it.name) }

/** Built-in agents' environment (API keys) and the user's own ACP agents. */
@Composable
private fun CliAgentsSheet(
    settings: Settings,
    onDismiss: () -> Unit,
    onUpdateSettings: ((Settings) -> Settings) -> Unit,
) {
    var editing by remember { mutableStateOf<CustomCliAgentConfig?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.cli_manage), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.cli_env_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.cli_builtin), style = MaterialTheme.typography.titleSmall)
            CliAgent.BUILT_IN.forEach { agent ->
                val stored = settings.builtInCliEnvOverrides.firstOrNull { it.id == agent.id }?.envVars.orEmpty()
                var text by remember(agent.id) { mutableStateOf(envText(stored)) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("${agent.name} · ${stringResource(R.string.cli_field_env)}") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().testTag("cli_env_${agent.executable}"),
                    minLines = 2,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = {
                            val vars = parseEnv(text)
                            onUpdateSettings { current ->
                                current.copy(builtInCliEnvOverrides = current.builtInCliEnvOverrides.filter { it.id != agent.id } +
                                    listOfNotNull(CliEnvOverride(agent.id, vars).takeIf { vars.isNotEmpty() }))
                            }
                        },
                        modifier = Modifier.testTag("cli_env_save_${agent.executable}"),
                    ) { Text(stringResource(R.string.chat_page_save)) }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.cli_custom), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.cli_custom_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            settings.customCliAgents.forEach { config ->
                CompactListItem(
                    headlineContent = { Text(config.name.ifBlank { config.executable }) },
                    supportingContent = { Text((listOf(config.executable) + config.args).joinToString(" ")) },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { editing = config }) { Text(stringResource(R.string.edit)) }
                            TextButton(onClick = {
                                onUpdateSettings { current -> current.copy(customCliAgents = current.customCliAgents.filter { it.id != config.id }) }
                            }) { Text(stringResource(R.string.delete)) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            TextButton(onClick = { editing = CustomCliAgentConfig() }) { Text(stringResource(R.string.cli_add_custom)) }

            editing?.let { config ->
                CustomAgentEditor(
                    config = config,
                    onCancel = { editing = null },
                    onSave = { saved ->
                        onUpdateSettings { current ->
                            val exists = current.customCliAgents.any { it.id == saved.id }
                            current.copy(customCliAgents = if (exists) {
                                current.customCliAgents.map { if (it.id == saved.id) saved else it }
                            } else {
                                current.customCliAgents + saved
                            })
                        }
                        editing = null
                    },
                )
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun CustomAgentEditor(config: CustomCliAgentConfig, onCancel: () -> Unit, onSave: (CustomCliAgentConfig) -> Unit) {
    var name by remember(config.id) { mutableStateOf(config.name) }
    var executable by remember(config.id) { mutableStateOf(config.executable) }
    var args by remember(config.id) { mutableStateOf(config.args.joinToString(" ")) }
    var env by remember(config.id) { mutableStateOf(envText(config.envVars)) }
    var login by remember(config.id) { mutableStateOf(config.loginCommand) }
    var install by remember(config.id) { mutableStateOf(config.installCommand) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.cli_field_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(executable, { executable = it }, label = { Text(stringResource(R.string.cli_field_executable)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(args, { args = it }, label = { Text(stringResource(R.string.cli_field_args)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            env, { env = it },
            label = { Text(stringResource(R.string.cli_field_env)) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedTextField(login, { login = it }, label = { Text(stringResource(R.string.cli_field_login)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(install, { install = it }, label = { Text(stringResource(R.string.cli_field_install)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            TextButton(
                enabled = executable.isNotBlank(),
                onClick = {
                    onSave(
                        config.copy(
                            name = name.trim(),
                            executable = executable.trim(),
                            args = args.split(Regex("\\s+")).filter { it.isNotBlank() },
                            envVars = parseEnv(env),
                            loginCommand = login.trim(),
                            installCommand = install.trim(),
                        )
                    )
                },
            ) { Text(stringResource(R.string.chat_page_save)) }
        }
    }
}
