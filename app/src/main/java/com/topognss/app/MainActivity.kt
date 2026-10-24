package com.topognss.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.topognss.app.ui.theme.LocalDarkTheme
import com.topognss.app.ui.theme.ThemeMode
import com.topognss.app.ui.theme.TopoTheme

/**
 * Navegación estilo Emlid Flow: sólo tres pestañas (Receptor, Levantamiento, Proyectos).
 * Puntos, Cálculo, Replanteo y Plomada se abren desde Levantamiento / Proyectos como pantallas
 * secundarias con su flecha de volver.
 */
private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    CONNECTION("connection", "Receptor", Icons.Filled.SettingsInputAntenna),
    SURVEY("survey", "Levantamiento", Icons.Filled.Map),
    PROJECTS("projects", "Proyectos", Icons.Filled.Folder),
}

private const val ROUTE_POINTS = "points"
private const val ROUTE_COGO = "cogo"
private const val ROUTE_STAKEOUT = "stakeout"
private const val ROUTE_PLUMB = "plumb"

/** Pantallas donde se muestra la franja de estado del receptor. */
private val STATUS_ROUTES = setOf(Dest.SURVEY.route, ROUTE_POINTS, ROUTE_COGO, ROUTE_STAKEOUT)

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

@Composable
private fun AppRoot(app: TopoApp) {
    val nav = rememberNavController()
    val gnss by app.gnss.state.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Permisos de tiempo de ejecución necesarios (Bluetooth 12+, ubicación, notificaciones 13+)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    val showStrip = route in STATUS_ROUTES
    val showTabs = Dest.entries.any { it.route == route }

    Scaffold(
        topBar = {
            // Un solo aviso de estado, delgado, y sólo donde aporta (no en Receptor, que ya lo muestra completo).
            Column(
                Modifier
                    .background(if (showStrip) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
            ) {
                if (showStrip) GnssStatusBar(gnss) { nav.goTab(Dest.CONNECTION) }
            }
        },
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    Dest.entries.forEach { d ->
                        NavigationBarItem(
                            selected = route == d.route,
                            onClick = { nav.goTab(d) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label, maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val openProjects = { nav.goTab(Dest.PROJECTS) }
        val back: () -> Unit = { nav.popBackStack() }
        NavHost(
            navController = nav,
            startDestination = if (app.prefs.activeProjectId.value > 0) Dest.SURVEY.route else Dest.PROJECTS.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (showTabs) Modifier else Modifier.navigationBarsPadding()),
        ) {
            composable(Dest.CONNECTION.route) { ConnectionScreen() }
            composable(Dest.SURVEY.route) {
                SurveyScreen(
                    onOpenProjects = openProjects,
                    onOpenPoints = { nav.navigate(ROUTE_POINTS) { launchSingleTop = true } },
                    onOpenCogo = { nav.navigate(ROUTE_COGO) { launchSingleTop = true } },
                )
            }
            composable(Dest.PROJECTS.route) {
                ProjectsScreen(
                    onDone = { nav.goTab(Dest.SURVEY) },
                    onOpenPlumb = { nav.navigate(ROUTE_PLUMB) { launchSingleTop = true } },
                )
            }
            composable(ROUTE_POINTS) {
                PointsScreen(
                    onOpenProjects = openProjects,
                    onStake = { nav.navigate(ROUTE_STAKEOUT) { launchSingleTop = true } },
                    onBack = back,
                )
            }
            composable(ROUTE_COGO) { CogoScreen(onOpenProjects = openProjects, onBack = back) }
            composable(ROUTE_STAKEOUT) { StakeoutScreen(onOpenProjects = openProjects, onBack = back) }
            composable(ROUTE_PLUMB) { PlumbScreen() }
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
