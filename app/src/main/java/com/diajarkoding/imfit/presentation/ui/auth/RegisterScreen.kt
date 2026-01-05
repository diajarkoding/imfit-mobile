package com.diajarkoding.imfit.presentation.ui.auth

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.diajarkoding.imfit.R
import androidx.compose.ui.res.stringResource
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITPasswordField
import com.diajarkoding.imfit.presentation.components.common.IMFITTextField
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.theme.IMFITTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onNavigateToLogin: () -> Unit,
    onRegisterSuccess: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showPhotoSheet by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onProfilePhotoSelected(it) }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            tempCameraUri?.let { viewModel.onProfilePhotoSelected(it) }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val photoFile = File(
                context.cacheDir,
                "photo_${System.currentTimeMillis()}.jpg"
            )
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                photoFile
            )
            tempCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    LaunchedEffect(state.registerSuccess) {
        if (state.registerSuccess) {
            onRegisterSuccess()
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            // Use ISO format for database (yyyy-MM-dd)
                            val isoFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            val date = isoFormatter.format(Date(millis))
                            viewModel.onBirthDateChange(date)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_ok), color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showPhotoSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPhotoSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(IMFITSpacing.lg)
                    .padding(bottom = IMFITSpacing.xxl)
            ) {
                Text(
                    text = stringResource(R.string.register_choose_photo),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = IMFITSpacing.lg)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    PhotoOptionButton(
                        icon = Icons.Default.CameraAlt,
                        label = stringResource(R.string.register_camera),
                        onClick = {
                            showPhotoSheet = false
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )
                    PhotoOptionButton(
                        icon = Icons.Default.PhotoLibrary,
                        label = stringResource(R.string.register_gallery),
                        onClick = {
                            showPhotoSheet = false
                            galleryLauncher.launch("image/*")
                        }
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = IMFITSpacing.screenHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            // Title at the top
            Text(
                text = stringResource(R.string.register_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xs))

            Text(
                text = stringResource(R.string.register_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            // Profile Photo Picker - Camera badge on top layer with zIndex
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clickable { showPhotoSheet = true },
                contentAlignment = Alignment.Center
            ) {
                // Main photo circle
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, Primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.profilePhotoUri != null) {
                        AsyncImage(
                            model = state.profilePhotoUri,
                            contentDescription = "Profile Photo",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(
                                            Primary.copy(alpha = 0.1f),
                                            PrimaryLight.copy(alpha = 0.1f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = stringResource(R.string.desc_add_photo),
                                modifier = Modifier.size(56.dp),
                                tint = Primary.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // Camera badge - Positioned at bottom-end with zIndex for topmost layer
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-5).dp, y = (-5).dp)
                        .zIndex(1f)
                        .size(40.dp)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            // Form Fields
            IMFITTextField(
                value = state.name,
                onValueChange = { viewModel.onNameChange(it) },
                label = stringResource(R.string.label_fullname),
                placeholder = stringResource(R.string.placeholder_email).replace("email", "name"),
                error = state.nameError,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            IMFITTextField(
                value = state.email,
                onValueChange = { viewModel.onEmailChange(it) },
                label = stringResource(R.string.label_email),
                placeholder = stringResource(R.string.placeholder_email),
                error = state.emailError,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            // Birth Date Field - Same style as other inputs
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.label_date_of_birth),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = IMFITSpacing.sm)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true }
                ) {
                    OutlinedTextField(
                        value = state.birthDate,
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IMFITSizes.textFieldHeight),
                        enabled = false,
                        placeholder = {
                            Text(
                                stringResource(R.string.register_select_birth_date),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = stringResource(R.string.desc_select_date),
                                tint = Primary
                            )
                        },
                        isError = state.birthDateError != null,
                        shape = IMFITShapes.TextField,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = if (state.birthDateError != null)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            disabledTrailingIconColor = Primary
                        )
                    )
                }
                if (state.birthDateError != null) {
                    Text(
                        text = state.birthDateError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = IMFITSpacing.xs, top = IMFITSpacing.xs)
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            IMFITPasswordField(
                value = state.password,
                onValueChange = { viewModel.onPasswordChange(it) },
                label = stringResource(R.string.label_password),
                error = state.passwordError,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            IMFITPasswordField(
                value = state.confirmPassword,
                onValueChange = { viewModel.onConfirmPasswordChange(it) },
                label = stringResource(R.string.label_confirm_password),
                error = state.confirmPasswordError,
                imeAction = ImeAction.Done,
                onImeAction = { viewModel.register() }
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            IMFITButton(
                text = stringResource(R.string.register_button),
                onClick = { viewModel.register() },
                isLoading = state.isLoading
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.register_redirect_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.register_redirect_action),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Primary,
                    modifier = Modifier.clickable { onNavigateToLogin() }
                )
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.huge))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}

@Composable
private fun PhotoOptionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(IMFITShapes.Card)
            .clickable { onClick() }
            .padding(IMFITSpacing.lg)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(IMFITSpacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegisterScreenContent(
    name: String = "",
    email: String = "",
    birthDate: String = "",
    password: String = "",
    confirmPassword: String = "",
    profilePhotoUri: Uri? = null,
    nameError: String? = null,
    emailError: String? = null,
    birthDateError: String? = null,
    passwordError: String? = null,
    confirmPasswordError: String? = null,
    isLoading: Boolean = false,
    onNameChange: (String) -> Unit = {},
    onEmailChange: (String) -> Unit = {},
    onBirthDateChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onConfirmPasswordChange: (String) -> Unit = {},
    onProfilePhotoClick: () -> Unit = {},
    onDatePickerClick: () -> Unit = {},
    onRegister: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = IMFITSpacing.screenHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            Text(
                text = stringResource(R.string.register_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xs))

            Text(
                text = stringResource(R.string.register_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clickable { onProfilePhotoClick() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, Primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (profilePhotoUri != null) {
                        AsyncImage(
                            model = profilePhotoUri,
                            contentDescription = "Profile Photo",
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(
                                            Primary.copy(alpha = 0.1f),
                                            PrimaryLight.copy(alpha = 0.1f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = stringResource(R.string.desc_add_photo),
                                modifier = Modifier.size(56.dp),
                                tint = Primary.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-5).dp, y = (-5).dp)
                        .zIndex(1f)
                        .size(40.dp)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            IMFITTextField(
                value = name,
                onValueChange = onNameChange,
                label = stringResource(R.string.label_fullname),
                placeholder = stringResource(R.string.placeholder_name),
                error = nameError,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            IMFITTextField(
                value = email,
                onValueChange = onEmailChange,
                label = stringResource(R.string.label_email),
                placeholder = stringResource(R.string.placeholder_email),
                error = emailError,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.label_date_of_birth),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = IMFITSpacing.sm)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDatePickerClick() }
                ) {
                    OutlinedTextField(
                        value = birthDate,
                        onValueChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IMFITSizes.textFieldHeight),
                        enabled = false,
                        placeholder = {
                            Text(
                                stringResource(R.string.register_select_birth_date),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = stringResource(R.string.desc_select_date),
                                tint = Primary
                            )
                        },
                        isError = birthDateError != null,
                        shape = IMFITShapes.TextField,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = if (birthDateError != null)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            disabledTrailingIconColor = Primary
                        )
                    )
                }
                if (birthDateError != null) {
                    Text(
                        text = birthDateError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = IMFITSpacing.xs, top = IMFITSpacing.xs)
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            IMFITPasswordField(
                value = password,
                onValueChange = onPasswordChange,
                label = stringResource(R.string.label_password),
                error = passwordError,
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            IMFITPasswordField(
                value = confirmPassword,
                onValueChange = onConfirmPasswordChange,
                label = stringResource(R.string.label_confirm_password),
                error = confirmPasswordError,
                imeAction = ImeAction.Done,
                onImeAction = onRegister
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            IMFITButton(
                text = stringResource(R.string.register_button),
                onClick = onRegister,
                isLoading = isLoading
            )

            Spacer(modifier = Modifier.height(IMFITSpacing.xl))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.register_redirect_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.register_redirect_action),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Primary,
                    modifier = Modifier.clickable { onNavigateToLogin() }
                )
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.huge))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RegisterScreenPreviewLight() {
    IMFITTheme(darkTheme = false) {
        RegisterScreenContent()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RegisterScreenPreviewDark() {
    IMFITTheme(darkTheme = true) {
        RegisterScreenContent()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RegisterScreenPreviewWithData() {
    IMFITTheme(darkTheme = false) {
        RegisterScreenContent(
            name = "John Doe",
            email = "john@example.com",
            birthDate = "1990-01-15",
            password = "password123",
            confirmPassword = "password123"
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RegisterScreenPreviewWithErrors() {
    IMFITTheme(darkTheme = false) {
        RegisterScreenContent(
            name = "",
            email = "invalid-email",
            birthDate = "",
            password = "123",
            confirmPassword = "456",
            nameError = "Name is required",
            emailError = "Invalid email format",
            birthDateError = "Birth date is required",
            passwordError = "Password must be at least 6 characters",
            confirmPasswordError = "Passwords do not match"
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RegisterScreenPreviewLoading() {
    IMFITTheme(darkTheme = false) {
        RegisterScreenContent(
            name = "John Doe",
            email = "john@example.com",
            birthDate = "1990-01-15",
            password = "password123",
            confirmPassword = "password123",
            isLoading = true
        )
    }
}
