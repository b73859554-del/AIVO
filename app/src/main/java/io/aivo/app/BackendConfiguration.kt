package io.aivo.app

import java.net.URI

internal object BackendConfiguration {
    val environment: String
        get() = BuildConfig.AIVO_ENVIRONMENT

    val proxyBaseUrl: String?
        get() = ProxyUrl.resolve(BuildConfig.AIVO_PROXY_URL)

    val googleWebClientId: String?
        get() = BuildConfig.AIVO_GOOGLE_WEB_CLIENT_ID.takeIf(String::isNotBlank)
}

internal object ProxyUrl {
    fun resolve(configuredUrl: String): String? {
        if (configuredUrl.isBlank()) return null

        val uri = URI(configuredUrl)
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                !ENCODED_PATH_SEPARATOR_OR_DOT.containsMatchIn(uri.rawPath.orEmpty()) &&
                uri.normalize() == uri,
        ) {
            "AIVO_PROXY_URL must be an HTTPS URL with a host and no user information, query, fragment, or dot segments."
        }
        return uri.toASCIIString()
    }

    private val ENCODED_PATH_SEPARATOR_OR_DOT = Regex("(?i)%(2f|5c|2e)")
}
