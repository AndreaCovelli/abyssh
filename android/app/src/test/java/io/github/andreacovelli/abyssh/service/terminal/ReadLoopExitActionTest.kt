package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadLoopExitActionTest {
    @Test
    fun remoteShellTransportsKeepAutomaticReconnect() {
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.SSH),
        )
        assertEquals(
            ReadLoopExitAction.AutomaticReconnect,
            readLoopExitAction(Transport.TailscaleSSH),
        )
    }

    @Test
    fun localShellDisconnectsWithoutRemoteReconnect() {
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.LocalShell),
        )
    }

    @Test
    fun remoteShellExitDisconnectsInsteadOfReconnecting() {
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.SSH, shellExited = true),
        )
        assertEquals(
            ReadLoopExitAction.Disconnect,
            readLoopExitAction(Transport.TailscaleSSH, shellExited = true),
        )
    }
}
