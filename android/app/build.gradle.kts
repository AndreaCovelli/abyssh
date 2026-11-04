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

data class AppVersion(val name: String, val code: Int)

// versionCode is MMmmpp * 1000 plus the commits since that tag.
fun versionCode(major: Int, minor: Int, patch: Int, commits: Int = 0): Int =
    (major * 10_000 + minor * 100 + patch) * 1_000 + commits.coerceAtMost(999)

// Release builds pass VERSION_MAJOR, VERSION_MINOR, and VERSION_PATCH from their tag.
// Other builds take the version from `git describe`: four commits after v0.8.13 give
// 0.8.13-4-g10c8b68 with versionCode 813004, above v0.8.13 and below v0.8.14, so a
// release installs over a local build and the other way round.
fun appVersion(): AppVersion {
    val fromEnv = listOf("VERSION_MAJOR", "VERSION_MINOR", "VERSION_PATCH").map { System.getenv(it)?.toIntOrNull() }
    if (fromEnv.all { it != null }) {
        val (major, minor, patch) = fromEnv.map { it!! }
        return AppVersion("$major.$minor.$patch", versionCode(major, minor, patch))
    }
    // Without git or a release tag, as in a shallow clone, fall back to a dev version.
    val output = runCatching {
        providers.exec {
            commandLine("git", "describe", "--tags", "--long", "--dirty", "--match", "v[0-9]*.[0-9]*.[0-9]*")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
    val match = Regex("""v(\d+)\.(\d+)\.(\d+)-(\d+)-g([0-9a-f]+)(-dirty)?""").matchEntire(output)
        ?: return AppVersion("0.0.0-dev", 1)
    val (major, minor, patch, commits) = match.groupValues.drop(1).take(4).map { it.toInt() }
    val exact = commits == 0 && match.groupValues[6].isEmpty()
    val name = if (exact) "$major.$minor.$patch" else "$major.$minor.$patch-" + output.substringAfter('-')
    return AppVersion(name, versionCode(major, minor, patch, commits))
}

// The build type the instrumented tests run against: debug, or minified with
// -PtestBuildType=minified.
val instrumentedBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")

room {
    schemaDirectory("$projectDir/schemas")
}

// Robolectric fetches its Android runtime jar from Maven Central on first use, outside
// Gradle, with no retry and into ~/.m2, which CI does not cache. Resolve it through
// Gradle instead and copy it where Robolectric's offline mode looks for it.
val robolectricRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val robolectricRuntimeDir = layout.buildDirectory.dir("robolectric-runtime")
val robolectricRuntimeJars = tasks.register<Sync>("robolectricRuntimeJars") {
    from(robolectricRuntime)
    into(robolectricRuntimeDir)
}

android {
    namespace = "io.github.andreacovelli.abyssh"
    compileSdk {
        version = release(37)
    }
    // Strips the native library and extracts its symbols. Keep in step with
    // NDK_VERSION in .github/workflows/release.yml and mise-tasks/check/native.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.andreacovelli.abyssh"
        minSdk = 24
        targetSdk = 37

        val version = appVersion()
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: version.code
        versionName = System.getenv("VERSION_NAME") ?: version.name

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

    // Release signing comes from the environment. KEYSTORE_PATH points at a keystore kept
    // outside the repository.
    val signing = mapOf(
        "KEYSTORE_PATH" to System.getenv("KEYSTORE_PATH"),
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
                storeFile = file(signing.getValue("KEYSTORE_PATH")!!)
                storePassword = signing.getValue("KEYSTORE_PASSWORD")
                keyAlias = signing.getValue("KEY_ALIAS")
                keyPassword = signing.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // A separate application ID lets the debug build sit next to an installed release
        // build without replacing it and its saved hosts and keys.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseSigningConfigured) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The native library is built unstripped. AGP strips the packaged copy and
            // puts the symbols in the AAB and in native-debug-symbols.zip, which the
            // release workflow attaches to the GitHub release.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        // The release build with debug signing, for instrumented tests that check R8
        // doesn't remove or rename what the native code calls by name. Run with
        // ./gradlew -PtestBuildType=minified dev34MinifiedAndroidTest, which runs only
        // src/androidTest/minified, since R8 removes the app code the other tests call.
        create("minified") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minified"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFiles("proguard-minified-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
    }
    testBuildType = instrumentedBuildType
    sourceSets {
        getByName("androidTest") {
            // The debug run includes the minified smoke tests, so they keep working.
            if (instrumentedBuildType == "minified") {
                kotlin.directories.clear()
                java.directories.clear()
            }
            kotlin.directories.add("src/androidTest/minified/java")
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
        // Release APKs ship arm64-v8a only (ANDROID_ABI_FILTERS);
        // ChromeOS and x86 devices are not supported targets.
        disable += "ChromeOsAbiSupport"
    }
    testOptions {
        animationsDisabled = true
        // On-device tests run on a Gradle-managed emulator: ./gradlew dev34DebugAndroidTest
        // (needs the x86_64 native library in jniLibs/x86_64).
        managedDevices {
            localDevices {
                create("dev34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                    testedAbi = "x86_64"
                }
            }
        }
        unitTests {
            // Robolectric needs the merged resources to resolve app resources in tests.
            isIncludeAndroidResources = true
            all { test ->
                // Run Robolectric offline against the Android runtime jar Gradle resolved,
                // so it comes from the Gradle cache rather than a separate download.
                test.inputs.files(robolectricRuntimeJars)
                    .withPropertyName("robolectricRuntimeJars")
                    .withPathSensitivity(PathSensitivity.NONE)
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", robolectricRuntimeDir.get().asFile.absolutePath)
                // Robolectric tests are slow to start; spread them over half the cores.
                test.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
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
    setSource(files("src/main/java", "src/test/java", "src/androidTest/java", "src/androidTest/minified/java"))
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
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    robolectricRuntime(libs.robolectric.android.all)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // The minified smoke tests need only JUnit and AndroidX Test.
    if (instrumentedBuildType != "minified") {
        androidTestImplementation(libs.room.testing)
        androidTestImplementation(platform(libs.androidx.compose.bom))
        androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    }
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
