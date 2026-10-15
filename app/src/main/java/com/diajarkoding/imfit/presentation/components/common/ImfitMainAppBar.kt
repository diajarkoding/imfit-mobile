package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITComponentSizes
import com.diajarkoding.imfit.theme.IMFITCornerRadius
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.IMFITTypography

/**
 * Shared top app bar for the main tabs (Home, Exercise, Progress).
 * Extracted from the Home app bar so all three tabs share logo size,
 * title typography, spacing, colors and the profile action.
 *
 * @param title Tab title, e.g. IMFIT, Gerakan, or Progres.
 * @param profilePhotoUri Signed photo URL; null shows the person fallback.
 * @param onProfileClick Null hides the profile action (Progress tab).
 */
@Composable
fun ImfitMainAppBar(
    title: String,
    modifier: Modifier = Modifier,
    profilePhotoUri: String? = null,
    onProfileClick: (() -> Unit)? = null
) {
    val colors = IMFITColors.training
    val profileLabel = stringResource(R.string.redesign_profile)
    Row(
        modifier.fillMaxWidth().heightIn(min = IMFITComponentSizes.homeHeader)
            .padding(horizontal = IMFITSpacing.screenHorizontal, vertical = IMFITSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.controlGap)
    ) {
        Box(
            Modifier.size(IMFITComponentSizes.brandTile).clip(RoundedCornerShape(IMFITCornerRadius.small))
                .background(colors.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Symbols.Default.FitnessCenter, null, Modifier.size(IMFITComponentSizes.brandIcon), tint = colors.onPrimary)
        }
        Text(title, Modifier.weight(1f), color = colors.text, style = IMFITTypography.brand)
        if (onProfileClick != null) {
            FilledTonalIconButton(
                onClick = onProfileClick,
                modifier = Modifier.size(IMFITSizes.minimumInteractiveTarget).semantics { contentDescription = profileLabel },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer
                )
            ) {
                if (profilePhotoUri.isNullOrBlank()) {
                    Icon(Symbols.Default.Person, null, Modifier.size(IMFITComponentSizes.brandIcon))
                } else {
                    IMFITProfilePhoto(profilePhotoUri, size = IMFITSizes.avatarSm)
                }
            }
        }
    }
}
