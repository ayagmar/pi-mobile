package com.ayagmar.pimobile.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

private data class AppDestination(
    val route: String,
    val label: String,
)

private val destinations =
    listOf(
        AppDestination(
            route = "hosts",
            label = "Hosts",
        ),
        AppDestination(
            route = "sessions",
            label = "Sessions",
        ),
        AppDestination(
            route = "chat",
            label = "Chat",
        ),
        AppDestination(
            route = "settings",
            label = "Settings",
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

        Scaffold(
            bottomBar = {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    launchSingleTop = true
                                    restoreState = true
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                }
                            },
                            icon = { Text(destination.label.take(1)) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            },
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = "sessions",
                modifier = Modifier.padding(paddingValues),
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
                            navController.navigate("chat") {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                            }
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
