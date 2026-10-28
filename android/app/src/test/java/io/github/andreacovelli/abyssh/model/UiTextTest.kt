package io.github.andreacovelli.abyssh.model

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import io.github.andreacovelli.abyssh.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiTextTest {
    private lateinit var resources: Resources

    @Before
    fun setUp() {
        resources = ApplicationProvider.getApplicationContext<Application>().resources
    }

    @Test
    fun rawTextPassesThrough() {
        assertEquals("Permission denied", UiText.Raw("Permission denied").resolve(resources))
    }

    @Test
    fun resourceResolvesNestedArguments() {
        val text = UiText.Resource(R.string.error_reconnect_failed, UiText.Resource(R.string.error_host_key_rejected))
        assertEquals("Reconnect failed: Host key rejected", text.resolve(resources))
    }

    @Test
    fun pluralsAndJoinedParts() {
        val text = UiText.Resource(
            R.string.backup_import_complete,
            UiText.Joined(
                listOf(
                    UiText.Plural(R.plurals.backup_summary_new_keys, 1),
                    UiText.Plural(R.plurals.backup_summary_hosts, 3),
                ),
            ),
        )
        assertEquals("Import complete: 1 new key, 3 hosts", text.resolve(resources))
    }

    @Test
    fun throwableKeepsItsUiTextOrRawMessage() {
        val fallback = UiText.Resource(R.string.error_unknown)
        val typed = UiText.Resource(R.string.error_host_key_rejected)
        assertEquals(typed, UiTextException(typed, "Host key rejected").toUiText(fallback))
        assertEquals(UiText.Raw("socket closed"), IllegalStateException("socket closed").toUiText(fallback))
        assertEquals(fallback, IllegalStateException(" ").toUiText(fallback))
    }
}
