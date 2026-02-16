package com.ayagmar.pimobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ayagmar.pimobile.di.AppGraph
import com.ayagmar.pimobile.ui.chat.ChatRoute
import com.ayagmar.pimobile.ui.hosts.HostsRoute
import com.ayagmar.pimobile.ui.sessions.SessionsRoute
import com.ayagmar.pimobile.ui.settings.KEY_THEME_PREFERENCE
import com.ayagmar.pimobile.ui.settings.SETTINGS_PREFS_NAME
import com.ayagmar.pimobile.ui.settings.SettingsRoute
import com.ayagmar.pimobile.ui.theme.PiMobileTheme
import com.ayagmar.pimobile.ui.theme.ThemePreference
import kotlinx.coroutines.launch

private data class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val destinations =
    listOf(
        AppDestination(
            route = "hosts",
            label = "Hosts",
            icon = Icons.Default.Computer,
        ),
        AppDestination(
            route = "sessions",
            label = "Sessions",
            icon = Icons.Default.Storage,
        ),
        AppDestination(
            route = "chat",
            label = "Chat",
            icon = Icons.Default.Chat,
        ),
        AppDestination(
            route = "settings",
            label = "Settings",
            icon = Icons.Default.Settings,
        ),
    )

@Suppress("LongMethod")
@Composable
fun piMobileApp(appGraph: AppGraph) {
    val context = LocalContext.current
    val settingsPrefs =
        remember(context) {
            context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
        }
    var themePreference by remember(settingsPrefs) {
        mutableStateOf(
            ThemePreference.fromValue(
                settingsPrefs.getString(KEY_THEME_PREFERENCE, null),
            ),
        )
    }

    DisposableEffect(settingsPrefs) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == KEY_THEME_PREFERENCE) {
                    themePreference = ThemePreference.fromValue(prefs.getString(KEY_THEME_PREFERENCE, null))
                }
            }
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    PiMobileTheme(themePreference = themePreference) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        fun navigateTo(route: String) {
            navController.navigate(route) {
                launchSingleTop = true
                restoreState = true
                popUpTo(navController.graph.startDestinationId) {
                    saveState = true
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(min = 248.dp, max = 300.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
                        Text(
                            text = "Navigation",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Opens from the left side. Tap outside to close.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))

                    destinations.forEach { destination ->
                        NavigationDrawerItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navigateTo(destination.route)
                                scope.launch { drawerState.close() }
                            },
                            label = { Text(destination.label) },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            },
        ) {
            Scaffold { paddingValues ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "sessions",
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable(route = "hosts") {
                            HostsRoute(
                                profileStore = appGraph.hostProfileStore,
                                tokenStore = appGraph.hostTokenStore,
                                diagnostics = appGraph.connectionDiagnostics,
                            )
                        }
                        composable(route = "sessions") {
                            SessionsRoute(
                                profileStore = appGraph.hostProfileStore,
                                tokenStore = appGraph.hostTokenStore,
                                repository = appGraph.sessionIndexRepository,
                                sessionController = appGraph.sessionController,
                                cwdPreferenceStore = appGraph.sessionCwdPreferenceStore,
                                onNavigateToChat = {
                                    navigateTo("chat")
                                },
                            )
                        }
                        composable(route = "chat") {
                            ChatRoute(sessionController = appGraph.sessionController)
                        }
                        composable(route = "settings") {
                            SettingsRoute(sessionController = appGraph.sessionController)
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        tonalElevation = 4.dp,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                scope.launch {
                                    if (drawerState.isOpen) {
                                        drawerState.close()
                                    } else {
                                        drawerState.open()
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (drawerState.isOpen) Icons.Default.MenuOpen else Icons.Default.Menu,
                                contentDescription =
                                    if (drawerState.isOpen) {
                                        "Close left navigation"
                                    } else {
                                        "Open left navigation"
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
