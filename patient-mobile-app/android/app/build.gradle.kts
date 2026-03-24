plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.bitnesttechs.hms.patient"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bitnesttechs.hms.patient"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"https://hms.dev.bitnesttechs.com/api\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"https://hms.bitnesttechs.com/api\"")
        }
    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", "\"https://hms.dev.bitnesttechs.com/api\"")
        }
        create("uat") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", "\"https://hms.uat.bitnesttechs.com/api\"")
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", "\"https://hms.bitnesttechs.com/api\"")
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
    implementation(project(":core"))
    implementation(project(":feature-auth"))
    implementation(project(":feature-home"))
    implementation(project(":feature-appointments"))
    implementation(project(":feature-records"))
    implementation(project(":feature-billing"))
    implementation(project(":feature-profile"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-more"))
    implementation(project(":feature-medications"))
    implementation(project(":feature-notifications"))
    implementation(project(":feature-lab-results"))
    implementation(project(":feature-vitals"))
    implementation(project(":feature-care-team"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-consents"))
    implementation(project(":feature-access-logs"))
    implementation(project(":feature-immunizations"))
    implementation(project(":feature-treatment-plans"))
    implementation(project(":feature-referrals"))
    implementation(project(":feature-consultations"))
    implementation(project(":feature-documents"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
