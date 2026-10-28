plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.detekt)
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
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.andreacovelli.abyssh"
        minSdk = 24
        targetSdk = 37

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

    val keystoreFile = file("key.jks")
    val signing = mapOf(
        "key.jks" to keystoreFile.takeIf { it.exists() }?.path,
        "KEYSTORE_PASSWORD" to System.getenv("KEYSTORE_PASSWORD"),
        "KEY_ALIAS" to System.getenv("KEY_ALIAS"),
        "KEY_PASSWORD" to System.getenv("KEY_PASSWORD"),
    )
    val missingSigning = signing.filterValues { it.isNullOrBlank() }.keys
    // Sign only when everything is present, and fail rather than ship an
    // unsigned release when only part of the signing setup is there.
    if (missingSigning.isNotEmpty() && missingSigning.size != signing.size) {
        throw GradleException("Partial release signing configuration. Missing: ${missingSigning.joinToString()}")
    }
    val releaseSigningConfigured = missingSigning.isEmpty()

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signing.getValue("KEYSTORE_PASSWORD")
                keyAlias = signing.getValue("KEY_ALIAS")
                keyPassword = signing.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("release") else null
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
    lint {
        // Nightly and release APKs ship arm64-v8a only (ANDROID_ABI_FILTERS);
        // ChromeOS and x86 devices are not supported targets.
        disable += "ChromeOsAbiSupport"
    }
    testOptions {
        // On-device tests run on a Gradle-managed emulator: ./gradlew dev34DebugAndroidTest
        // (needs the x86_64 native library in jniLibs/x86_64).
        managedDevices {
            localDevices {
                create("dev34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
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

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
    setSource(files("src/main/java", "src/test/java", "src/androidTest/java"))
    include("**/*.kt")
    reports {
        html.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
        md.required.set(false)
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
    implementation(libs.androidx.compose.material3)
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
