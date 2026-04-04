package dev.c0redev.pteraandroid.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.c0redev.pteraandroid.ui.screens.CloudScreen
import dev.c0redev.pteraandroid.ui.screens.ConfigsScreen
import dev.c0redev.pteraandroid.ui.screens.HomeScreen
import dev.c0redev.pteraandroid.ui.screens.LogsScreen
import dev.c0redev.pteraandroid.ui.screens.ProtectionScreen
import dev.c0redev.pteraandroid.ui.screens.SettingsScreen

private data class NavItem(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()
    val vm: ConnectionViewModel = viewModel()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val vpnPrep by vm.vpnPermissionIntent.collectAsState()
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.confirmVpnPermission() else vm.cancelPendingVpnConnect()
    }

    LaunchedEffect(vpnPrep) {
        val intent = vpnPrep ?: return@LaunchedEffect
        vpnLauncher.launch(intent)
        vm.consumeVpnPermissionIntent()
    }

    val items = listOf(
        NavItem("home", "Home") { Icon(Icons.Outlined.Home, null) },
        NavItem("configs", "Configs") { Icon(Icons.Outlined.Dns, null) },
        NavItem("cloud", "Cloud") { Icon(Icons.Outlined.Cloud, null) },
        NavItem("logs", "Logs") { Icon(Icons.AutoMirrored.Outlined.ReceiptLong, null) },
        NavItem("protection", "Protect") { Icon(Icons.Outlined.Security, null) },
        NavItem("settings", "Settings") { Icon(Icons.Outlined.Settings, null) },
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        onClick = {
                            nav.navigate(item.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = item.icon,
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier) {
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
            composable("cloud") { CloudScreen(vm, padding) }
            composable("logs") { LogsScreen(vm, padding) }
            composable("protection") { ProtectionScreen(vm, padding) }
            composable("settings") { SettingsScreen(vm, padding) }
        }
    }
}
