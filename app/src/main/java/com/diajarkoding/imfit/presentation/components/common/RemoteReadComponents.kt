package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diajarkoding.imfit.R
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteRefreshBox(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier, content = content)
}

@Composable
fun RemoteReadFailure(stale: Boolean, onRetry: () -> Unit) {
    RoutineFailure(stringResource(if (stale) R.string.remote_read_stale else R.string.redesign_error), onRetry)
}

@Composable
fun RemotePageFooter(loading: Boolean, error: Boolean, end: Boolean, onRetry: () -> Unit, onMore: () -> Unit) {
    when {
        loading -> CircularProgressIndicator()
        error -> TextButton(onRetry) { Text(stringResource(R.string.redesign_retry)) }
        !end -> TextButton(onMore) { Text(stringResource(R.string.remote_read_more)) }
    }
}

/** Viewport access hints only; no repository/network calls from the composable. */
@Composable
fun RemotePagingViewport(list: LazyListState, ids: List<String>, onAccess: (String) -> Unit) {
    val callback by rememberUpdatedState(onAccess)
    LaunchedEffect(list, ids) {
        if (ids.isNotEmpty()) snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged().collect { index -> index?.let { callback(ids[it.coerceIn(ids.indices)]) } }
    }
}
