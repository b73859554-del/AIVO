import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val proxyUrl = providers.environmentVariable("AIVO_PROXY_URL")
    .orElse(providers.gradleProperty("AIVO_PROXY_URL"))
    .getOrElse("")

if (proxyUrl.isNotBlank()) {
    val uri = URI(proxyUrl)
    require(
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host != null &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null,
    ) {
        "AIVO_PROXY_URL must be an HTTPS URL with a host and no user information, query, or fragment."
    }
}

android {
    namespace = "io.aivo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.aivo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "AIVO_PROXY_URL", "\"$proxyUrl\"")
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
    testImplementation(libs.junit)
}
