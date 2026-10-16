package com.diajarkoding.imfit.presentation.ui.auth

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
internal fun AuthProfilePhotoField(photo: Uri?, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = IMFITColors.training
    Column(modifier, verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AuthFieldLabel(stringResource(R.string.auth_profile_photo))
            Text(stringResource(R.string.auth_optional), color = colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (photo == null) {
            AuthBorderedRow(Modifier.heightIn(min = AuthTokens.photoRowHeight), enabled = enabled, onClick = onClick) {
                Box(Modifier.size(40.dp).background(colors.primaryContainer, AuthControlShape), contentAlignment = Alignment.Center) {
                    Icon(Symbols.Default.CameraAlt, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)) {
                    Text(stringResource(R.string.auth_add_photo), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.auth_photo_helper), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.muted, modifier = Modifier.size(20.dp))
            }
        } else {
            Surface(shape = AuthControlShape, color = colors.surface, border = BorderStroke(1.dp, AuthTokens.outline)) {
                Column(Modifier.fillMaxWidth().padding(IMFITSpacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(photo, stringResource(R.string.auth_profile_photo), contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(6.dp)))
                    TextButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.auth_change_photo), color = colors.primary)
                        Spacer(Modifier.width(6.dp))
                        Icon(Symbols.Default.Edit, null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    }
                    Text(stringResource(R.string.auth_photo_selected), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
