package com.diajarkoding.imfit.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.diajarkoding.imfit.theme.IMFITTypography
import com.diajarkoding.imfit.theme.customColors

@Composable
fun TwoToneTitle(
    text: String,
    highlightColor: Color = MaterialTheme.colorScheme.primary
) {
    val annotatedString = buildAnnotatedString {
        if (text.equals("IMFit", ignoreCase = true)) {
            // Kasus khusus untuk "IMFit"
            append("IM")
            withStyle(style = SpanStyle(color = highlightColor)) {
                append("Fi")
            }
            append("t")
        } else if (text.length > 3) {
            // Kasus umum: warnai 2 huruf sebelum huruf terakhir
            val highlightStartIndex = text.length - 3 // Index awal dari 2 huruf yang akan diwarnai
            val highlightEndIndex = text.length - 1   // Index akhir

            // 1. Tambahkan teks sebelum bagian yang diwarnai
            append(text.substring(0, highlightStartIndex))

            // 2. Tambahkan 2 huruf yang diwarnai
            withStyle(style = SpanStyle(color = highlightColor)) {
                append(text.substring(highlightStartIndex, highlightEndIndex))
            }

            // 3. Tambahkan huruf terakhir
            append(text.substring(highlightEndIndex))

        } else {
            // Jika teks terlalu pendek, tampilkan biasa
            append(text)
        }
    }

    Text(
        text = annotatedString,
        style = IMFITTypography.appBarTitle,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.customColors.textPrimary
    )
}