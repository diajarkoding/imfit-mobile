package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight

/**
 * A reusable profile photo component that displays the user's profile image
 * with a fallback placeholder when the image is null or fails to load.
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
    var showPlaceholder by remember { mutableStateOf(profilePhotoUri == null) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (showPlaceholder) {
                    Brush.linearGradient(listOf(Primary, PrimaryLight))
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (profilePhotoUri != null && !showPlaceholder) {
            AsyncImage(
                model = profilePhotoUri,
                contentDescription = stringResource(R.string.desc_profile_photo),
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    showPlaceholder = when (state) {
                        is AsyncImagePainter.State.Error -> true
                        is AsyncImagePainter.State.Empty -> true
                        else -> false
                    }
                }
            )
        }

        // Show placeholder if no URI or if image failed to load
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
