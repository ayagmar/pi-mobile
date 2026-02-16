package com.ayagmar.pimobile.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.ayagmar.pimobile.ui.settings.KEY_SHOW_EXTENSION_STATUS_STRIP
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
private fun DrawerDestinationItem(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val itemShape = RoundedCornerShape(14.dp)
    val itemColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            animationSpec = tween(durationMillis = 180),
            label = "drawer_item_color",
        )
    val dotColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            animationSpec = tween(durationMillis = 180),
            label = "drawer_dot_color",
        )
    val dotSize by
        animateDpAsState(
            targetValue = if (selected) 8.dp else 6.dp,
            animationSpec = tween(durationMillis = 180),
            label = "drawer_dot_size",
        )

    Surface(
        shape = itemShape,
        color = itemColor,
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        NavigationDrawerItem(
            selected = selected,
            onClick = onClick,
            label = {
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            icon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(dotSize)
                                .background(
                                    color = dotColor,
                                    shape = CircleShape,
                                ),
                    )
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = destination.label,
                    )
                }
            },
            shape = itemShape,
            colors =
                NavigationDrawerItemDefaults.colors(
                    selectedContainerColor = Color.Transparent,
                    unselectedContainerColor = Color.Transparent,
                ),
        )
    }
}

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
    var showExtensionStatusStrip by remember(settingsPrefs) {
        mutableStateOf(settingsPrefs.getBoolean(KEY_SHOW_EXTENSION_STATUS_STRIP, true))
    }

    DisposableEffect(settingsPrefs) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                when (key) {
                    KEY_THEME_PREFERENCE -> {
                        themePreference = ThemePreference.fromValue(prefs.getString(KEY_THEME_PREFERENCE, null))
                    }

                    KEY_SHOW_EXTENSION_STATUS_STRIP -> {
                        showExtensionStatusStrip = prefs.getBoolean(KEY_SHOW_EXTENSION_STATUS_STRIP, true)
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
            scrimColor = Color.Transparent,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.widthIn(min = 220.dp, max = 270.dp),
                    drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Navigation",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "Slides from the left. Tap outside to close.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        HorizontalDivider()

                        Text(
                            text = "WORKSPACE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )

                        destinations.forEach { destination ->
                            DrawerDestinationItem(
                                destination = destination,
                                selected = currentRoute == destination.route,
                                onClick = {
                                    navigateTo(destination.route)
                                    scope.launch { drawerState.close() }
                                },
                            )
                        }
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
                            ChatRoute(
                                sessionController = appGraph.sessionController,
                                showExtensionStatusStrip = showExtensionStatusStrip,
                            )
                        }
                        composable(route = "settings") {
                            SettingsRoute(sessionController = appGraph.sessionController)
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        tonalElevation = 5.dp,
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .statusBarsPadding()
                                .offset(x = (-6).dp, y = 2.dp),
                    ) {
                        FilledTonalIconButton(
                            modifier = Modifier.size(42.dp),
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
