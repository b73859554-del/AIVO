package io.aivo.app

import java.net.URI

internal object BackendConfiguration {
    val proxyBaseUrl: String?
        get() = ProxyUrl.resolve(BuildConfig.AIVO_PROXY_URL)
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
                uri.rawFragment == null,
        ) {
            "AIVO_PROXY_URL must be an HTTPS URL with a host and no user information, query, or fragment."
        }
        return uri.toASCIIString()
    }
}
