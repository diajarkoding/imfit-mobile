package com.diajarkoding.imfit.presentation.ui.auth

import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.AuthRedirectText
import com.diajarkoding.imfit.presentation.components.AuthScreenLayout
import com.diajarkoding.imfit.presentation.components.AuthTextField
import com.diajarkoding.imfit.presentation.components.DatePickerField
import com.diajarkoding.imfit.presentation.components.PasswordTextField
import com.diajarkoding.imfit.presentation.components.PrimaryButton
import java.io.File

@Composable
fun RegisterScreen(
    onNavigateToLogin: () -> Unit,
    onRegisterSuccess: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showImageSourceDialog by remember { mutableStateOf(false) }

    // --- Persiapan untuk mengambil gambar ---
    val tempImageUri = remember { mutableStateOf<Uri?>(null) }

    // Launcher untuk Galeri
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            viewModel.onEvent(RegisterEvent.ProfilePictureChanged(uri))
        }
    )

    // Launcher untuk Kamera
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                viewModel.onEvent(RegisterEvent.ProfilePictureChanged(tempImageUri.value))
            }
        }
    )

    // Launcher untuk meminta izin
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                // Jika izin diberikan, ulangi aksi yang diinginkan
                Toast.makeText(context, "Izin diberikan, silakan coba lagi.", Toast.LENGTH_SHORT)
                    .show()
            } else {
                Toast.makeText(context, "Izin ditolak.", Toast.LENGTH_SHORT).show()
            }
        }
    )

    // --- Dialog untuk memilih sumber gambar ---
    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = { Text("Pilih Sumber Gambar") },
            text = { Text("Ambil gambar dari kamera atau galeri?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImageSourceDialog = false
                        // Minta izin kamera
                        permissionLauncher.launch(Manifest.permission.CAMERA)

                        // Buat file sementara untuk kamera
                        val file = File.createTempFile("imfit_profile_", ".jpg", context.cacheDir)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                        tempImageUri.value = uri
                        cameraLauncher.launch(uri)
                    }
                ) { Text("Kamera") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImageSourceDialog = false
                        // Minta izin galeri (sesuai versi Android)
                        val permission =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Manifest.permission.READ_MEDIA_IMAGES
                            } else {
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            }
                        permissionLauncher.launch(permission)
                        galleryLauncher.launch("image/*")
                    }
                ) { Text("Galeri") }
            }
        )
    }

    LaunchedEffect(key1 = state.registerSuccess) {
        if (state.registerSuccess) {
            Toast.makeText(context, "Registrasi Berhasil! Silakan Masuk.", Toast.LENGTH_LONG).show()
            onRegisterSuccess()
        }
    }

    AuthScreenLayout(title = stringResource(id = R.string.register_title)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {


            // Placeholder untuk Upload Foto Profil (sama seperti sebelumnya)
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { showImageSourceDialog = true },
                contentAlignment = Alignment.Center
            ) {
                if (state.profileImageUri != null) {
                    AsyncImage(
                        model = state.profileImageUri,
                        contentDescription = "Foto Profil",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Profile Picture Placeholder",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = stringResource(R.string.register_profile_picture_placeholder),
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            AuthTextField(
                value = state.fullname,
                onValueChange = { viewModel.onEvent(RegisterEvent.FullnameChanged(it)) },
                label = stringResource(id = R.string.label_fullname),
                modifier = Modifier.fillMaxWidth(),
                isError = state.fullnameError != null,
                errorMessage = state.fullnameError
            )
            Spacer(modifier = Modifier.height(16.dp))

            AuthTextField(
                value = state.username,
                onValueChange = { viewModel.onEvent(RegisterEvent.UsernameChanged(it)) },
                label = stringResource(id = R.string.label_username),
                modifier = Modifier.fillMaxWidth(),
                isError = state.usernameError != null,
                errorMessage = state.usernameError
            )
            Spacer(modifier = Modifier.height(16.dp))

            AuthTextField(
                value = state.email,
                onValueChange = { viewModel.onEvent(RegisterEvent.EmailChanged(it)) },
                label = stringResource(id = R.string.label_email),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                modifier = Modifier.fillMaxWidth(),
                isError = state.emailError != null,
                errorMessage = state.emailError
            )
            Spacer(modifier = Modifier.height(16.dp))

            PasswordTextField(
                value = state.password,
                onValueChange = { viewModel.onEvent(RegisterEvent.PasswordChanged(it)) },
                label = stringResource(id = R.string.label_password),
                modifier = Modifier.fillMaxWidth(),
                isError = state.passwordError != null,
                errorMessage = state.passwordError
            )
            Spacer(modifier = Modifier.height(16.dp))

            DatePickerField(
                value = state.dateOfBirth,
                onValueChange = { viewModel.onEvent(RegisterEvent.DateOfBirthChanged(it)) },
                label = stringResource(id = R.string.label_date_of_birth),
                modifier = Modifier.fillMaxWidth(),
                isError = state.dateOfBirthError != null,
                errorMessage = state.dateOfBirthError
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (state.isLoading) {
                CircularProgressIndicator()
            } else {
                PrimaryButton(
                    text = stringResource(id = R.string.register_button),
                    onClick = { viewModel.onEvent(RegisterEvent.RegisterButtonPressed) },
                    enabled = !state.isLoading
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            AuthRedirectText(
                promptText = stringResource(id = R.string.register_redirect_prompt),
                clickableText = stringResource(id = R.string.register_redirect_action),
                onClick = onNavigateToLogin,
                modifier = Modifier.padding(top = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
            
        }
    }
}