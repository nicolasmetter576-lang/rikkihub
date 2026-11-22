package me.rerere.rikkihub.ui.pages.setting

import androidx.annotation.StringRes
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.Settings

/**
 * 设置搜索能找到的具体设置项（页面入口另见设置首页的分块）。title 同时是目标页里那一项的 searchKey；
 * 开关项带读写，搜索结果里直接拨。需要先申请权限的开关（消息生成通知、悬浮窗）不带读写，点了跳到原页面。
 */
internal class SettingSearchItem(@StringRes val title: Int, val screen: Screen, val toggle: SettingToggle? = null)

internal class SettingToggle(val get: (Settings) -> Boolean, val set: (Settings, Boolean) -> Settings)

private fun display(get: (DisplaySetting) -> Boolean, set: (DisplaySetting, Boolean) -> DisplaySetting) =
    SettingToggle({ get(it.displaySetting) }) { s, v -> s.copy(displaySetting = set(s.displaySetting, v)) }

internal val settingSearchItems = listOf(
    // SettingPreferencesThemePage
    SettingSearchItem(R.string.setting_page_color_mode, Screen.SettingPreferencesTheme),
    SettingSearchItem(R.string.setting_page_dynamic_color, Screen.SettingPreferencesTheme, SettingToggle({ it.dynamicColor }) { s, v -> s.copy(dynamicColor = v) }),
    SettingSearchItem(R.string.setting_page_theme_setting, Screen.SettingPreferencesTheme),
    SettingSearchItem(R.string.setting_display_page_amoled_dark_mode_title, Screen.SettingPreferencesTheme),
    SettingSearchItem(R.string.setting_display_page_amoled_strong_title, Screen.SettingPreferencesTheme),
    // SettingPreferencesUIPage
    SettingSearchItem(R.string.setting_display_page_show_user_avatar_title, Screen.SettingPreferencesUI, display({ it.showUserAvatar }) { d, v -> d.copy(showUserAvatar = v) }),
    SettingSearchItem(R.string.setting_display_page_show_assistant_bubble_title, Screen.SettingPreferencesUI, display({ it.showAssistantBubble }) { d, v -> d.copy(showAssistantBubble = v) }),
    SettingSearchItem(R.string.setting_display_page_bubble_opacity_title, Screen.SettingPreferencesUI),
    SettingSearchItem(R.string.setting_display_page_chat_list_model_icon_title, Screen.SettingPreferencesUI, display({ it.showModelIcon }) { d, v -> d.copy(showModelIcon = v) }),
    SettingSearchItem(R.string.setting_display_page_show_model_name_title, Screen.SettingPreferencesUI, display({ it.showModelName }) { d, v -> d.copy(showModelName = v) }),
    SettingSearchItem(R.string.setting_display_page_show_datetime_in_message_title, Screen.SettingPreferencesUI, display({ it.showDateTimeInMessage }) { d, v -> d.copy(showDateTimeInMessage = v) }),
    SettingSearchItem(R.string.setting_display_page_show_token_usage_title, Screen.SettingPreferencesUI, display({ it.showTokenUsage }) { d, v -> d.copy(showTokenUsage = v) }),
    SettingSearchItem(R.string.setting_display_page_show_thinking_content_title, Screen.SettingPreferencesUI, display({ it.showThinkingContent }) { d, v -> d.copy(showThinkingContent = v) }),
    SettingSearchItem(R.string.setting_display_page_auto_collapse_thinking_title, Screen.SettingPreferencesUI, display({ it.autoCloseThinking }) { d, v -> d.copy(autoCloseThinking = v) }),
    SettingSearchItem(R.string.setting_display_page_collapse_long_user_message_title, Screen.SettingPreferencesUI, display({ it.collapseLongUserMessages }) { d, v -> d.copy(collapseLongUserMessages = v) }),
    SettingSearchItem(R.string.setting_display_page_collapse_long_user_message_chars_title, Screen.SettingPreferencesUI),
    SettingSearchItem(R.string.setting_display_page_enable_latex_rendering_title, Screen.SettingPreferencesUI, display({ it.enableLatexRendering }) { d, v -> d.copy(enableLatexRendering = v) }),
    SettingSearchItem(R.string.setting_display_page_enable_quote_highlight_title, Screen.SettingPreferencesUI, display({ it.enableQuoteHighlight }) { d, v -> d.copy(enableQuoteHighlight = v) }),
    SettingSearchItem(R.string.setting_display_page_workspace_markdown_sizes_title, Screen.SettingPreferencesUI, display({ it.workspaceMarkdownHeadingSizes }) { d, v -> d.copy(workspaceMarkdownHeadingSizes = v) }),
    SettingSearchItem(R.string.setting_display_page_chat_font_family_title, Screen.SettingPreferencesUI),
    SettingSearchItem(R.string.setting_display_page_custom_font_title, Screen.SettingPreferencesUI),
    SettingSearchItem(R.string.setting_display_page_font_size_title, Screen.SettingPreferencesUI),
    SettingSearchItem(R.string.setting_display_page_code_block_auto_wrap_title, Screen.SettingPreferencesUI, display({ it.codeBlockAutoWrap }) { d, v -> d.copy(codeBlockAutoWrap = v) }),
    SettingSearchItem(R.string.setting_display_page_code_block_auto_collapse_title, Screen.SettingPreferencesUI, display({ it.codeBlockAutoCollapse }) { d, v -> d.copy(codeBlockAutoCollapse = v) }),
    SettingSearchItem(R.string.setting_display_page_show_line_numbers_title, Screen.SettingPreferencesUI, display({ it.showLineNumbers }) { d, v -> d.copy(showLineNumbers = v) }),
    // SettingPreferencesGeneralPage
    SettingSearchItem(R.string.setting_display_page_create_new_conversation_on_start_title, Screen.SettingPreferencesGeneral),
    SettingSearchItem(R.string.setting_display_page_create_new_conversation_on_assistant_switch_title, Screen.SettingPreferencesGeneral),
    SettingSearchItem(R.string.setting_display_page_send_on_enter_title, Screen.SettingPreferencesGeneral, display({ it.sendOnEnter }) { d, v -> d.copy(sendOnEnter = v) }),
    SettingSearchItem(R.string.setting_display_page_show_message_jumper_title, Screen.SettingPreferencesGeneral, display({ it.showMessageJumper }) { d, v -> d.copy(showMessageJumper = v) }),
    SettingSearchItem(R.string.setting_display_page_message_jumper_position_title, Screen.SettingPreferencesGeneral, display({ it.messageJumperOnLeft }) { d, v -> d.copy(messageJumperOnLeft = v) }),
    SettingSearchItem(R.string.setting_display_page_enable_auto_scroll_title, Screen.SettingPreferencesGeneral, display({ it.enableAutoScroll }) { d, v -> d.copy(enableAutoScroll = v) }),
    SettingSearchItem(R.string.setting_display_page_use_app_icon_style_loading_indicator_title, Screen.SettingPreferencesGeneral, display({ it.useAppIconStyleLoadingIndicator }) { d, v -> d.copy(useAppIconStyleLoadingIndicator = v) }),
    SettingSearchItem(R.string.setting_display_page_enable_blur_effect_title, Screen.SettingPreferencesGeneral, display({ it.enableBlurEffect }) { d, v -> d.copy(enableBlurEffect = v) }),
    SettingSearchItem(R.string.setting_display_page_enable_message_generation_haptic_effect_title, Screen.SettingPreferencesGeneral, display({ it.enableMessageGenerationHapticEffect }) { d, v -> d.copy(enableMessageGenerationHapticEffect = v) }),
    SettingSearchItem(R.string.setting_display_page_skip_crop_image_title, Screen.SettingPreferencesGeneral, display({ it.skipCropImage }) { d, v -> d.copy(skipCropImage = v) }),
    SettingSearchItem(R.string.setting_display_page_paste_long_text_as_file_title, Screen.SettingPreferencesGeneral, display({ it.pasteLongTextAsFile }) { d, v -> d.copy(pasteLongTextAsFile = v) }),
    SettingSearchItem(R.string.setting_display_page_paste_long_text_threshold_title, Screen.SettingPreferencesGeneral),
    SettingSearchItem(R.string.setting_display_page_volume_key_scroll_title, Screen.SettingPreferencesGeneral, display({ it.enableVolumeKeyScroll }) { d, v -> d.copy(enableVolumeKeyScroll = v) }),
    SettingSearchItem(R.string.setting_display_page_volume_key_scroll_ratio, Screen.SettingPreferencesGeneral),
    SettingSearchItem(R.string.setting_tts_page_default_playback_speed, Screen.SettingPreferencesGeneral),
    SettingSearchItem(R.string.setting_display_page_tts_only_read_quoted_title, Screen.SettingPreferencesGeneral, display({ it.ttsOnlyReadQuoted }) { d, v -> d.copy(ttsOnlyReadQuoted = v) }),
    SettingSearchItem(R.string.setting_display_page_tts_read_outside_brackets_title, Screen.SettingPreferencesGeneral, display({ it.ttsOnlyReadOutsideBrackets }) { d, v -> d.copy(ttsOnlyReadOutsideBrackets = v) }),
    SettingSearchItem(R.string.setting_display_page_auto_play_tts_title, Screen.SettingPreferencesGeneral, display({ it.autoPlayTTSAfterGeneration }) { d, v -> d.copy(autoPlayTTSAfterGeneration = v) }),
    SettingSearchItem(R.string.setting_display_page_voice_listen_while_replying_title, Screen.SettingPreferencesGeneral, display({ it.voiceModeListenWhileReplying }) { d, v -> d.copy(voiceModeListenWhileReplying = v) }),
    // SettingPreferencesNotificationPage
    SettingSearchItem(R.string.setting_display_page_notification_message_generated, Screen.SettingPreferencesNotification),
    SettingSearchItem(R.string.setting_display_page_live_update_notification, Screen.SettingPreferencesNotification, display({ it.enableLiveUpdateNotification }) { d, v -> d.copy(enableLiveUpdateNotification = v) }),
    SettingSearchItem(R.string.setting_page_preferences_notification_floating_window, Screen.SettingPreferencesNotification),
    SettingSearchItem(R.string.setting_page_preferences_notification_floating_strip, Screen.SettingPreferencesNotification, display({ it.floatingChatStripWhileGenerating }) { d, v -> d.copy(floatingChatStripWhileGenerating = v) }),
    SettingSearchItem(R.string.setting_page_preferences_notification_floating_auto_hide, Screen.SettingPreferencesNotification, display({ it.floatingChatAutoHide }) { d, v -> d.copy(floatingChatAutoHide = v) }),
    SettingSearchItem(R.string.setting_page_preferences_notification_floating_opacity, Screen.SettingPreferencesNotification),
    SettingSearchItem(R.string.setting_page_preferences_notification_overlay_permission, Screen.SettingPreferencesNotification),
    // SettingPreferencesWorkspacePage
    SettingSearchItem(R.string.editor_typography_settings_desc, Screen.SettingPreferencesWorkspace),
    SettingSearchItem(R.string.setting_workspace_editor_auto_pair, Screen.SettingPreferencesWorkspace, display({ it.workspaceEditorAutoPair }) { d, v -> d.copy(workspaceEditorAutoPair = v) }),
    SettingSearchItem(R.string.setting_workspace_reader_mode, Screen.SettingPreferencesWorkspace),
    SettingSearchItem(R.string.setting_workspace_reader_volume_keys, Screen.SettingPreferencesWorkspace, display({ it.workspaceReaderVolumeKeys }) { d, v -> d.copy(workspaceReaderVolumeKeys = v) }),
    SettingSearchItem(R.string.setting_workspace_editor_toolbar_position, Screen.SettingPreferencesWorkspace),
    SettingSearchItem(R.string.setting_workspace_editor_toolbar_layout, Screen.SettingPreferencesWorkspace),
    SettingSearchItem(R.string.setting_workspace_editor_toolbar_size, Screen.SettingPreferencesWorkspace),
    // SettingPreferencesNetworkPage
    SettingSearchItem(R.string.setting_page_preferences_network_auto_retry, Screen.SettingPreferencesNetwork, SettingToggle({ it.networkSetting.enableAutoRetry }) { s, v -> s.copy(networkSetting = s.networkSetting.copy(enableAutoRetry = v)) }),
    SettingSearchItem(R.string.setting_page_preferences_network_rate_limit_retry, Screen.SettingPreferencesNetwork, SettingToggle({ it.networkSetting.enableRateLimitRetry }) { s, v -> s.copy(networkSetting = s.networkSetting.copy(enableRateLimitRetry = v)) }),
    SettingSearchItem(R.string.setting_page_config, Screen.SettingPreferencesNetwork),
    SettingSearchItem(R.string.setting_provider_page_test_connection, Screen.SettingPreferencesNetwork),
    // SettingModelPage
    SettingSearchItem(R.string.setting_model_page_enable_auto_title, Screen.SettingModels, SettingToggle({ it.enableAutoTitle }) { s, v -> s.copy(enableAutoTitle = v) }),
    SettingSearchItem(R.string.setting_model_page_enable_suggestion, Screen.SettingModels, SettingToggle({ it.enableSuggestion }) { s, v -> s.copy(enableSuggestion = v) }),
    SettingSearchItem(R.string.assistant_page_thinking_budget, Screen.SettingModels),
    // SettingPromptPolishPage
    SettingSearchItem(R.string.prompt_polish_context, Screen.SettingPromptPolish, SettingToggle({ it.promptPolish.includeContext }) { s, v -> s.copy(promptPolish = s.promptPolish.copy(includeContext = v)) }),
    SettingSearchItem(R.string.prompt_polish_context_count, Screen.SettingPromptPolish),
    // SettingMemoryDreamPage
    SettingSearchItem(R.string.setting_memory_dream_auto, Screen.SettingMemoryDream, SettingToggle({ it.memoryDream.autoDream }) { s, v -> s.copy(memoryDream = s.memoryDream.copy(autoDream = v)) }),
    SettingSearchItem(R.string.setting_memory_dream_idle, Screen.SettingMemoryDream),
    SettingSearchItem(R.string.setting_memory_dream_turns, Screen.SettingMemoryDream),
    SettingSearchItem(R.string.setting_memory_dream_on_leave, Screen.SettingMemoryDream, SettingToggle({ it.memoryDream.dreamOnLeave }) { s, v -> s.copy(memoryDream = s.memoryDream.copy(dreamOnLeave = v)) }),
    SettingSearchItem(R.string.setting_memory_dream_on_background, Screen.SettingMemoryDream, SettingToggle({ it.memoryDream.dreamOnBackground }) { s, v -> s.copy(memoryDream = s.memoryDream.copy(dreamOnBackground = v)) }),
    SettingSearchItem(R.string.setting_memory_dream_on_startup, Screen.SettingMemoryDream, SettingToggle({ it.memoryDream.catchUpOnStartup }) { s, v -> s.copy(memoryDream = s.memoryDream.copy(catchUpOnStartup = v)) }),
    SettingSearchItem(R.string.setting_memory_dream_steps, Screen.SettingMemoryDream),
    // SettingCompressionPage
    SettingSearchItem(R.string.compress_mode_label, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_trigger, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_target, Screen.SettingCompression),
    SettingSearchItem(R.string.chat_page_compress_keep_recent, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_summary_tokens, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_model, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_carry_mode, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_carry_count, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_carry_replies, Screen.SettingCompression, SettingToggle({ it.compression.carryIncludeReplies }) { s, v -> s.copy(compression = s.compression.copy(carryIncludeReplies = v)) }),
    SettingSearchItem(R.string.setting_compression_max_requests, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_chunk_timeout, Screen.SettingCompression),
    SettingSearchItem(R.string.setting_compression_templates, Screen.SettingCompression),
    // SettingToolsPage
    SettingSearchItem(R.string.setting_page_tools_parallel, Screen.SettingTools),
    // SettingTuiqiaoPage
    SettingSearchItem(R.string.tuiqiao_settings_count, Screen.SettingTuiqiao),
    SettingSearchItem(R.string.tuiqiao_settings_explanation, Screen.SettingTuiqiao),
    // SettingZaizaoPage
    SettingSearchItem(R.string.zaizao_reasoning, Screen.SettingZaizao),
    SettingSearchItem(R.string.zaizao_include_after, Screen.SettingZaizao, SettingToggle({ it.zaizao.includeAfter }) { s, v -> s.copy(zaizao = s.zaizao.copy(includeAfter = v)) }),
    SettingSearchItem(R.string.zaizao_default_type, Screen.SettingZaizao),
    SettingSearchItem(R.string.zaizao_files_shared, Screen.SettingZaizao),
    // SettingQubaguPage
    SettingSearchItem(R.string.qubagu_protect_dialogue, Screen.SettingQubagu, SettingToggle({ it.qubagu.protectDialogue }) { s, v -> s.copy(qubagu = s.qubagu.copy(protectDialogue = v)) }),
    SettingSearchItem(R.string.qubagu_protect_corner, Screen.SettingQubagu, SettingToggle({ it.qubagu.protectCornerQuotes }) { s, v -> s.copy(qubagu = s.qubagu.copy(protectCornerQuotes = v)) }),
    SettingSearchItem(R.string.qubagu_protect_details, Screen.SettingQubagu, SettingToggle({ it.qubagu.protectDetails }) { s, v -> s.copy(qubagu = s.qubagu.copy(protectDetails = v)) }),
    SettingSearchItem(R.string.qubagu_merge, Screen.SettingQubagu, SettingToggle({ it.qubagu.merge }) { s, v -> s.copy(qubagu = s.qubagu.copy(merge = v)) }),
    SettingSearchItem(R.string.qubagu_refine_auto, Screen.SettingQubagu, SettingToggle({ it.qubagu.refine }) { s, v -> s.copy(qubagu = s.qubagu.copy(refine = v)) }),
    // SettingWebPage
    SettingSearchItem(R.string.setting_page_web_server_port, Screen.SettingWeb),
    SettingSearchItem(R.string.setting_page_web_server_localhost_only, Screen.SettingWeb, SettingToggle({ it.webServerLocalhostOnly }) { s, v -> s.copy(webServerLocalhostOnly = v) }),
    SettingSearchItem(R.string.setting_page_web_server_https, Screen.SettingWeb, SettingToggle({ it.webServerHttpsEnabled }) { s, v -> s.copy(webServerHttpsEnabled = v) }),
    SettingSearchItem(R.string.setting_page_web_server_jwt_enable, Screen.SettingWeb, SettingToggle({ it.webServerJwtEnabled }) { s, v -> s.copy(webServerJwtEnabled = v) }),
    SettingSearchItem(R.string.setting_page_web_server_password, Screen.SettingWeb),
    SettingSearchItem(R.string.setting_page_web_server_lan_address, Screen.SettingWeb),
    SettingSearchItem(R.string.setting_page_web_server_mdns_address, Screen.SettingWeb),
    SettingSearchItem(R.string.setting_page_web_server_local_address, Screen.SettingWeb),
    SettingSearchItem(R.string.web_tls_fingerprint, Screen.SettingWeb),
    SettingSearchItem(R.string.web_tls_trust_title, Screen.SettingWeb),
    SettingSearchItem(R.string.web_tls_export, Screen.SettingWeb),
    SettingSearchItem(R.string.web_tls_rotate, Screen.SettingWeb),
)
