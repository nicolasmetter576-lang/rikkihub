package me.rerere.rikkihub.ui.pages.backup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.components.ui.toast.ToastType
import me.rerere.rikkihub.ui.context.LocalToaster
import me.rerere.rikkihub.ui.pages.backup.components.BackupDialog
import me.rerere.rikkihub.ui.pages.backup.components.BackupPasswordPromptDialog
import me.rerere.rikkihub.ui.pages.backup.components.RestoreBusyDialog
import me.rerere.rikkihub.ui.pages.backup.components.RestorePrecheckDialog
import me.rerere.rikkihub.ui.pages.backup.tabs.ImportExportTab
import me.rerere.rikkihub.ui.pages.backup.tabs.LocalSnapshotsTab
import me.rerere.rikkihub.ui.pages.backup.tabs.ReminderTab
import me.rerere.rikkihub.ui.pages.backup.tabs.S3Tab
import me.rerere.rikkihub.ui.pages.backup.tabs.WebDavTab
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val pagerState = rememberPagerState { 5 }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val restoreFlow by vm.restoreFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.backup_page_title))
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            SecondaryScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = CustomColors.topBarColors.containerColor,
                edgePadding = 4.dp,
            ) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text(stringResource(R.string.backup_page_import_export)) }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text(stringResource(R.string.backup_page_local_snapshots)) }
                )
                Tab(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    text = { Text(stringResource(R.string.backup_page_webdav_backup)) }
                )
                Tab(
                    selected = pagerState.currentPage == 3,
                    onClick = { scope.launch { pagerState.animateScrollToPage(3) } },
                    text = { Text(stringResource(R.string.backup_page_s3_backup)) }
                )
                Tab(
                    selected = pagerState.currentPage == 4,
                    onClick = { scope.launch { pagerState.animateScrollToPage(4) } },
                    text = { Text(stringResource(R.string.backup_page_reminder)) }
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> ImportExportTab(vm = vm)
                    1 -> LocalSnapshotsTab(vm = vm)
                    2 -> WebDavTab(vm = vm)
                    3 -> S3Tab(vm = vm)
                    4 -> ReminderTab(vm = vm)
                }
            }
        }
    }

    when (val state = restoreFlow) {
        RestoreFlowState.Idle -> Unit
        is RestoreFlowState.Busy -> RestoreBusyDialog(stage = state.stage)
        is RestoreFlowState.NeedPassword -> {
            BackupPasswordPromptDialog(
                title = stringResource(R.string.backup_enc_enter_password_title),
                description = stringResource(
                    R.string.backup_enc_enter_password_decrypt_desc,
                    state.session.displayName,
                ),
                errorMessage = state.errorMessage,
                onDismiss = { vm.cancelRestoreFlow() },
                onConfirm = { password ->
                    scope.launch {
                        runCatching {
                            vm.submitRestorePassword(password)
                        }.onFailure { err ->
                            toaster.show(
                                err.message ?: context.getString(R.string.backup_page_unknown_error),
                                type = ToastType.Error,
                            )
                        }
                    }
                },
            )
        }
        is RestoreFlowState.ReadyToConfirm -> {
            RestorePrecheckDialog(
                displayName = state.session.displayName,
                report = state.report,
                onDismiss = { vm.cancelRestoreFlow() },
                onConfirm = {
                    scope.launch {
                        runCatching {
                            vm.confirmAndStageRestore()
                            toaster.show(
                                context.getString(R.string.backup_page_restore_success),
                                type = ToastType.Success,
                            )
                        }.onFailure { err ->
                            toaster.show(
                                err.message ?: context.getString(R.string.backup_page_unknown_error),
                                type = ToastType.Error,
                            )
                        }
                    }
                },
            )
        }
        RestoreFlowState.StagedNeedRestart -> BackupDialog()
    }
}
