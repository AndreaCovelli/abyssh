package io.github.andreacovelli.ctulssh.data.db

import androidx.room.TypeConverter
import io.github.andreacovelli.ctulssh.model.AuthMethod
import io.github.andreacovelli.ctulssh.model.MultiplexerType
import io.github.andreacovelli.ctulssh.model.Transport

class Converters {
    @TypeConverter
    fun fromTransport(value: Transport): String = value.name

    @TypeConverter
    fun toTransport(value: String): Transport = Transport.valueOf(value)

    @TypeConverter
    fun fromAuthMethod(value: AuthMethod): String = value.name

    @TypeConverter
    fun toAuthMethod(value: String): AuthMethod = AuthMethod.valueOf(value)

    @TypeConverter
    fun fromMultiplexer(value: MultiplexerType?): String? = value?.id

    @TypeConverter
    fun toMultiplexer(value: String?): MultiplexerType? = value?.let { MultiplexerType.fromPersistedValue(it) }
}
