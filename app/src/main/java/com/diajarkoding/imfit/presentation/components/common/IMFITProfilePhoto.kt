package com.diajarkoding.imfit.presentation.components.common

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight

private const val TAG = "IMFITProfilePhoto"

/**
 * A reusable profile photo component that displays the user's profile image
 * with a fallback placeholder when the image is null or fails to load.
 * Uses Coil with disk caching for efficient loading.
 *
 * @param profilePhotoUri The URI of the profile photo, or null to show placeholder
 * @param size The size of the profile photo
 * @param modifier Optional modifier for additional styling
 */
@Composable
fun IMFITProfilePhoto(
    profilePhotoUri: String?,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Log URL info for debugging
    LaunchedEffect(profilePhotoUri) {
        logUrlInfo(profilePhotoUri, context)
    }
    
    // State to track loading status
    var isLoading by remember(profilePhotoUri) { mutableStateOf(profilePhotoUri != null) }
    var showPlaceholder by remember(profilePhotoUri) { mutableStateOf(profilePhotoUri == null) }
    
    // Check if URL is valid and loadable
    val isValidUrl = remember(profilePhotoUri) {
        isValidImageUrl(profilePhotoUri)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (showPlaceholder || isLoading) {
                    Brush.linearGradient(listOf(Primary, PrimaryLight))
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Load image only if URL is valid
        if (!profilePhotoUri.isNullOrBlank() && isValidUrl) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(profilePhotoUri)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.desc_profile_photo),
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Loading -> {
                            isLoading = true
                            showPlaceholder = false
                        }
                        is AsyncImagePainter.State.Success -> {
                            isLoading = false
                            showPlaceholder = false
                            Log.d(TAG, "✅ Image loaded successfully from: ${getUrlType(profilePhotoUri)}")
                        }
                        is AsyncImagePainter.State.Error -> {
                            isLoading = false
                            showPlaceholder = true
                            Log.e(TAG, "❌ Failed to load image: ${state.result.throwable.message}")
                            Log.e(TAG, "   URL: $profilePhotoUri")
                            Log.e(TAG, "   URL Type: ${getUrlType(profilePhotoUri)}")
                        }
                        is AsyncImagePainter.State.Empty -> {
                            isLoading = false
                            showPlaceholder = true
                        }
                    }
                }
            )
        } else if (!profilePhotoUri.isNullOrBlank() && !isValidUrl) {
            // Invalid URL, show placeholder
            showPlaceholder = true
            isLoading = false
        }

        // Show loading indicator
        if (isLoading && !profilePhotoUri.isNullOrBlank()) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.3f),
                color = Color.White,
                strokeWidth = 2.dp
            )
        }

        // Show placeholder if no URI, loading, or if image failed to load
        if (showPlaceholder) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

/**
 * Logs detailed information about the profile photo URL
 */
private fun logUrlInfo(url: String?, context: android.content.Context) {
    Log.d(TAG, "========== Profile Photo URL Info ==========")
    
    if (url.isNullOrBlank()) {
        Log.d(TAG, "URL: null or blank - showing placeholder")
        return
    }
    
    Log.d(TAG, "URL: $url")
    Log.d(TAG, "URL Type: ${getUrlType(url)}")
    Log.d(TAG, "Is Valid: ${isValidImageUrl(url)}")
    
    // Log cache info
    val imageLoader = context.imageLoader
    val diskCache = imageLoader.diskCache
    val memoryCache = imageLoader.memoryCache
    
    Log.d(TAG, "Cache Info:")
    Log.d(TAG, "  - Disk cache size: ${diskCache?.size ?: 0} / ${diskCache?.maxSize ?: 0} bytes")
    Log.d(TAG, "  - Memory cache size: ${memoryCache?.size ?: 0} / ${memoryCache?.maxSize ?: 0} bytes")
    
    // Warn about local URIs
    if (url.startsWith("content://") || url.startsWith("file://")) {
        Log.w(TAG, "⚠️ WARNING: Using local URI! This file may not persist across app reinstalls.")
        Log.w(TAG, "   Consider uploading to remote storage (Supabase Storage) and use https:// URL.")
    }
    
    Log.d(TAG, "=============================================")
}

/**
 * Determines the type of URL for logging purposes
 */
private fun getUrlType(url: String?): String {
    return when {
        url == null -> "null"
        url.startsWith("content://") -> "LOCAL_CONTENT_PROVIDER"
        url.startsWith("file://") -> "LOCAL_FILE"
        url.startsWith("https://") -> "REMOTE_HTTPS"
        url.startsWith("http://") -> "REMOTE_HTTP"
        url.startsWith("data:") -> "DATA_URI"
        else -> "UNKNOWN"
    }
}

/**
 * Validates if a URL is a valid and loadable image URL
 */
private fun isValidImageUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    
    // Accept remote URLs
    if (url.startsWith("https://") || url.startsWith("http://")) {
        return true
    }
    
    // Accept content and file URIs (though they may fail if file doesn't exist)
    if (url.startsWith("content://") || url.startsWith("file://")) {
        return true
    }
    
    // Accept data URIs
    if (url.startsWith("data:")) {
        return true
    }
    
    return false
}

