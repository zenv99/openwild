@file:OptIn(ExperimentalMaterial3Api::class)

package org.openwild.app.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openwild.app.R
import org.openwild.app.ui.screens.AboutScreen
import org.openwild.app.ui.screens.RegionChoiceScreen
import org.openwild.app.ui.screens.CaptureScreen
import org.openwild.app.ui.screens.CreditsScreen
import org.openwild.app.ui.screens.ExploreScreen
import org.openwild.app.ui.screens.HomeScreen
import org.openwild.app.ui.screens.PacksScreen
import org.openwild.app.ui.screens.ResultsScreen
import org.openwild.app.ui.screens.SpeciesScreen
import org.openwild.app.ui.screens.SurvivalScreen
import org.openwild.app.ui.theme.OpenWildTheme

@Composable
fun OpenWildRoot(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val dark = when (state.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    OpenWildTheme(darkTheme = dark) { RootBody(state, vm) }
}

@Composable
private fun RootBody(state: UiState, vm: AppViewModel) {

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

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    if (!state.bootstrapped) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.activeRegionName == null) {
        Box(Modifier.fillMaxSize()) {
            RegionChoiceScreen(state, vm)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    BackHandler(enabled = state.stack.size > 1) { vm.back() }

    val screen = state.screen
    val isTopLevel = screen in TopLevel
    val title = when (screen) {
        Screen.Home -> ""
        Screen.Capture -> stringResource(R.string.capture_title)
        Screen.Results -> stringResource(R.string.results)
        is Screen.Species -> vm.pack?.species?.scientificNames?.getOrNull(screen.index) ?: ""
        Screen.Packs -> stringResource(R.string.region_packs)
        Screen.Survival -> stringResource(R.string.survival_guide)
        Screen.Credits -> stringResource(R.string.credits)
        Screen.Explore -> stringResource(R.string.explore)
        Screen.About -> stringResource(R.string.nav_about)
    }

    Scaffold(
        topBar = {
            if (screen != Screen.Home) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            title,
                            fontStyle = if (screen is Screen.Species) FontStyle.Italic else FontStyle.Normal,
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        if (!isTopLevel) {
                            IconButton(onClick = { vm.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = screen == Screen.Home, onClick = { vm.tab(Screen.Home) },
                        icon = { Icon(Icons.Filled.Home, null) }, label = { Text(stringResource(R.string.nav_identify)) },
                    )
                    NavigationBarItem(
                        selected = screen == Screen.Explore, onClick = { vm.tab(Screen.Explore) },
                        icon = { Icon(Icons.Filled.Explore, null) }, label = { Text(stringResource(R.string.explore)) },
                    )
                    NavigationBarItem(
                        selected = screen == Screen.Packs, onClick = { vm.tab(Screen.Packs) },
                        icon = { Icon(Icons.Filled.CloudDownload, null) }, label = { Text(stringResource(R.string.nav_packs)) },
                    )
                    NavigationBarItem(
                        selected = screen == Screen.About, onClick = { vm.tab(Screen.About) },
                        icon = { Icon(Icons.Filled.Favorite, null) }, label = { Text(stringResource(R.string.nav_about)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { s ->
                when (s) {
                    Screen.Home -> HomeScreen(state, vm)
                    Screen.Capture -> CaptureScreen(state, vm)
                    Screen.Results -> ResultsScreen(state, vm)
                    is Screen.Species -> SpeciesScreen(s.index, s.tier, state, vm)
                    Screen.Packs -> PacksScreen(state, vm)
                    Screen.Survival -> SurvivalScreen(vm)
                    Screen.Credits -> CreditsScreen(vm)
                    Screen.Explore -> ExploreScreen(state, vm)
                    Screen.About -> AboutScreen(state, vm)
                }
            }
        }
    }
}
