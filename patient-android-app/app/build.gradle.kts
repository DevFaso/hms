import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    kotlin("kapt")
}

// Load signing properties from local.properties
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(FileInputStream(localPropsFile))
}

android {
    namespace = "com.bitnesttechs.hms.patient"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bitnesttechs.hms.patient"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Build config fields — override with local.properties or CI env vars
        // TODO: revert to production before Play Store release
        // buildConfigField("String", "API_BASE_URL", "\"https://hms-production.up.railway.app/api\"")
        buildConfigField("String", "API_BASE_URL", "\"https://api.hms.dev.bitnesttechs.com/api\"")

        // Keycloak / OIDC config (KC-3). SSO is OFF by default until prod Keycloak is
        // provisioned (tasks-keycloak.md P-2). Override via local.properties or CI env.
        val keycloakIssuer = localProps.getProperty("KEYCLOAK_ISSUER", "")
        val keycloakClientId = localProps.getProperty("KEYCLOAK_CLIENT_ID", "hms-patient-android")
        val keycloakRedirectScheme = localProps.getProperty(
            "KEYCLOAK_REDIRECT_SCHEME",
            "com.bitnesttechs.hms.patient"
        )
        val keycloakRedirectUri = localProps.getProperty(
            "KEYCLOAK_REDIRECT_URI",
            "$keycloakRedirectScheme:/oauth2redirect"
        )
        val keycloakSsoEnabled = localProps.getProperty("KEYCLOAK_SSO_ENABLED", "false")
        buildConfigField("String", "KEYCLOAK_ISSUER", "\"$keycloakIssuer\"")
        buildConfigField("String", "KEYCLOAK_CLIENT_ID", "\"$keycloakClientId\"")
        buildConfigField("String", "KEYCLOAK_REDIRECT_URI", "\"$keycloakRedirectUri\"")
        buildConfigField("Boolean", "KEYCLOAK_SSO_ENABLED_DEFAULT", keycloakSsoEnabled)

        // AppAuth redirect scheme consumed by net.openid.appauth.RedirectUriReceiverActivity
        // via manifest placeholder. Must match the scheme portion of KEYCLOAK_REDIRECT_URI.
        manifestPlaceholders["appAuthRedirectScheme"] = keycloakRedirectScheme
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("STORE_FILE", "../upload-keystore.jks"))
            storePassword = localProps.getProperty("STORE_PASSWORD", "")
            keyAlias = localProps.getProperty("KEY_ALIAS", "upload")
            keyPassword = localProps.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            buildConfigField("String", "API_BASE_URL", "\"https://api.hms.dev.bitnesttechs.com/api\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    kapt(libs.moshi.codegen)

    // Coroutines
    implementation(libs.coroutines.android)

    // Biometric
    implementation(libs.biometric)

    // Secure storage
    implementation(libs.security.crypto)

    // Image loading
    implementation(libs.coil.compose)

    // Splash screen
    implementation(libs.splashscreen)

    // Material3 XML themes (for Theme.Material3.DayNight.NoActionBar)
    implementation(libs.material3.xml)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // OIDC / OAuth2 (Keycloak SSO — KC-3)
    implementation(libs.appauth)

    // DataStore-backed feature flags
    implementation(libs.datastore.preferences)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Unit tests (JVM)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockk.android)
}

// Allow references to generated code
kapt {
    correctErrorTypes = true
}
