package io.github.andreacovelli.abyssh.ui

import android.app.Application
import android.content.Context
import android.content.res.Resources
import io.github.andreacovelli.abyssh.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import io.github.andreacovelli.abyssh.ui.security.AppLockGate
import io.github.andreacovelli.abyssh.ui.security.VerificationResult
import io.github.andreacovelli.abyssh.ui.security.requireUserVerification

@Composable
fun ApplicationNavController() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application
    // Whether the user has verified since the app last came to the foreground.
    var appUnlocked by rememberSaveable { mutableStateOf(false) }

    AppLockGate(application, unlocked = appUnlocked, onUnlockedChange = { appUnlocked = it }) {
        NavHost(navController = navController, startDestination = "servers") {
            composable("servers") { ServersRoute(navController, application) }
            composable("settings") {
                SettingsRoute(navController, application, onAppLockVerified = { appUnlocked = true })
            }
            serverEditorRoutes(navController, application)
            terminalRoutes(navController, application)
        }
    }
}

/** Runs [onVerified] after the user passes device verification. */
private fun verifyThen(context: Context, title: String, subtitle: String, onVerified: () -> Unit) {
    requireUserVerification(context = context, title = title, subtitle = subtitle) { result ->
        if (result == VerificationResult.Success) onVerified()
    }
}

/** Runs [onVerified], after device verification when [required]. */
private fun verifyHostThen(context: Context, resources: Resources, required: Boolean, onVerified: () -> Unit) {
    if (!required) {
        onVerified()
        return
    }
    verifyThen(
        context = context,
        title = resources.getString(R.string.terminal_verify_title),
        subtitle = resources.getString(R.string.terminal_verify_subtitle),
        onVerified = onVerified,
    )
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
            verifyHostThen(context, resources, requireAuthOnConnect || host.requireAuthOnConnect, connect)
        },
        onNewSession = { host ->
            val open = { navController.navigate("terminal/${host.id}?newSession=true") }
            verifyHostThen(context, resources, requireAuthOnConnect || host.requireAuthOnConnect, open)
        },
        onOpenSettings = { navController.navigate("settings") },
    )
}

/**
 * Turning app lock on asks for verification first, so the setting stays off when the
 * user cancels or fails the prompt. [onAppLockVerified] marks the app as unlocked so the
 * gate does not ask again straight away.
 */
@Composable
private fun SettingsRoute(
    navController: NavHostController,
    application: Application,
    onAppLockVerified: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(application))
    SettingsScreen(
        vm = vm,
        onEnableAppLock = {
            verifyThen(
                context = context,
                title = resources.getString(R.string.settings_lock_app_verify_title),
                subtitle = resources.getString(R.string.settings_lock_app_verify_subtitle),
            ) {
                onAppLockVerified()
                vm.setAppLockEnabled(true)
            }
        },
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
        route = "terminal/{id}?newSession={newSession}",
        arguments = listOf(
            navArgument("id") { type = NavType.LongType },
            navArgument("newSession") {
                type = NavType.BoolType
                defaultValue = false
            },
        ),
    ) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("id")
        val newSession = backStackEntry.arguments?.getBoolean("newSession") == true
        val vm: TerminalViewModel = viewModel(factory = TerminalViewModel.factory(application))
        TerminalScreen(
            vm = vm,
            hostId = id,
            newSession = newSession,
            onOpenSettings = { navController.navigate("settings") },
            onBack = { navController.popBackStack() },
        )
    }
}
