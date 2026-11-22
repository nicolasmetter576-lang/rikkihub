package me.rerere.rikkihub.ui.pages.setting

import me.rerere.rikkihub.ui.components.ui.rememberSettingSearchListState
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.AppSpacing
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.model.GenerationPhase
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.CardGroup
import me.rerere.rikkihub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkihub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkihub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkihub.ui.components.ui.switchItem
import me.rerere.rikkihub.ui.floating.BubbleEdge
import me.rerere.rikkihub.ui.floating.ChatFloatingBubble
import me.rerere.rikkihub.ui.floating.ChatFloatingStrip
import me.rerere.rikkihub.ui.floating.FloatingChatUiState
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        val previous = displaySetting
        displaySetting = setting
        // 只写改动的字段，在最新值上合并（同 SettingPreferencesUIPage）
        vm.updateDisplaySettings(previous, setting)
    }


    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    val context = LocalContext.current
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    // 打开悬浮窗开关时先去授权，授权回来才真正打开
    var enableFloatingAfterGrant by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        canDrawOverlays = Settings.canDrawOverlays(context)
        if (enableFloatingAfterGrant) {
            enableFloatingAfterGrant = false
            if (canDrawOverlays) {
                // 效果只建一次，闭包里的页面状态可能是旧的，按最新设置只改这一项
                val latest = vm.settings.value.displaySetting
                vm.updateDisplaySettings(latest, latest.copy(enableFloatingChatWindow = true))
            }
        }
        onPauseOrDispose {}
    }

    fun requestOverlayPermission() {
        val packageUri = "package:${context.packageName}".toUri()
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
        } catch (_: ActivityNotFoundException) {
            // 个别系统没有单独的悬浮窗授权页，退到应用详情页自己找
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_notification))
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            state = rememberSettingSearchListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = AppSpacing.xxs),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    if (it && !permissionState.allPermissionsGranted) {
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                                }
                            )
                        },
                        searchKey = R.string.setting_display_page_notification_message_generated,
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.enableLiveUpdateNotification,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                                    }
                                )
                            },
                            searchKey = R.string.setting_display_page_live_update_notification,
                        )
                    }
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = AppSpacing.xxs),
                    title = { Text(stringResource(R.string.setting_page_preferences_notification_background_generation)) },
                ) {
                    switchItem(
                        checked = displaySetting.enableFloatingChatWindow,
                        onCheckedChange = {
                            if (it && !canDrawOverlays) {
                                enableFloatingAfterGrant = true
                                requestOverlayPermission()
                            } else {
                                updateDisplaySetting(displaySetting.copy(enableFloatingChatWindow = it))
                            }
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_preferences_notification_floating_window_desc))
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification_floating_window)) },
                        searchKey = R.string.setting_page_preferences_notification_floating_window,
                    )
                    if (displaySetting.enableFloatingChatWindow) {
                        switchItem(
                            checked = displaySetting.floatingChatStripWhileGenerating,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(floatingChatStripWhileGenerating = it))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_preferences_notification_floating_strip_desc))
                            },
                            headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification_floating_strip)) },
                            searchKey = R.string.setting_page_preferences_notification_floating_strip,
                        )
                        switchItem(
                            checked = displaySetting.floatingChatAutoHide,
                            onCheckedChange = {
                                updateDisplaySetting(displaySetting.copy(floatingChatAutoHide = it))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.setting_page_preferences_notification_floating_auto_hide_desc))
                            },
                            headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification_floating_auto_hide)) },
                            searchKey = R.string.setting_page_preferences_notification_floating_auto_hide,
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification_floating_opacity)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Slider(
                                            value = displaySetting.floatingChatOpacity,
                                            onValueChange = {
                                                updateDisplaySetting(displaySetting.copy(floatingChatOpacity = it))
                                            },
                                            valueRange = 0.3f..1.0f,
                                            steps = 13,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(text = "${(displaySetting.floatingChatOpacity * 100).roundToInt()}%")
                                    }
                                    FloatingWindowPreview(opacity = displaySetting.floatingChatOpacity)
                                }
                            },
                            searchKey = R.string.setting_page_preferences_notification_floating_opacity,
                        )
                    }
                    // 开着但权限被收回了
                    if (displaySetting.enableFloatingChatWindow && !canDrawOverlays) {
                        item(
                            onClick = ::requestOverlayPermission,
                            headlineContent = { Text(stringResource(R.string.setting_page_preferences_notification_overlay_permission)) },
                            supportingContent = { Text(stringResource(R.string.setting_page_preferences_notification_overlay_permission_desc)) },
                            trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                            searchKey = R.string.setting_page_preferences_notification_overlay_permission,
                        )
                    }
                }
            }
        }
    }
}

// 在一段文字上叠一个气泡和一条横条，拖滑条时直接看到透明度的效果
@Composable
private fun FloatingWindowPreview(opacity: Float) {
    val sample = FloatingChatUiState(
        conversationId = Uuid.NIL,
        title = "",
        generating = true,
        compressing = false,
        processingStatus = null,
        phase = GenerationPhase.Waiting,
        hasReply = true,
        preview = stringResource(R.string.setting_page_preferences_notification_floating_preview_reply),
        previewIsReasoning = false,
        pendingTools = emptyList(),
        queuedCount = 0,
        queuePaused = false,
        error = null,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_page_preferences_notification_floating_preview_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Row(modifier = Modifier.graphicsLayer { alpha = opacity }, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                ChatFloatingStrip(state = sample, onClick = {})
            }
            ChatFloatingBubble(state = sample.copy(generating = false), edge = BubbleEdge.End, tucked = false, onClick = {})
        }
    }
}

