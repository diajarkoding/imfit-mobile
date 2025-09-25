package com.diajarkoding.imfit.presentation.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.IMFITCornerRadius
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.PrimaryBlue
import com.diajarkoding.imfit.theme.customColors

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Gunakan LazyColumn agar seluruh halaman bisa di-scroll
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
    ) {
        // 1. Hero Banner
        item {
            HeroBanner()
        }

        // 2. Progress Section
        item {
            ProgressSection(progressItems = state.progressItems)
        }

        // 3. Categories Section
        item {
            CategoriesSection(categories = state.categories)
        }

        // 4. Exercise List
        item {
            SectionHeader(title = "Latihan Lainnya")
        }
        items(state.exercises) { exercise ->
            ExerciseListItem(
                exercise = exercise,
                modifier = Modifier.padding(horizontal = IMFITSpacing.md)
            )
        }

        item {
            Spacer(modifier = Modifier.height(IMFITSpacing.md))
        }
    }
}

// --- Komponen-komponen untuk HomeScreen ---

@Composable
fun HeroBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp) // Beri ketinggian pada Card agar gambar bisa diatur di bawah
            .padding(horizontal = IMFITSpacing.md),
        shape = RoundedCornerShape(IMFITCornerRadius.large)
    ) {
        // Gunakan Box untuk menumpuk teks dan gambar
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.customColors.gradient)
        ) {
            // Kolom untuk teks dan tombol
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart) // Posisi di kiri tengah
                    .fillMaxWidth(0.70f) // Batasi lebar kolom agar tidak tertimpa gambar
                    .padding(start = 20.dp),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
            ) {
                Text(
                    text = "Mulai dengan Kuat & Tetapkan Tujuan Fitness Anda",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Button(
                    onClick = { /* TODO */ },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(IMFITCornerRadius.medium)
                ) {
                    Text(
                        "Mulai Latihan",
                        color = PrimaryBlue,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Gambar Pelatih
            Image(
                painter = painterResource(id = R.drawable.trainer),
                contentDescription = "Gambar Pelatih Fitness",
                modifier = Modifier
                    .size(130.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-20).dp)
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = IMFITSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        TextButton(onClick = { /* TODO */ }) {
            Text(
                "Lihat Semua",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}


@Composable
fun ProgressSection(progressItems: List<ProgressItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
        SectionHeader(title = "Progres")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm),
            contentPadding = PaddingValues(horizontal = IMFITSpacing.md)
        ) {
            items(progressItems) { item ->
                ProgressCard(item = item)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesSection(categories: List<CategoryItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
        SectionHeader(title = "Kategori")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm),
            contentPadding = PaddingValues(horizontal = IMFITSpacing.md)
        ) {
            items(categories) { category ->
                FilterChip(
                    onClick = { /* TODO */ },
                    label = { Text(category.name) },
                    selected = category.isSelected,
                    shape = RoundedCornerShape(IMFITCornerRadius.large)
                )
            }
        }
    }
}

@Composable
fun ExerciseListItem(exercise: ExerciseItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(IMFITCornerRadius.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.customColors.surfaceElevated)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // TODO: Ganti dengan gambar dari Coil
            Image(
                painter = painterResource(exercise.imageRes),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(IMFITCornerRadius.small))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(text = exercise.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${exercise.exerciseCount} Latihan • ${exercise.duration}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.textSecondary
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.customColors.textTertiary
            )
        }
    }
}

@Composable
fun ProgressCard(item: ProgressItem) {
    Card(
        modifier = Modifier.size(width = 140.dp, height = 160.dp),
        shape = RoundedCornerShape(IMFITCornerRadius.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.customColors.surfaceElevated)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Box(modifier = Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 6.dp
                )
                Text(
                    text = "${item.current}/${item.total}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = item.timeRemaining,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.customColors.textSecondary
            )
        }
    }
}