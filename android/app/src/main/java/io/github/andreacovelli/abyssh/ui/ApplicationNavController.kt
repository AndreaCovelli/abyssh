package io.github.andreacovelli.abyssh.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import io.github.andreacovelli.abyssh.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.screens.addserver.AddServerScreen
import io.github.andreacovelli.abyssh.ui.screens.addserver.AddServerViewModel
import io.github.andreacovelli.abyssh.ui.screens.keys.KeysViewModel
import io.github.andreacovelli.abyssh.ui.screens.serverlist.ServerListScreen
import io.github.andreacovelli.abyssh.ui.screens.serverlist.ServerListViewModel
import io.github.andreacovelli.abyssh.ui.screens.settings.SettingsBackupViewModel
import io.github.andreacovelli.abyssh.ui.screens.settings.SettingsScreen
import io.github.andreacovelli.abyssh.ui.screens.settings.SettingsViewModel
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalScreen
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalViewModel
import io.github.andreacovelli.abyssh.ui.security.VerificationResult
import io.github.andreacovelli.abyssh.ui.security.requireUserVerification

@Composable
fun ApplicationNavController() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application

    NavHost(navController = navController, startDestination = "servers") {
        composable("servers") { ServersRoute(navController, application) }
        composable("settings") { SettingsRoute(navController, application) }
        serverEditorRoutes(navController, application)
        terminalRoutes(navController, application)
    }

    AppLockGate(application)
}

/** Runs [onVerified] after the user passes device verification. */
private fun verifyThen(context: Context, title: String, subtitle: String, onVerified: () -> Unit) {
    requireUserVerification(context = context, title = title, subtitle = subtitle) { result ->
        if (result == VerificationResult.Success) onVerified()
    }
}

@Composable
private fun ServersRoute(navController: NavHostController, application: Application) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val vm: ServerListViewModel = viewModel(factory = ServerListViewModel.factory(application))
    val settingsRepo = SettingsRepository.getInstance(application)
    val requireAuthOnConnect by settingsRepo.requireAuthOnConnect.collectAsStateWithLifecycle()
    val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()

    ServerListScreen(
        vm = vm,
        localShellEnabled = localShellEnabled,
        onAddServer = { navController.navigate("servers/add") },
        onOpenLocalShell = localShell@{
            if (!localShellEnabled) return@localShell
            val open = { navController.navigate("terminal/local") }
            if (!requireAuthOnConnect) {
                open()
            } else {
                verifyThen(
                    context = context,
                    title = resources.getString(R.string.nav_verify_local_shell_title),
                    subtitle = resources.getString(R.string.nav_verify_local_shell_subtitle),
                    onVerified = open,
                )
            }
        },
        onEditServer = { id -> navController.navigate("servers/edit/$id") },
        onCopyServer = { id -> navController.navigate("servers/copy/$id") },
        onConnectServer = { host ->
            val connect = {
                vm.prepareConnect(host.id)
                navController.navigate("terminal/${host.id}")
            }
            if (!requireAuthOnConnect && !host.requireAuthOnConnect) {
                connect()
            } else {
                verifyThen(
                    context = context,
                    title = resources.getString(R.string.terminal_verify_title),
                    subtitle = resources.getString(R.string.terminal_verify_subtitle),
                    onVerified = connect,
                )
            }
        },
        onOpenSettings = { navController.navigate("settings") },
    )
}

@Composable
private fun SettingsRoute(navController: NavHostController, application: Application) {
    SettingsScreen(
        vm = viewModel(factory = SettingsViewModel.factory(application)),
        backupViewModel = viewModel<SettingsBackupViewModel>(factory = SettingsBackupViewModel.factory(application)),
        keysViewModel = viewModel<KeysViewModel>(factory = KeysViewModel.factory(application)),
        onBack = {
            if (navController.currentBackStackEntry?.destination?.route == "settings") {
                navController.popBackStack()
            }
        },
    )
}

/** Add, edit, and copy routes, which all open the server editor. */
private fun NavGraphBuilder.serverEditorRoutes(navController: NavHostController, application: Application) {
    val idArgument = listOf(navArgument("id") { type = NavType.LongType })
    composable("servers/add") {
        val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, null))
        AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
    }
    composable(route = "servers/edit/{id}", arguments = idArgument) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("id")
        val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, id))
        AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
    }
    composable(route = "servers/copy/{id}", arguments = idArgument) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("id")
        val vm: AddServerViewModel =
            viewModel(factory = AddServerViewModel.factory(application, id, copy = true))
        AddServerScreen(vm = vm, onBack = { navController.popBackStack() })
    }
}

private fun NavGraphBuilder.terminalRoutes(navController: NavHostController, application: Application) {
    composable("terminal/local") {
        val settingsRepo = SettingsRepository.getInstance(application)
        val localShellEnabled by settingsRepo.localShellEnabled.collectAsStateWithLifecycle()
        if (localShellEnabled) {
            val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(application))
            TerminalScreen(
                vm = vm,
                hostId = null,
                openLocalShell = true,
                onOpenSettings = { navController.navigate("settings") },
                onBack = { navController.popBackStack() },
            )
        } else {
            // The local shell was turned off while this route was on the back stack.
            LaunchedEffect(Unit) {
                val popped = navController.popBackStack("servers", inclusive = false)
                if (!popped) {
                    navController.navigate("servers") { launchSingleTop = true }
                }
            }
        }
    }
    composable(
        route = "terminal/{id}",
        arguments = listOf(navArgument("id") { type = NavType.LongType }),
    ) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("id")
        val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(application))
        TerminalScreen(
            vm = vm,
            hostId = id,
            onOpenSettings = { navController.navigate("settings") },
            onBack = { navController.popBackStack() },
        )
    }
}

/**
 * Asks for device verification when app lock is on and the app has not been unlocked
 * since it last went to the background. A failed prompt is not repeated until app lock
 * is toggled or the app is backgrounded again.
 */
@Composable
private fun AppLockGate(application: Application) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var appUnlocked by rememberSaveable { mutableStateOf(false) }
    var unlockPromptRequested by rememberSaveable { mutableStateOf(false) }
    var appLockBlockedUntilToggle by rememberSaveable { mutableStateOf(false) }

    fun reset() {
        appUnlocked = false
        unlockPromptRequested = false
        appLockBlockedUntilToggle = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, event ->
            val isConfigChange = (source as? Activity)?.isChangingConfigurations == true
            if (event == Lifecycle.Event.ON_STOP && !isConfigChange) reset()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val appLockEnabled by SettingsRepository.getInstance(application).appLockEnabled.collectAsStateWithLifecycle()
    if (!appLockEnabled) reset()

    LaunchedEffect(appLockEnabled, appUnlocked, unlockPromptRequested, appLockBlockedUntilToggle) {
        if (appLockEnabled && !appUnlocked && !unlockPromptRequested && !appLockBlockedUntilToggle) {
            unlockPromptRequested = true
            requireUserVerification(
                context = context,
                title = resources.getString(R.string.nav_unlock_title),
                subtitle = resources.getString(R.string.nav_unlock_subtitle),
            ) { result ->
                appUnlocked = result == VerificationResult.Success
                if (result != VerificationResult.Success) {
                    appLockBlockedUntilToggle = true
                }
                unlockPromptRequested = false
            }
        }
    }
}
