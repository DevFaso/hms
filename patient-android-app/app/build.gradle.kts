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
        minSdk = 23
        targetSdk = 35
        // Overridable from CI: every AAB uploaded to Play burns a version
        // code, so a second publish of a hard-coded one is rejected outright.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 13
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // defaultConfig is what a RELEASE build inherits, so it defaults to
        // production. `api.dev.e-keneya.com` has no DNS record: a release
        // built against it reaches no server at all. Verified 2026-09-19:
        //   api.e-keneya.com/api  -> 200   dev.e-keneya.com/api -> 200
        //   api.dev.e-keneya.com  -> no response
        //
        // -PapiBaseUrl overrides it so a SIGNED build can be pointed at dev.
        // Internal-testing builds must use that: the release bundle is the one
        // handed to testers, and a tester exercising the appointment-cancel
        // flow against production cancels a real patient's real appointment.
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?)
            ?: "https://api.e-keneya.com/api"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")

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
        val keycloakSsoEnabledRaw = localProps.getProperty("KEYCLOAK_SSO_ENABLED", "false")
        // Normalize to a strict "true"/"false" literal — values like "1"/"yes"/"on"
        // (common in CI envs) would otherwise break the generated BuildConfig field.
        val keycloakSsoEnabled = when (keycloakSsoEnabledRaw.trim().lowercase()) {
            "true", "1", "yes", "on" -> "true"
            else -> "false"
        }
        buildConfigField("String", "KEYCLOAK_ISSUER", "\"$keycloakIssuer\"")
        buildConfigField("String", "KEYCLOAK_CLIENT_ID", "\"$keycloakClientId\"")
        buildConfigField("String", "KEYCLOAK_REDIRECT_URI", "\"$keycloakRedirectUri\"")
        buildConfigField("Boolean", "KEYCLOAK_SSO_ENABLED_DEFAULT", keycloakSsoEnabled)

        // AppAuth redirect scheme consumed by net.openid.appauth.RedirectUriReceiverActivity
        // via manifest placeholder. Must match the scheme portion of KEYCLOAK_REDIRECT_URI.
        manifestPlaceholders["appAuthRedirectScheme"] = keycloakRedirectScheme
    }

    // Signing values come from local.properties on a developer machine and
    // from the environment in CI, where writing local.properties would mean
    // spilling the keystore password onto disk in the runner.
    fun signingValue(key: String, fallback: String) =
        localProps.getProperty(key) ?: System.getenv(key) ?: fallback

    signingConfigs {
        create("release") {
            storeFile = file(signingValue("STORE_FILE", "../upload-keystore.jks"))
            storePassword = signingValue("STORE_PASSWORD", "")
            keyAlias = signingValue("KEY_ALIAS", "upload")
            keyPassword = signingValue("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // The dev API is served same-origin by the portal host; the
            // `api.dev.` subdomain was never provisioned.
            buildConfigField("String", "API_BASE_URL", "\"https://dev.e-keneya.com/api\"")
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
        isCoreLibraryDesugaringEnabled = true
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

    testOptions {
        unitTests {
            // Required for Robolectric Compose UI tests so the test
            // manifest from androidx.compose.ui:ui-test-manifest
            // (which declares ComponentActivity) is on the classpath.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

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
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockk.android)
}

// Allow references to generated code
kapt {
    correctErrorTypes = true
}
