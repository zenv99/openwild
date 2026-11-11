@file:OptIn(ExperimentalMaterial3Api::class)

package org.openwild.app.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openwild.app.R
import org.openwild.app.core.Notice
import org.openwild.app.ui.screens.CaptureScreen
import org.openwild.app.ui.screens.CreditsScreen
import org.openwild.app.ui.screens.HomeScreen
import org.openwild.app.ui.screens.PacksScreen
import org.openwild.app.ui.screens.ResultsScreen
import org.openwild.app.ui.screens.SpeciesScreen
import org.openwild.app.ui.screens.SurvivalScreen
import org.openwild.app.ui.theme.Caution
import org.openwild.app.ui.theme.CautionBg
import org.openwild.app.ui.theme.Danger
import org.openwild.app.ui.theme.DangerBg

@Composable
fun OpenWildRoot(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.detectRegion()
    }
    LaunchedEffect(state.disclaimerAccepted) {
        if (state.disclaimerAccepted) locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    if (!state.disclaimerAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_body)) },
            confirmButton = { Button(onClick = vm::acceptDisclaimer) { Text(stringResource(R.string.disclaimer_accept)) } },
        )
    }

    BackHandler(enabled = state.stack.size > 1) { vm.back() }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val title = when (val s = state.screen) {
        Screen.Home -> stringResource(R.string.app_name)
        Screen.Capture -> stringResource(R.string.capture_title)
        Screen.Results -> stringResource(R.string.results)
        is Screen.Species -> vm.pack?.species?.scientificNames?.getOrNull(s.index) ?: ""
        Screen.Packs -> stringResource(R.string.region_packs)
        Screen.Survival -> stringResource(R.string.survival_guide)
        Screen.Credits -> stringResource(R.string.credits)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (state.stack.size > 1) {
                        IconButton(onClick = { vm.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state.screen) {
                Screen.Home -> HomeScreen(state, vm)
                Screen.Capture -> CaptureScreen(state, vm)
                Screen.Results -> ResultsScreen(state, vm)
                is Screen.Species -> SpeciesScreen(s.index, s.tier, state, vm)
                Screen.Packs -> PacksScreen(state, vm)
                Screen.Survival -> SurvivalScreen(vm)
                Screen.Credits -> CreditsScreen(vm)
            }
        }
    }
}

// ---------- shared components ----------

@Composable
fun ScrollColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun WarningBox(text: String, danger: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (danger) DangerBg else CautionBg, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = if (danger) Danger else Caution)
        Spacer(Modifier.width(10.dp))
        Text(text, color = if (danger) Danger else Color(0xFF4A3000), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun noticeText(n: Notice): String = stringResource(
    when (n) {
        Notice.NOT_CONFIDENT_EATING -> R.string.notice_not_confident_eating
        Notice.NOT_CONFIDENT_DO_NOT_EAT -> R.string.notice_not_confident
        Notice.NOT_IN_LIBRARY -> R.string.notice_not_in_library
        Notice.NO_SAFETY_DATA -> R.string.notice_no_safety_data
        Notice.NEED_UNDERSIDE_PHOTO -> R.string.notice_need_underside
        Notice.MUSHROOM_POOR_SURVIVAL_FOOD -> R.string.notice_mushroom_survival
        Notice.LEGAL_WARNING -> R.string.legal_warning
        Notice.NOT_EXPERT_REVIEWED -> R.string.notice_not_reviewed
    },
)

fun isDangerNotice(n: Notice) = n != Notice.MUSHROOM_POOR_SURVIVAL_FOOD && n != Notice.NOT_EXPERT_REVIEWED

@Composable
fun Footer() {
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.footer),
        style = MaterialTheme.typography.labelLarge,
        color = Danger,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
