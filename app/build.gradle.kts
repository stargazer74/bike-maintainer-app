plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
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

android {
    namespace = "de.bikemaintainer.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.bikemaintainer.app"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}