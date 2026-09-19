package eu.rybnik.events

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.rybnik.events.ui.events.EventDetailScreen
import eu.rybnik.events.ui.events.EventListScreen
import eu.rybnik.events.ui.home.HomeScreen
import eu.rybnik.events.ui.more.AirScreen
import eu.rybnik.events.ui.more.MoreScreen
import eu.rybnik.events.ui.more.SettingsScreen
import eu.rybnik.events.ui.news.NewsScreen
import eu.rybnik.events.ui.transit.TransitScreen
import eu.rybnik.events.ui.waste.AddressPickerScreen
import eu.rybnik.events.ui.waste.WasteScreen

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Tab("home", "Start", Icons.Outlined.Home)
    data object Events : Tab("events", "Wydarzenia", Icons.Outlined.CalendarMonth)
    data object Transit : Tab("transit", "Transport", Icons.Outlined.DirectionsBus)
    data object Waste : Tab("waste", "Śmieci", Icons.Outlined.Delete)
    data object More : Tab("more", "Więcej", Icons.Outlined.MoreHoriz)
}

private val tabs = listOf(Tab.Home, Tab.Events, Tab.Transit, Tab.Waste, Tab.More)

private object Routes {
    const val NEWS = "news"
    const val AIR = "air"
    const val SETTINGS = "settings"
    const val FAVOURITES = "favourites"
    const val ADDRESS = "address"
    const val EVENT_DETAIL = "event/{id}"
    fun event(id: String) = "event/$id"
}

@Composable
fun RybnikApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // The bottom bar is for the five tabs only; detail screens own their whole viewport.
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy?.any {
                            it.route == tab.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected) navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Home.route,
            // Only the bottom bar's space is reserved here. Every screen brings its own
            // Scaffold + TopAppBar, which already consumes the status-bar inset — applying
            // the full innerPadding as well would inset the top twice and leave a gap.
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(Tab.Home.route) {
                HomeScreen(
                    onOpenWaste = { navController.switchTab(Tab.Waste.route) },
                    onOpenTransit = { navController.switchTab(Tab.Transit.route) },
                    onOpenEvents = { navController.switchTab(Tab.Events.route) },
                    onOpenNews = { navController.navigate(Routes.NEWS) },
                    onOpenAir = { navController.navigate(Routes.AIR) },
                    onEventClick = { navController.navigate(Routes.event(it)) },
                )
            }

            composable(Tab.Events.route) {
                EventListScreen(onEventClick = { navController.navigate(Routes.event(it)) })
            }

            composable(Tab.Transit.route) { TransitScreen() }

            composable(Tab.Waste.route) {
                WasteScreen(onPickAddress = { navController.navigate(Routes.ADDRESS) })
            }

            composable(Tab.More.route) {
                MoreScreen(
                    onOpenNews = { navController.navigate(Routes.NEWS) },
                    onOpenAir = { navController.navigate(Routes.AIR) },
                    onOpenFavourites = { navController.navigate(Routes.FAVOURITES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(Routes.NEWS) { NewsScreen() }

            composable(Routes.AIR) { AirScreen(onBack = { navController.popBackStack() }) }

            composable(Routes.FAVOURITES) {
                EventListScreen(
                    onEventClick = { navController.navigate(Routes.event(it)) },
                    favouritesOnly = true,
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onPickAddress = { navController.navigate(Routes.ADDRESS) },
                )
            }

            composable(Routes.ADDRESS) {
                AddressPickerScreen(
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.EVENT_DETAIL) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                EventDetailScreen(eventId = id, onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
