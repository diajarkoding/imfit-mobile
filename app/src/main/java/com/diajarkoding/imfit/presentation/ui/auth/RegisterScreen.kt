package com.diajarkoding.imfit.presentation.ui.auth

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.RegisterResult
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITTheme
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class RegisterOverlay { None, PhotoSource, BirthDate }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onNavigateToLogin: () -> Unit,
    onAuthenticated: () -> Unit,
    onCheckEmail: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var overlay by rememberSaveable { mutableStateOf(RegisterOverlay.None) }
    var photoError by rememberSaveable { mutableStateOf<Int?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { photoError = null; viewModel.onProfilePhotoSelected(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { photoError = null; viewModel.onProfilePhotoSelected(Uri.parse(it)) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            try {
                val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                cameraUri = uri.toString()
                camera.launch(uri)
            } catch (_: Exception) {
                photoError = R.string.auth_photo_unavailable
            }
        } else {
            photoError = R.string.auth_camera_permission_denied
        }
    }
    LaunchedEffect(state.registerResult) {
        when (state.registerResult) {
            is RegisterResult.Authenticated -> onAuthenticated()
            RegisterResult.CheckEmail -> onCheckEmail()
            null -> Unit
        }
        if (state.registerResult != null) viewModel.consumeRegisterResult()
    }
    BackHandler(enabled = overlay == RegisterOverlay.None) {
        if (!state.isLoading) onNavigateToLogin()
    }
    if (overlay == RegisterOverlay.BirthDate) AuthBirthDatePicker(state.birthDate,
        onDismiss = { overlay = RegisterOverlay.None },
        onSelect = { viewModel.onBirthDateChange(it); overlay = RegisterOverlay.None })
    if (overlay == RegisterOverlay.PhotoSource) {
        PhotoSourceSheet(onDismiss = { overlay = RegisterOverlay.None },
            onCamera = {
                overlay = RegisterOverlay.None
                try { permission.launch(Manifest.permission.CAMERA) }
                catch (_: Exception) { photoError = R.string.auth_photo_unavailable }
            },
            onGallery = {
                overlay = RegisterOverlay.None
                try { gallery.launch("image/*") }
                catch (_: Exception) { photoError = R.string.auth_photo_unavailable }
            })
    }
    RegisterScreenContent(state, viewModel::onNameChange, viewModel::onEmailChange,
        viewModel::onPasswordChange, viewModel::onConfirmPasswordChange,
        { overlay = RegisterOverlay.PhotoSource }, { overlay = RegisterOverlay.BirthDate }, viewModel::register,
        onNavigateToLogin, snackbar, photoError?.let { stringResource(it) }, overlay != RegisterOverlay.None)
}

@Composable
private fun RegisterScreenContent(
    state: RegisterState = RegisterState(),
    onNameChange: (String) -> Unit = {}, onEmailChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {}, onConfirmPasswordChange: (String) -> Unit = {},
    onPhotoClick: () -> Unit = {}, onDateClick: () -> Unit = {}, onRegister: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {}, snackbar: SnackbarHostState = remember { SnackbarHostState() },
    photoError: String? = null,
    pickerOpen: Boolean = false
) {
    val colors = IMFITColors.training
    val enabled = !state.isLoading
    val dateFocus = remember { FocusRequester() }
    val photoFocus = remember { FocusRequester() }
    var lastPicker by remember { mutableStateOf(RegisterOverlay.None) }
    LaunchedEffect(pickerOpen) {
        if (!pickerOpen) {
            when (lastPicker) {
                RegisterOverlay.BirthDate -> dateFocus.requestFocus()
                RegisterOverlay.PhotoSource -> photoFocus.requestFocus()
                RegisterOverlay.None -> Unit
            }
            lastPicker = RegisterOverlay.None
        }
    }
    Box(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            AuthRegisterHeading(onNavigateToLogin, enabled)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AuthTextField(state.name, onNameChange, stringResource(R.string.label_fullname), stringResource(R.string.placeholder_name),
                    error = state.nameError?.let { stringResource(it) }, enabled = enabled)
                AuthTextField(state.email, onEmailChange, stringResource(R.string.label_email), stringResource(R.string.placeholder_email),
                    error = state.emailError?.let { stringResource(it) }, keyboardType = KeyboardType.Email, enabled = enabled)
                Column {
                    AuthFieldLabel(stringResource(R.string.label_date_of_birth), state.birthDateError?.let { stringResource(it) })
                    Spacer(Modifier.height(8.dp))
                    AuthBorderedRow(Modifier.focusRequester(dateFocus).focusable(enabled),
                        enabled = enabled, isError = state.birthDateError != null,
                        onClick = { lastPicker = RegisterOverlay.BirthDate; onDateClick() }) {
                        val date = runCatching { LocalDate.parse(state.birthDate).format(
                            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault())) }.getOrDefault(state.birthDate)
                        Text(date.ifBlank { stringResource(R.string.register_select_birth_date) }, Modifier.weight(1f),
                            color = if (date.isBlank()) colors.muted else colors.text, style = MaterialTheme.typography.bodyLarge)
                        Icon(Symbols.Default.CalendarMonth, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    }
                    AuthFieldError(state.birthDateError?.let { stringResource(it) })
                }
                AuthTextField(state.password, onPasswordChange, stringResource(R.string.label_password), stringResource(R.string.auth_create_password),
                    error = state.passwordError?.let { stringResource(it) }, password = true, enabled = enabled)
                AuthTextField(state.confirmPassword, onConfirmPasswordChange, stringResource(R.string.label_confirm_password), stringResource(R.string.auth_repeat_password),
                    error = state.confirmPasswordError?.let { stringResource(it) }, password = true, imeAction = ImeAction.Done, enabled = enabled, onDone = onRegister)
            }
            AuthProfilePhotoField(state.profilePhotoUri, enabled,
                onClick = { lastPicker = RegisterOverlay.PhotoSource; onPhotoClick() },
                modifier = Modifier.focusRequester(photoFocus).focusable(enabled))
            AuthOperationError(photoError, stringResource(R.string.auth_photo_failure_title))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AuthOperationError(state.errorMessage?.let { stringResource(it) }, stringResource(R.string.auth_register_failure_title))
                AuthPrimaryButton(stringResource(R.string.register_button), onRegister, state.isLoading,
                    loadingText = stringResource(R.string.auth_registering))
                AuthRedirect(stringResource(R.string.register_redirect_prompt), stringResource(R.string.register_redirect_action), onNavigateToLogin, enabled)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun PhotoIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Int = 48) {
    val colors = IMFITColors.training
    Box(Modifier.size(size.dp).background(colors.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoSourceSheet(onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    val colors = IMFITColors.training
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.register_choose_photo), color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.auth_photo_source_description), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
            Column {
                PhotoSourceRow(Symbols.Default.CameraAlt, stringResource(R.string.register_camera), stringResource(R.string.auth_camera_description), onCamera)
                PhotoSourceRow(Symbols.Default.PhotoLibrary, stringResource(R.string.register_gallery), stringResource(R.string.auth_gallery_description), onGallery)
            }
            TextButton(onDismiss, Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text(stringResource(R.string.action_cancel), color = colors.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PhotoSourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String, onClick: () -> Unit) {
    val colors = IMFITColors.training
    Row(Modifier.fillMaxWidth().clip(AuthControlShape).clickable(onClick = onClick).heightIn(min = 76.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PhotoIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.muted, modifier = Modifier.size(20.dp))
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 956)
@Composable
private fun RegisterPreviewLight() { IMFITTheme { RegisterScreenContent() } }

@Preview(showBackground = true, widthDp = 412, heightDp = 956)
@Composable
private fun RegisterPreviewDark() { IMFITTheme(darkTheme = true) { RegisterScreenContent() } }

@Preview(showBackground = true, widthDp = 412, heightDp = 956)
@Composable
private fun RegisterPreviewValidation() {
    IMFITTheme { RegisterScreenContent(RegisterState(email = "invalid", emailError = R.string.error_email_invalid)) }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
private fun RegisterPreviewFailure() {
    IMFITTheme {
        RegisterScreenContent(RegisterState(name = "Raka", email = "raka@email.com", errorMessage = R.string.auth_network_error))
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
private fun RegisterPreviewPhotoFailureDark() {
    IMFITTheme(darkTheme = true) {
        RegisterScreenContent(RegisterState(name = "Raka"), photoError = stringResource(R.string.auth_camera_permission_denied))
    }
}
