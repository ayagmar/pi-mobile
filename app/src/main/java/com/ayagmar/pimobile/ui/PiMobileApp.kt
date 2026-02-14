package com.ayagmar.pimobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ayagmar.pimobile.ui.hosts.HostsRoute
import com.ayagmar.pimobile.ui.sessions.SessionsRoute

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
    )

@Composable
fun piMobileApp() {
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
                HostsRoute()
            }
            composable(route = "sessions") {
                SessionsRoute()
            }
            composable(route = "chat") {
                placeholderScreen(title = "Chat")
            }
        }
    }
}

@Composable
private fun placeholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "$title screen placeholder")
    }
}
