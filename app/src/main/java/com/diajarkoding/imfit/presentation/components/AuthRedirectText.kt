package com.diajarkoding.imfit.presentation.components

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

@Composable
fun AuthRedirectText(
    promptText: String,
    clickableText: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val annotatedString = buildAnnotatedString {
        append("$promptText ")
        withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
            pushStringAnnotation(tag = clickableText, annotation = clickableText)
            append(clickableText)
            pop()
        }
    }

    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = clickableText, start = offset, end = offset)
                .firstOrNull()?.let {
                    onClick()
                }
        },
        modifier = modifier
    )
}