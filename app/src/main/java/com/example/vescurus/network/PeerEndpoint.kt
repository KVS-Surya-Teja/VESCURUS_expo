package com.example.vescurus.network

/** Validation only; never resolve DNS or block the UI thread. */
fun isValidPeerHost(raw: String): Boolean {
    val host=raw.trim()
    if(host.isEmpty() || host.length>253 || host.any { it.isWhitespace() } || host.contains('/'))return false
    if(host.all { it.isDigit() || it=='.' }) {
        val octets=host.split('.')
        return octets.size==4 && octets.all { it.isNotEmpty() && it.length<=3 && it.toIntOrNull() in 0..255 }
    }
    if(host.contains(':'))return host.count { it==':' }>=2 && host.all { it.isLetterOrDigit() || it in ":.%_-" }
    val label=Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    return host.split('.').all(label::matches)
}
