package io.github.andreacovelli.abyssh.ui

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import io.github.andreacovelli.abyssh.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.data.secure.DataResetNotice
import io.github.andreacovelli.abyssh.model.QuickConnectTarget
import io.github.andreacovelli.abyssh.ui.screens.addserver.AddServerScreen
import io.github.andreacovelli.abyssh.ui.screens.addserver.AddServerViewModel
import io.github.andreacovelli.abyssh.ui.screens.keys.KeysViewModel
import io.github.andreacovelli.abyssh.ui.screens.knownhosts.KnownHostsScreen
import io.github.andreacovelli.abyssh.ui.screens.knownhosts.KnownHostsViewModel
import io.github.andreacovelli.abyssh.ui.screens.serverlist.DataResetDialog
import io.github.andreacovelli.abyssh.ui.screens.serverlist.ServerListScreen
import io.github.andreacovelli.abyssh.ui.screens.serverlist.ServerListViewModel
import io.github.andreacovelli.abyssh.ui.screens.settings.SettingsBackupViewModel
import io.github.andreacovelli.abyssh.ui.screens.settings.SettingsScreen
import io.github.andreacovelli.abyssh.ui.screens.settings.SettingsViewModel
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalScreen
import io.github.andreacovelli.abyssh.ui.screens.terminal.TerminalViewModel
import io.github.andreacovelli.abyssh.ui.security.AppLockGate
import io.github.andreacovelli.abyssh.ui.security.AppLockState
import io.github.andreacovelli.abyssh.ui.security.verifyThen

@Composable
fun ApplicationNavController() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application
    val lockState: AppLockState = viewModel()

    AppLockGate(application, lockState) {
        NavHost(navController = navController, startDestination = "servers") {
            composable("servers") { ServersRoute(navController, application) }
            composable("settings") {
                SettingsRoute(navController, application, onAppLockVerified = lockState::markUnlocked)
            }
            composable("settings/known-hosts") {
                val vm: KnownHostsViewModel = viewModel(factory = KnownHostsViewModel.factory(application))
                KnownHostsScreen(vm = vm, onBack = dropUnlessResumed { navController.popBackStack() })
            }
            serverEditorRoutes(navController, application)
            terminalRoutes(navController, application)
        }
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
    val resetNotice = DataResetNotice.getInstance(application)
    val dataWasReset by resetNotice.pending.collectAsStateWithLifecycle()

    if (dataWasReset) DataResetDialog(onDismiss = resetNotice::dismiss)

    ServerListScreen(
        vm = vm,
        localShellEnabled = localShellEnabled,
        onAddServer = { navController.navigate("servers/add") { launchSingleTop = true } },
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
        onEditServer = { id -> navController.navigate("servers/edit/$id") { launchSingleTop = true } },
        onCopyServer = { id -> navController.navigate("servers/copy/$id") { launchSingleTop = true } },
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
        onQuickConnect = { target ->
            val host = Uri.encode(target.host)
            val username = Uri.encode(target.username)
            navController.navigate("servers/add?host=$host&port=${target.port}&username=$username") {
                launchSingleTop = true
            }
        },
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
                unavailableMessage = R.string.settings_lock_app_unavailable,
            ) {
                onAppLockVerified()
                vm.setAppLockEnabled(true)
            }
        },
        backupViewModel = viewModel<SettingsBackupViewModel>(factory = SettingsBackupViewModel.factory(application)),
        keysViewModel = viewModel<KeysViewModel>(factory = KeysViewModel.factory(application)),
        onOpenKnownHosts = { navController.navigate("settings/known-hosts") { launchSingleTop = true } },
        onBack = {
            if (navController.currentBackStackEntry?.destination?.route == "settings") {
                navController.popBackStack()
            }
        },
    )
}

/**
 * Add, edit, and copy routes, which all open the server editor. Leaving is ignored once
 * the editor is on its way out, so a second tap on Save or Back can't pop the screen under it.
 */
private fun NavGraphBuilder.serverEditorRoutes(navController: NavHostController, application: Application) {
    val idArgument = listOf(navArgument("id") { type = NavType.LongType })
    composable("servers/add") {
        val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, null))
        AddServerScreen(vm = vm, onBack = dropUnlessResumed { navController.popBackStack() })
    }
    composable(
        route = "servers/add?host={host}&port={port}&username={username}",
        arguments = listOf(
            navArgument("host") { type = NavType.StringType },
            navArgument("port") { type = NavType.IntType },
            navArgument("username") { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        val context = LocalContext.current
        val resources = LocalResources.current
        val prefill = QuickConnectTarget(
            username = backStackEntry.arguments?.getString("username").orEmpty(),
            host = backStackEntry.arguments?.getString("host").orEmpty(),
            port = backStackEntry.arguments?.getInt("port") ?: return@composable,
        )
        val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, null, prefill = prefill))
        val requireAuthOnConnect = SettingsRepository.getInstance(application).requireAuthOnConnect
        AddServerScreen(
            vm = vm,
            onBack = dropUnlessResumed { navController.popBackStack() },
            onSaveAndConnect = { host ->
                // Back stays with the list, whether or not verification lets the session open.
                if (backStackEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    navController.popBackStack()
                    verifyHostThen(context, resources, requireAuthOnConnect.value || host.requireAuthOnConnect) {
                        navController.navigate("terminal/${host.id}")
                    }
                }
            },
        )
    }
    composable(route = "servers/edit/{id}", arguments = idArgument) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("id")
        val vm: AddServerViewModel = viewModel(factory = AddServerViewModel.factory(application, id))
        AddServerScreen(vm = vm, onBack = dropUnlessResumed { navController.popBackStack() })
    }
    composable(route = "servers/copy/{id}", arguments = idArgument) { backStackEntry ->
        val id = backStackEntry.arguments?.getLong("id")
        val vm: AddServerViewModel =
            viewModel(factory = AddServerViewModel.factory(application, id, copy = true))
        AddServerScreen(vm = vm, onBack = dropUnlessResumed { navController.popBackStack() })
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
