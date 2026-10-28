package io.github.andreacovelli.abyssh.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.andreacovelli.abyssh.service.network.LocalNetworkAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns a runner that, on Android 17 and later, asks for local network access before
 * running its action when the host is on the local network. The action runs whether or
 * not the user grants it; the connection then reports the missing permission itself.
 */
@Composable
fun rememberLocalNetworkPermissionGate(): (host: String, action: () -> Unit) -> Unit {
    val context = LocalContext.current
    val access = remember(context) { LocalNetworkAccess(context) }
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pending[0]
        pending[0] = null
        action?.invoke()
    }
    return { host, action ->
        if (!access.isRestricted) {
            action()
        } else {
            scope.launch {
                // Resolving the host may hit DNS.
                if (withContext(Dispatchers.IO) { access.blocks(host) }) {
                    pending[0] = action
                    launcher.launch(LocalNetworkAccess.PERMISSION)
                } else {
                    action()
                }
            }
        }
    }
}
