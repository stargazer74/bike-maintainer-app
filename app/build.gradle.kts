plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.kover)
}

// The API client is generated from the OpenAPI spec in the sibling repo bike-maintainer-api (contract-first); the
// generated code is never committed. Another location can be given with -Pbm.apiSpec=<path>.
val apiSpec = providers.gradleProperty("bm.apiSpec")
    .orElse(rootDir.resolve("../bike-maintainer-api/src/main/resources/maintenance-api.yaml").path)
val generatedApiDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName = "kotlin"
    library.set("jvm-retrofit2")
    inputSpec = apiSpec
    outputDir = generatedApiDir.map { it.asFile.path }
    packageName = "de.bikemaintainer.app.api"
    apiPackage = "de.bikemaintainer.app.api.apis"
    modelPackage = "de.bikemaintainer.app.api.models"
    generateApiDocumentation = false
    generateModelDocumentation = false
    generateApiTests = false
    generateModelTests = false
    configOptions = mapOf(
        "serializationLibrary" to "kotlinx_serialization",
        "useCoroutines" to "true",
        "dateLibrary" to "java8",
        "enumPropertyNaming" to "UPPERCASE",
    )
    // Only APIs, models and the serializer with its adapters: the generated ApiClient.kt would need
    // logging-interceptor and scalars converter, and ApiKeyAuth is unused; the app builds OkHttp/Retrofit itself.
    globalProperties = mapOf(
        "apis" to "",
        "models" to "",
        "supportingFiles" to "Serializer.kt,CollectionFormats.kt,BigDecimalAdapter.kt,BigIntegerAdapter.kt," +
            "LocalDateAdapter.kt,LocalDateTimeAdapter.kt,OffsetDateTimeAdapter.kt,UUIDAdapter.kt," +
            "AtomicIntegerAdapter.kt,AtomicLongAdapter.kt,AtomicBooleanAdapter.kt,URIAdapter.kt,URLAdapter.kt," +
            "StringBuilderAdapter.kt",
    )
}

// A separate task, because the generator's own input validation fails with a less helpful message first.
val checkApiSpec by tasks.registering {
    val specPath = apiSpec
    doLast {
        check(File(specPath.get()).exists()) {
            "API spec not found at ${specPath.get()} – check out bike-maintainer-api next to this repo " +
                "or pass -Pbm.apiSpec=…"
        }
    }
}

tasks.named("openApiGenerate") {
    dependsOn(checkApiSpec)
}

tasks.named("preBuild") {
    dependsOn("openApiGenerate")
}

// Release version from -Pbm.version=X.Y.Z (scripts/release.sh takes it from the tag vX.Y.Z). versionCode must rise
// strictly across GitHub and Play, so it is derived from the version: X·1 000 000 + Y·1 000 + Z (below Play's limit
// of 2 100 000 000). Without the property: a development version that any release can update.
val appVersion: Pair<String, Int> = providers.gradleProperty("bm.version").orNull.let { version ->
    if (version == null) return@let "0.0.0-dev" to 1
    val match = Regex("""(\d{1,4})\.(\d{1,3})\.(\d{1,3})""").matchEntire(version)
        ?: error("bm.version must be X.Y.Z (major up to 2099, minor/patch up to 999), got '$version'")
    val (major, minor, patch) = match.destructured.toList().map(String::toInt)
    check(major <= 2099) { "bm.version major must be at most 2099, got $major" }
    version to major * 1_000_000 + minor * 1_000 + patch
}

// Signing key only from the environment (scripts/release.sh asks for the password); nothing in the repo or in
// local.properties. Without it the release build stays unsigned, so builds without the key (CI, contributors) work.
val releaseKeystore: String? = providers.environmentVariable("BM_KEYSTORE_FILE").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("BM_KEYSTORE_PASSWORD").orNull

android {
    namespace = "de.bikemaintainer.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.bikemaintainer.app"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersion.second
        versionName = appVersion.first

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystorePassword != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeystorePassword
                keyAlias = providers.environmentVariable("BM_KEY_ALIAS").orElse("bike-maintainer").get()
                // PKCS12 keystores use the store password for the key as well.
                keyPassword = providers.environmentVariable("BM_KEY_PASSWORD").orElse(releaseKeystorePassword).get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG decides e.g. whether HTTP logging may be enabled.
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    lint {
        // Lint covers Android security checks (exported components, cleartext traffic, …); findings fail the build.
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        sarifReport = true
        // Version freshness is Dependabot's job; as lint errors these would break the build with every new release.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    sourceSets["main"].kotlin.directories.add(generatedApiDir.map { it.dir("src/main/kotlin").asFile.path }.get())
}

// Exact versions of what ships in the APK (runtime classpaths) in gradle.lockfile, so that Trivy can scan them. Build
// tools (lint, compiler, test runners) never reach a phone and are kept out of the scan.
// After changing dependencies: ./gradlew :app:dependencies --write-locks
configurations.matching { it.name.endsWith("RuntimeClasspath") && !it.name.contains("Test") }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Robolectric reflects into JDK internals, which newer JDKs close by default.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
    )
}

// Coverage gate: data and domain layers need >= 90 % line coverage; generated API code and the Keystore-bound cipher
// (needs real Android Keystore, no JVM unit test possible) are excluded.
kover {
    reports {
        filters {
            includes {
                packages("de.bikemaintainer.app.data", "de.bikemaintainer.app.domain")
            }
            excludes {
                packages("de.bikemaintainer.app.api")
                classes("*KeystoreTokenCipher*")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
