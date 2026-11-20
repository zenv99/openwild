package org.openwild.app.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Scrolls this node into view when it or a child field is focused, and again once the
 * keyboard inset has been applied. Put it on the field, or on a column that also holds
 * the button under that field, inside a scrollable parent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bringIntoViewOnFocus(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus, imeBottom) {
        if (!hasFocus) return@LaunchedEffect
        withFrameMillis { }
        requester.bringIntoView()
    }
    return this.bringIntoViewRequester(requester).onFocusChanged { hasFocus = it.hasFocus }
}

/**
 * Alert dialog whose card stays in the space above the keyboard.
 * The dialog window does not fit system windows, so the IME inset is reported, and the
 * card is centered in what the system bars and the keyboard leave free. The message
 * slot keeps its own scroll and shrinks when that space is short.
 */
@Composable
fun ImeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // API 30+ reports the keyboard as an inset when the window does not resize.
            // That is the same mode a ModalBottomSheet uses, so ime padding can lift the card.
            val mode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            window.setSoftInputMode(mode)
            androidx.core.view.ViewCompat.requestApplyInsets(window.decorView)
        }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Title and buttons stay on screen. The message uses whatever height is left.
            val textMax = (maxHeight - 200.dp).coerceAtLeast(96.dp)
            Surface(
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(
                    Modifier.widthIn(min = 280.dp, max = 560.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    title?.let {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) { it() }
                        }
                    }
                    text?.let {
                        Box(Modifier.heightIn(max = textMax).fillMaxWidth()) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) { it() }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
