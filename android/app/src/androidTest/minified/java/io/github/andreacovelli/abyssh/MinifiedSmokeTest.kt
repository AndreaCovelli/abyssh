package io.github.andreacovelli.abyssh

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.andreacovelli.abyssh.service.backup.NativeBackupBridge
import io.github.andreacovelli.abyssh.service.ssh.NativeSshBridge
import io.github.andreacovelli.abyssh.service.terminal.GhosttyBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The only tests that run against the R8-minified build (-PtestBuildType=minified). They
 * check that the app starts and that the native library still reaches the bridges R8
 * processed. They call only the bridge methods the app itself uses: R8 removes or
 * renames anything else, so other app code isn't reachable from here.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedSmokeTest {
    @Test
    fun theAppStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun sshKeysGoThroughTheNativeLibrary() {
        val bridge = NativeSshBridge()
        val generated = bridge.nativeGenerateEd25519Key("smoke@abyssh", null)!!
        val derived = bridge.nativeDerivePublicKey(generated[0])!!

        assertEquals("ssh-ed25519", derived[0])
        assertEquals(generated[1].split(' ').take(2), derived[1].split(' ').take(2))
    }

    @Test
    fun backupsGoThroughTheNativeLibrary() {
        val bridge = NativeBackupBridge()
        val plaintext = "smoke".toByteArray()

        val sealed = bridge.nativeEncrypt(plaintext.copyOf(), "passphrase".toCharArray())!!
        assertEquals(NativeBackupBridge.STATUS_OK, sealed[0].toInt())
        val opened = bridge.nativeDecrypt(sealed.copyOfRange(1, sealed.size), "passphrase".toCharArray())!!

        assertEquals(NativeBackupBridge.STATUS_OK, opened[0].toInt())
        assertArrayEquals(plaintext, opened.copyOfRange(1, opened.size))
    }

    @Test
    fun theTerminalGoesThroughTheNativeLibrary() {
        val bridge = GhosttyBridge()
        val handle = bridge.nativeCreate(80, 24, 1024 * 1024)
        assertNotEquals(0L, handle)
        try {
            bridge.nativeWriteRemote(handle, "smoke\r\n".toByteArray())
            assertTrue(bridge.nativeSnapshot(handle, true).capacity() > 0)
        } finally {
            bridge.nativeDestroy(handle)
        }
    }
}
