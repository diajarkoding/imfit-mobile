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
import androidx.compose.runtime.saveable.rememberSaveable
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
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight

private const val TAG = "IMFITProfilePhoto"

/**
 * A reusable profile photo component that displays the user's profile image
 * with a fallback placeholder when the image is null or fails to load.
 * Uses Coil with aggressive disk/memory caching to avoid reloading on navigation.
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
    
    // Extract stable cache key from URL (removes query parameters like token/expiry)
    val stableCacheKey = remember(profilePhotoUri) {
        extractStableCacheKey(profilePhotoUri)
    }
    
    // Use saveable to persist loading state across navigation
    var hasLoadedSuccessfully by rememberSaveable(stableCacheKey) { mutableStateOf(false) }
    var isLoading by remember(stableCacheKey) { mutableStateOf(!hasLoadedSuccessfully && profilePhotoUri != null) }
    var showPlaceholder by remember(stableCacheKey) { mutableStateOf(profilePhotoUri == null) }
    
    // Check if URL is valid and loadable
    val isValidUrl = remember(profilePhotoUri) {
        isValidImageUrl(profilePhotoUri)
    }
    
    // Only log once when URL actually changes to a new value
    LaunchedEffect(stableCacheKey) {
        if (stableCacheKey != null && !hasLoadedSuccessfully) {
            Log.d(TAG, "Loading profile photo: $stableCacheKey")
        }
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
                    // Aggressive caching - use memory and disk cache
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    // Use stable cache key (without query params like token/expiry)
                    .diskCacheKey(stableCacheKey)
                    .memoryCacheKey(stableCacheKey)
                    .build(),
                contentDescription = stringResource(R.string.desc_profile_photo),
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Loading -> {
                            // Only show loading if not already loaded
                            if (!hasLoadedSuccessfully) {
                                isLoading = true
                                showPlaceholder = false
                            }
                        }
                        is AsyncImagePainter.State.Success -> {
                            isLoading = false
                            showPlaceholder = false
                            hasLoadedSuccessfully = true
                        }
                        is AsyncImagePainter.State.Error -> {
                            isLoading = false
                            showPlaceholder = true
                            Log.e(TAG, "Failed to load image: ${state.result.throwable.message}")
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
        if (isLoading && !hasLoadedSuccessfully && !profilePhotoUri.isNullOrBlank()) {
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
 * Extracts a stable cache key from the URL by removing query parameters.
 * This ensures the same image file uses the same cache regardless of token expiry.
 */
private fun extractStableCacheKey(url: String?): String? {
    if (url.isNullOrBlank()) return null
    
    // For Supabase signed URLs, extract the path without query parameters
    return try {
        val uri = android.net.Uri.parse(url)
        // Use host + path as stable key (ignores query parameters like token/expiry)
        "${uri.host}${uri.path}"
    } catch (e: Exception) {
        url
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

