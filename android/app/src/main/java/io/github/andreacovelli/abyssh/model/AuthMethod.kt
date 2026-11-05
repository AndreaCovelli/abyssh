package io.github.andreacovelli.abyssh.model

enum class AuthMethod {
    None,
    Password,
    Key,
    KeyWithPassphrase,
    ;

    val usesKey: Boolean get() = this == Key || this == KeyWithPassphrase
}
