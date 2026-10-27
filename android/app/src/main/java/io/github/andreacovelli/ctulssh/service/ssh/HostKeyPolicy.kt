package io.github.andreacovelli.ctulssh.service.ssh

fun interface HostKeyPolicy {
    fun verify(host: String, port: Int, algorithm: String, keyBytes: ByteArray): Boolean
}
