import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val proxyUrl = providers.environmentVariable("AIVO_PROXY_URL")
    .orElse(providers.gradleProperty("AIVO_PROXY_URL"))
    .getOrElse("")

val environment = providers.gradleProperty("AIVO_ENVIRONMENT")
    .orElse(providers.environmentVariable("AIVO_ENVIRONMENT"))
    .getOrElse("development")
    .lowercase()

require(environment in setOf("development", "staging", "production")) {
    "AIVO_ENVIRONMENT must be development, staging, or production."
}

val environmentProxyUrl = when (environment) {
    "development" -> proxyUrl
    "staging" -> providers.environmentVariable("AIVO_STAGING_PROXY_URL")
        .orElse(providers.gradleProperty("AIVO_STAGING_PROXY_URL"))
        .getOrElse("")
    else -> providers.environmentVariable("AIVO_PRODUCTION_PROXY_URL")
        .orElse(providers.gradleProperty("AIVO_PRODUCTION_PROXY_URL"))
        .getOrElse("")
}

val googleWebClientId = when (environment) {
    "development" -> providers.environmentVariable("AIVO_GOOGLE_WEB_CLIENT_ID")
        .orElse(providers.gradleProperty("AIVO_GOOGLE_WEB_CLIENT_ID"))
        .getOrElse("")
    "staging" -> providers.environmentVariable("AIVO_GOOGLE_WEB_CLIENT_ID_STAGING")
        .orElse(providers.gradleProperty("AIVO_GOOGLE_WEB_CLIENT_ID_STAGING"))
        .getOrElse("")
    else -> providers.environmentVariable("AIVO_GOOGLE_WEB_CLIENT_ID_PRODUCTION")
        .orElse(providers.gradleProperty("AIVO_GOOGLE_WEB_CLIENT_ID_PRODUCTION"))
        .getOrElse("")
}

if (environmentProxyUrl.isNotBlank()) {
    val uri = URI(environmentProxyUrl)
    require(
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host != null &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            !Regex("(?i)%(2f|5c|2e)").containsMatchIn(uri.rawPath.orEmpty()) &&
            uri.normalize() == uri,
    ) {
        "The selected AIVO proxy URL must be HTTPS, have a host, and contain no user info, query, fragment, or dot segments."
    }
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "io.aivo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.aivo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-$environment"
        applicationIdSuffix = when (environment) {
            "development" -> ".dev"
            "staging" -> ".staging"
            else -> ""
        }
        buildConfigField("String", "AIVO_ENVIRONMENT", buildConfigString(environment))
        buildConfigField("String", "AIVO_PROXY_URL", buildConfigString(environmentProxyUrl))
        buildConfigField("String", "AIVO_GOOGLE_WEB_CLIENT_ID", buildConfigString(googleWebClientId))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.gson)
}
