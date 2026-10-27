plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvmToolchain(21)
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "io.github.andreacovelli.abyssh"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.andreacovelli.abyssh"
        minSdk = 24
        targetSdk = 36

        val major = (System.getenv("VERSION_MAJOR")?.toIntOrNull() ?: 0)
        val minor = (System.getenv("VERSION_MINOR")?.toIntOrNull() ?: 2)
        val patch = (System.getenv("VERSION_PATCH")?.toIntOrNull() ?: 1)
        val releaseBase = major * 10_000 + minor * 100 + patch
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: (releaseBase * 1_000)
        versionName = System.getenv("VERSION_NAME") ?: "$major.$minor.$patch"

        System.getenv("ANDROID_ABI_FILTERS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { filters ->
                ndk {
                    abiFilters += filters
                }
            }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("key.jks")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("KEY_ALIAS")
            val keyPasswordEnv = System.getenv("KEY_PASSWORD")

            if (keystorePassword != null && keyAliasEnv != null && keyPasswordEnv != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources to resolve app resources in tests.
            isIncludeAndroidResources = true
            all { test ->
                // Roborazzi gates PNG capture on this system property; the Gradle
                // plugin usually wires it, but we pass it through manually so
                // `-Proborazzi.test.record=true` works with plain test tasks.
                test.systemProperty(
                    "roborazzi.test.record",
                    providers.gradleProperty("roborazzi.test.record").orElse("false").get(),
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
