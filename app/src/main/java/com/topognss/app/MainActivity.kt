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
import com.topognss.app.ui.plumb.PlumbScreen
import com.topognss.app.ui.points.PointsScreen
import com.topognss.app.ui.projects.ProjectsScreen
import com.topognss.app.ui.stakeout.StakeoutScreen
import com.topognss.app.ui.survey.SurveyScreen
import com.topognss.app.ui.theme.TopoTheme
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.topognss.app.ui.theme.LocalDarkTheme
import com.topognss.app.ui.theme.ThemeMode
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
private const val ROUTE_PLUMB = "plumb"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TopoApp
        setContent {
            val modeName by app.prefs.themeMode.collectAsState()
            val mode = runCatching { ThemeMode.valueOf(modeName) }.getOrDefault(ThemeMode.SYSTEM)
            TopoTheme(mode) {
                // Iconos de la barra de estado/navegación acordes al tema elegido (no al del sistema)
                val dark = LocalDarkTheme.current
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                AppRoot(app)
            }
        }
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
                        OverflowMenu(app, onOpenPlumb = { nav.navigate(ROUTE_PLUMB) { launchSingleTop = true } })
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
            composable(ROUTE_PLUMB) { PlumbScreen() }
        }
    }
}

/** Menú ⋮: elección de tema (se guarda en preferencias) y accesos a herramientas secundarias. */
@Composable
private fun OverflowMenu(app: TopoApp, onOpenPlumb: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current by app.prefs.themeMode.collectAsState()
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Opciones") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Plomada virtual") },
                onClick = { open = false; onOpenPlumb() },
            )
            Text("Tema", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            ThemeMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text((if (current == m.name) "✓ " else "   ") + m.label) },
                    onClick = { app.prefs.setThemeMode(m.name); open = false },
                )
            }
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
