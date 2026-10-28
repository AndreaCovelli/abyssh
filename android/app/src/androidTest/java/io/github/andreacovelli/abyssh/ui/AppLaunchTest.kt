package io.github.andreacovelli.abyssh.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.andreacovelli.abyssh.MainActivity
import io.github.andreacovelli.abyssh.data.db.AppDatabase
import io.github.andreacovelli.abyssh.model.HostProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val db get() = AppDatabase.getInstance(compose.activity)
    private var hostId = 0L

    @After
    fun tearDown() = runBlocking {
        db.hostProfileDao().getById(hostId)?.let { db.hostProfileDao().delete(it) }
        Unit
    }

    @Test
    fun savedServerShowsInTheList() {
        hostId = runBlocking {
            db.hostProfileDao().insert(
                HostProfile(name = "launch-test-host", host = "10.0.2.2", username = "me", password = "pw"),
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("launch-test-host").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("host_connect_$hostId").assertExists()
    }
}
