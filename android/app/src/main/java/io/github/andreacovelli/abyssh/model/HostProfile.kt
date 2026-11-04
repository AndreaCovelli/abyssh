package io.github.andreacovelli.abyssh.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "host_profiles",
    indices = [Index(value = ["name"])],
)
data class HostProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val keyId: Long? = null,
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
    @ColumnInfo(defaultValue = "0")
    val requireAuthOnConnect: Boolean = false,
    val postConnectCommand: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val multiplexer: MultiplexerType? = null,
    // Attach to tmux in control mode, so each tmux window is a tab with local scrollback.
    @ColumnInfo(defaultValue = "0")
    val tmuxControlMode: Boolean = false,
    // List the multiplexer sessions on connect and let the user pick one, instead of
    // reusing or creating one automatically.
    @ColumnInfo(defaultValue = "0")
    val chooseSessionOnConnect: Boolean = false,
    // Seconds between SSH keepalives, 0 for none. A connection that answers none of
    // three in a row is dropped and reconnected.
    @ColumnInfo(defaultValue = "30")
    val keepaliveIntervalSeconds: Int = DEFAULT_KEEPALIVE_INTERVAL_SECONDS,
)

const val DEFAULT_KEEPALIVE_INTERVAL_SECONDS = 30

/** The longest keepalive interval a host can set: one day. */
const val MAX_KEEPALIVE_INTERVAL_SECONDS = 24 * 60 * 60
