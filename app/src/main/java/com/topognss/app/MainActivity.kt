package com.topognss.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.topognss.app.ui.cogo.CogoScreen
import com.topognss.app.ui.components.GnssStatusBar
import com.topognss.app.ui.connection.ConnectionScreen
import com.topognss.app.ui.points.PointsScreen
import com.topognss.app.ui.projects.ProjectsScreen
import com.topognss.app.ui.stakeout.StakeoutScreen
import com.topognss.app.ui.survey.SurveyScreen
import com.topognss.app.ui.theme.TopoTheme
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    CONNECTION("connection", "GNSS", Icons.Filled.Settings),
    SURVEY("survey", "Relevar", Icons.Filled.Add),
    POINTS("points", "Puntos", Icons.Filled.List),
    COGO("cogo", "Cálculo", Icons.Filled.Build),
    STAKEOUT("stakeout", "Replanteo", Icons.Filled.Place),
}

private const val ROUTE_PROJECTS = "projects"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TopoTheme { AppRoot(application as TopoApp) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
private fun AppRoot(app: TopoApp) {
    val nav = rememberNavController()
    val gnss by app.gnss.state.collectAsState()
    val projectFlow = remember {
        app.prefs.activeProjectId.flatMapLatest { id -> if (id > 0) app.repository.observeProject(id) else flowOf(null) }
    }
    val project by projectFlow.collectAsState(initial = null)
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Permisos de tiempo de ejecución necesarios (Bluetooth 12+, ubicación, notificaciones 13+)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(project?.name ?: "TopoGNSS", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    actions = {
                        TextButton(onClick = { nav.navigate(ROUTE_PROJECTS) { launchSingleTop = true } }) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null)
                            Text(" Proyectos")
                        }
                    },
                )
                GnssStatusBar(gnss) { nav.goTab(Dest.CONNECTION) }
            }
        },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = route == d.route,
                        onClick = { nav.goTab(d) },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        val openProjects = { nav.navigate(ROUTE_PROJECTS) { launchSingleTop = true } }
        NavHost(
            navController = nav,
            startDestination = if (app.prefs.activeProjectId.value > 0) Dest.CONNECTION.route else ROUTE_PROJECTS,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Dest.CONNECTION.route) { ConnectionScreen() }
            composable(Dest.SURVEY.route) { SurveyScreen(onOpenProjects = openProjects) }
            composable(Dest.POINTS.route) {
                PointsScreen(onOpenProjects = openProjects, onStake = { nav.goTab(Dest.STAKEOUT) })
            }
            composable(Dest.COGO.route) { CogoScreen(onOpenProjects = openProjects) }
            composable(Dest.STAKEOUT.route) { StakeoutScreen(onOpenProjects = openProjects) }
            composable(ROUTE_PROJECTS) { ProjectsScreen(onDone = { nav.goTab(Dest.SURVEY) }) }
        }
    }
}

private fun NavHostController.goTab(d: Dest) {
    navigate(d.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
