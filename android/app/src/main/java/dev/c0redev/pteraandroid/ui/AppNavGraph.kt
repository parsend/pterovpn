package dev.c0redev.pteraandroid.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.c0redev.pteraandroid.R
import dev.c0redev.pteraandroid.ui.screens.ConfigsScreen
import dev.c0redev.pteraandroid.ui.screens.HomeScreen
import dev.c0redev.pteraandroid.ui.screens.LogsScreen
import dev.c0redev.pteraandroid.ui.screens.ProtectionScreen
import dev.c0redev.pteraandroid.ui.screens.SettingsScreen

private data class NavItem(
    val route: String,
    val labelRes: Int,
    val icon: @Composable (cd: String) -> Unit,
)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AppNavGraph(vm: ConnectionViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val vpnPrep by vm.vpnPermissionIntent.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val activity = context as Activity
    val windowSizeClass = calculateWindowSizeClass(activity = activity)
    val useRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.confirmVpnPermission() else vm.cancelPendingVpnConnect()
        vm.consumeVpnPermissionIntent()
    }

    LaunchedEffect(vpnPrep) {
        val intent = vpnPrep ?: return@LaunchedEffect
        vpnLauncher.launch(intent)
    }

    LaunchedEffect(Unit) {
        vm.uiMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        vm.navToQuickTilesSettings.collect {
            nav.navigate("settings") {
                popUpTo(nav.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val navItems = listOf(
        NavItem("home", R.string.nav_home) { cd -> Icon(Icons.Outlined.Home, cd) },
        NavItem("configs", R.string.nav_configs) { cd -> Icon(Icons.Outlined.Dns, cd) },
        NavItem("logs", R.string.nav_logs) { cd -> Icon(Icons.AutoMirrored.Outlined.ReceiptLong, cd) },
        NavItem("protection", R.string.nav_protection) { cd -> Icon(Icons.Outlined.Security, cd) },
        NavItem("settings", R.string.nav_settings) { cd -> Icon(Icons.Outlined.Settings, cd) },
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!useRail) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    navItems.forEach { item ->
                        val cd = stringResource(R.string.common_cd_nav, stringResource(item.labelRes))
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { item.icon(cd) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize()) {
            if (useRail) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    navItems.forEach { item ->
                        val cd = stringResource(R.string.common_cd_nav, stringResource(item.labelRes))
                        NavigationRailItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                nav.navigate(item.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { item.icon(cd) },
                            label = { Text(stringResource(item.labelRes)) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable("home") {
                        HomeScreen(vm, padding) { route ->
                            nav.navigate(route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                    composable("configs") { ConfigsScreen(vm, padding) }
                    composable("logs") { LogsScreen(vm, padding) }
                    composable("protection") { ProtectionScreen(vm, padding) }
                    composable("settings") { SettingsScreen(vm, padding) }
                }
            }
        }
    }
}
