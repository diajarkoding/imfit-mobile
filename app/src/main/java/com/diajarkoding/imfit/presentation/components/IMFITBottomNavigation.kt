package com.diajarkoding.imfit.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.diajarkoding.imfit.presentation.navigation.BottomNavItem
import com.diajarkoding.imfit.theme.IMFITBottomNavDimensions
import com.diajarkoding.imfit.theme.IMFITTypography
import com.diajarkoding.imfit.theme.customColors


@Composable
fun IMFITBottomNavigation(
    items: List<BottomNavItem>,
    selectedItem: String?,
    onItemClick: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    // Gunakan Column sebagai pembungkus utama
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(IMFITBottomNavDimensions.height),
//                .shadow(
//                    elevation = IMFITElevation.bottomNav,
//                    spotColor = MaterialTheme.customColors.bottomNavShadow
//                ),
            color = MaterialTheme.customColors.bottomNavBackground,
            contentColor = MaterialTheme.customColors.textPrimary
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items.forEach { item ->
                    IMFITBottomNavigationItem(
                        item = item,
                        selected = selectedItem == item.route,
                        onClick = { onItemClick(item) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        // TAMBAHKAN SPACER INI
        // Spacer ini akan mendorong Surface di atasnya agar tidak tertimpa
        // oleh bilah navigasi sistem.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
        )
    }
}

@Composable
private fun IMFITBottomNavigationItem(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconColor = if (selected) {
        MaterialTheme.customColors.bottomNavActive
    } else {
        MaterialTheme.customColors.bottomNavInactive
    }

    val textColor = if (selected) {
        MaterialTheme.customColors.bottomNavActive
    } else {
        MaterialTheme.customColors.bottomNavInactive
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(
                horizontal = IMFITBottomNavDimensions.horizontalPadding,
                vertical = IMFITBottomNavDimensions.verticalPadding
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.titleResId),
            tint = iconColor,
            modifier = Modifier.size(IMFITBottomNavDimensions.iconSize)
        )

        Spacer(modifier = Modifier.height(IMFITBottomNavDimensions.iconTextSpacing))

        Text(
            text = stringResource(item.titleResId),
            style = IMFITTypography.bottomNavLabel,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}