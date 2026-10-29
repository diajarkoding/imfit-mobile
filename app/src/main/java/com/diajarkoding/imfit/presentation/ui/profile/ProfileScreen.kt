package com.diajarkoding.imfit.presentation.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.diajarkoding.imfit.data.remote.online.SessionSavePhase
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.RoutineAppBar
import com.diajarkoding.imfit.presentation.ui.progress.ProgressState
import com.diajarkoding.imfit.presentation.components.common.RemoteRefreshBox
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.IMFITTheme
import java.util.Locale

@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    isDarkMode: Boolean,
    onToggleTheme: () -> Unit,
    isIndonesian: Boolean,
    onToggleLanguage: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
    onRecovery: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    var confirmLogout by remember(session.identity) { mutableStateOf(false) }
    if (confirmLogout) {
        AlertDialog(onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.session_leave_title)) },
            text = { Text(stringResource(R.string.session_leave_message)) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.logout(onLogout) }) { Text(stringResource(R.string.action_logout)) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.session_stay)) } })
    }
    RemoteRefreshBox(state.loading, viewModel::refresh, Modifier.fillMaxSize()) {
    ProfileScreenContent(
        state = ProgressState(userName = state.data?.name.orEmpty(), userEmail = state.data?.email.orEmpty(),
            userBirthDate = state.data?.birthDate, userProfilePhotoUri = state.data?.avatarUrl,
            isLoading = state.loading && state.data == null, hasError = state.error != null),
        isDarkMode = isDarkMode,
        isIndonesian = isIndonesian,
        onNavigateBack = onNavigateBack,
        onLogout = {
            if (session.drafts.isNotEmpty() || session.pendingRequestId != null || session.requiresVerification ||
                session.savePhase in setOf(SessionSavePhase.UNKNOWN, SessionSavePhase.RESOLVING, SessionSavePhase.CONFLICT))
                confirmLogout = true
            else viewModel.logout(onLogout)
        },
        onToggleTheme = onToggleTheme,
        onToggleLanguage = onToggleLanguage,
        onRetry = viewModel::refresh,
        stale = state.stale && state.error != null,
        onRecovery = onRecovery
    )
    }
}

/** One renderer shared by the route, previews and synthetic UI fixtures. */
@Composable
internal fun ProfileScreenContent(
    state: ProgressState,
    isDarkMode: Boolean,
    isIndonesian: Boolean,
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleLanguage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
    onRecovery: () -> Unit = {},
) {
    val colors = IMFITColors.training
    val presentation = profileDataPresentation(state)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val missingValue = stringResource(R.string.profile_missing_value)
    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = { RoutineAppBar(stringResource(R.string.title_profile), onNavigateBack) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("profile_list"),
            contentPadding = PaddingValues(
                start = IMFITSpacing.screenHorizontal, end = IMFITSpacing.screenHorizontal,
                top = IMFITSpacing.headerContentGap,
                bottom = IMFITSpacing.xxxl
            ),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sectionSpacing)
        ) {
            if (stale) item("stale") { com.diajarkoding.imfit.presentation.components.common.RemoteReadFailure(true, onRetry) }
            if (presentation != ProfileDataPresentation.Unavailable) {
                item(key = "identity") {
                    if (presentation == ProfileDataPresentation.Loading) ProfileIdentityLoading()
                    else ProfileIdentity(state)
                }
            }
            item(key = "information") {
                ProfileSection(stringResource(R.string.profile_information_title)) {
                    when (presentation) {
                        ProfileDataPresentation.Loading -> ProfileInformationLoading()
                        ProfileDataPresentation.Unavailable -> ProfileLoadFailure(onRetry)
                        ProfileDataPresentation.Ready -> ProfileInformation(
                            state.userName.ifBlank { stringResource(R.string.progress_overview_guest) },
                            state.userEmail.ifBlank { missingValue },
                            formatProfileBirthDate(state.userBirthDate, locale, missingValue)
                        )
                    }
                }
            }
            item(key = "preferences") {
                ProfileSection(stringResource(R.string.profile_preferences_title)) {
                    ProfilePreferences(isDarkMode, isIndonesian, onToggleTheme, onToggleLanguage)
                }
            }
            item(key = "recovery") {
                com.diajarkoding.imfit.presentation.components.common.RoutineButton(
                    stringResource(R.string.legacy_recovery_title), onRecovery, outlined = true)
            }
            item(key = "logout") { ProfileLogout(onLogout) }
        }
    }
}

@Composable
private fun ProfileSection(title: String, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
        Text(title, Modifier.semantics { heading() }, color = IMFITColors.training.text,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 900, locale = "in")
@Composable
private fun ProfilePreviewLight() = ProfilePreview(false)

@Preview(showBackground = true, widthDp = 412, heightDp = 900, locale = "in")
@Composable
private fun ProfilePreviewDark() = ProfilePreview(true)

@Preview(showBackground = true, widthDp = 320, heightDp = 1000, fontScale = 1.3f, locale = "in")
@Composable
private fun ProfilePreviewCompact() = ProfilePreview(false, longName = true)

@Preview(showBackground = true, widthDp = 412, heightDp = 900, locale = "in")
@Composable
private fun ProfilePreviewLoading() = ProfilePreview(false, state = ProgressState())

@Preview(showBackground = true, widthDp = 412, heightDp = 900, locale = "in")
@Composable
private fun ProfilePreviewFailure() = ProfilePreview(false, state = ProgressState(isLoading = false, hasError = true))

@Composable
private fun ProfilePreview(dark: Boolean, longName: Boolean = false, state: ProgressState? = null, indonesian: Boolean = true) {
    IMFITTheme(darkTheme = dark) {
        ProfileScreenContent(
            state ?: ProgressState(userName = if (longName) "Alex Pratama Wijayakusuma" else "Alex Pratama",
                userEmail = "alex@example.com", userBirthDate = "1998-01-15", isLoading = false),
            dark, indonesian, {}, {}, {}, {}, {}
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 900, locale = "en")
@Composable
private fun ProfilePreviewEnglish() = ProfilePreview(false, indonesian = false)

@Preview(showBackground = true, widthDp = 412, heightDp = 900, locale = "in")
@Composable
private fun ProfilePreviewOptionalEmpty() = ProfilePreview(false,
    state = ProgressState(userName = "Alex Pratama", userEmail = "alex@example.com", isLoading = false))
