package com.ayagmar.pimobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
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
import com.ayagmar.pimobile.ui.settings.KEY_NAV_RAIL_EXPANDED
import com.ayagmar.pimobile.ui.settings.KEY_THEME_PREFERENCE
import com.ayagmar.pimobile.ui.settings.SETTINGS_PREFS_NAME
import com.ayagmar.pimobile.ui.settings.SettingsRoute
import com.ayagmar.pimobile.ui.theme.PiMobileTheme
import com.ayagmar.pimobile.ui.theme.ThemePreference

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
    var isNavExpanded by remember(settingsPrefs) {
        mutableStateOf(settingsPrefs.getBoolean(KEY_NAV_RAIL_EXPANDED, false))
    }

    DisposableEffect(settingsPrefs) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                when (key) {
                    KEY_THEME_PREFERENCE -> {
                        themePreference = ThemePreference.fromValue(prefs.getString(KEY_THEME_PREFERENCE, null))
                    }

                    KEY_NAV_RAIL_EXPANDED -> {
                        isNavExpanded = prefs.getBoolean(KEY_NAV_RAIL_EXPANDED, false)
                    }
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

        fun navigateTo(route: String) {
            navController.navigate(route) {
                launchSingleTop = true
                restoreState = true
                popUpTo(navController.graph.startDestinationId) {
                    saveState = true
                }
            }
        }

        Scaffold { paddingValues ->
            Row(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            ) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight().widthIn(min = if (isNavExpanded) 112.dp else 72.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = {
                                val nextValue = !isNavExpanded
                                isNavExpanded = nextValue
                                settingsPrefs.edit().putBoolean(KEY_NAV_RAIL_EXPANDED, nextValue).apply()
                            },
                        ) {
                            Icon(
                                imageVector = if (isNavExpanded) Icons.Default.MenuOpen else Icons.Default.Menu,
                                contentDescription = if (isNavExpanded) "Collapse navigation" else "Expand navigation",
                            )
                        }

                        destinations.forEach { destination ->
                            NavigationRailItem(
                                selected = currentRoute == destination.route,
                                onClick = { navigateTo(destination.route) },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label =
                                    if (isNavExpanded) {
                                        { Text(destination.label) }
                                    } else {
                                        null
                                    },
                                alwaysShowLabel = isNavExpanded,
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "sessions",
                    modifier = Modifier.weight(1f),
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
            }
        }
    }
}
