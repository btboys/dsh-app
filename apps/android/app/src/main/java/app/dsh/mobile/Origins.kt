package app.dsh.mobile

import java.net.URI

/** Compares WebView navigations with the paired computer by scheme, host and port. */
object Origins {
    /** True when [candidate] stays on the paired origin; a string prefix is not enough. */
    fun isSameOrigin(paired: String, candidate: String): Boolean {
        val expected = parse(paired) ?: return false
        val actual = parse(candidate) ?: return false
        return actual.scheme == expected.scheme &&
            actual.host.equals(expected.host, ignoreCase = true) &&
            actual.port == expected.port
    }

    /** True when the request targets the paired origin; javascript: and file: never match. */
    fun isAllowedNavigation(paired: String, candidate: String): Boolean =
        isSameOrigin(paired, candidate)

    private fun parse(value: String): Parsed? = try {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) defaultPort(scheme) else uri.port
        Parsed(scheme, host, port)
    } catch (error: Exception) {
        null
    }

    private fun defaultPort(scheme: String) = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }

    private data class Parsed(val scheme: String, val host: String, val port: Int)
}
