import com.android.build.api.dsl.Packaging
import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.rerere.rikkahub"
        minSdk = 26
        targetSdk = 37
        versionCode = 176
        versionName = "2.4.5-pale.4"

        buildConfigField("String", "UPDATE_FEED_URL", "\"https://updates.paleink.cc/api/v1/stable.json\"")
        buildConfigField("String", "UPDATE_SOURCE", "\"paleink/rikkahub\"")
        buildConfigField("String", "UPDATE_FEED_KEY_ID", "\"paleink-update-feed-rsa-2026-01\"")
        buildConfigField(
            "String",
            "UPDATE_FEED_PUBLIC_KEY",
            "\"MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAxYOPCbNWGoj1/vGqAP+N7tEE11WN+pw/oQ+M4/l++CKgzaTbO3CE4fwZAY7uHUIYI8wfO7PJuiRdVwco0ULmXaBnHXokfVVlx4jEdBhEMrTJiPTc9ZwQED2Sbv1Jrgwxt4f3ar4pjTx5MFjZ1VpYmJO1AsS+xMDs3DKiA572beLmZXXG3fFRQncocifrK99CJi9QYQCuk3fP/WTf1a692IFGVZik2IJkq7kx+lgkP3QTvQSqbNyklgga+iXG3TqFNoxKxaU62SbWp6kNCDrkE120RENZw4dMpMoXStPbxHIZcEe8obHFfsCwy6O2KrMVjS1FW4YnLLVZK5qMXGIgpccWXg/3bRvJPohgDF6+Ox8vpaGZAREwGDjICefHjUBLOHeRO4m0h2Wml5grimXjZ/sytbnSZSaeLLF9w2BMWfWNuw+agthAiuZ19efwGZtAQEe2e2vL++4Mt3YXwaIasNdQHcBko+OQsdmQ5++xF1YaiUVlOHGPkL4G3jDpx/f3AgMBAAE=\"",
        )
        buildConfigField("String", "UPDATE_PACKAGE_NAME", "\"me.rerere.rikkahub\"")
        buildConfigField(
            "String",
            "UPDATE_APK_SIGNER_SHA256",
            "\"df8c1f92039b19cfbdd72491e0058eb4682ff75f99cbbe32450fe9ea4d408520\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            val isBuildingPaleInkUniversal =
                providers.gradleProperty("paleinkUniversalOnly").orNull?.toBoolean() == true
            isEnable = !isBuildingBundle && !isBuildingPaleInkUniversal
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkDependencies = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

// Debug has its own application ID (`me.rerere.rikkahub.debug`) and deliberately
// does not send development analytics or crashes to the production Firebase app.
// Release still processes app/google-services.json normally.
tasks.configureEach {
    val isDebugFirebaseTask =
        name == "processDebugGoogleServices" ||
            (name.contains("Crashlytics") && name.endsWith("Debug"))
    if (isDebugFirebaseTask) {
        enabled = false
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view)
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase is a release-only product capability. Keeping its product SDKs
    // off the debug runtime classpath prevents automatic providers and
    // advertising/install-referrer permissions from entering development APKs.
    releaseImplementation(platform(libs.firebase.bom))
    releaseImplementation(libs.firebase.analytics)
    releaseImplementation(libs.firebase.crashlytics)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ucrop
    implementation(libs.ucrop)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Toast (Sonner)
    implementation(libs.sonner)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":workspace"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val debugRuntimeClasspath = configurations.named("${variant.name}RuntimeClasspath")
        val debugExternalRuntimeArtifacts = debugRuntimeClasspath.get()
            .incoming
            .artifactView {
                componentFilter { it is ModuleComponentIdentifier }
                attributes.attribute(
                    Attribute.of("artifactType", String::class.java),
                    "android-classes-jar",
                )
            }
            .files
        val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val verifyTask = tasks.register("verify${variant.name.replaceFirstChar(Char::uppercase)}FirebaseIsolation") {
            group = "verification"
            description =
                "Verifies that ${variant.name} excludes Firebase product telemetry while retaining ML Kit transport"
            // Keep the task action configuration-cache safe: all external
            // Gradle model values are declared as inputs here, and the action
            // reads only the resulting files instead of capturing a
            // Configuration or Provider.
            inputs.files(debugExternalRuntimeArtifacts)
                .withPropertyName("runtimeClasspath")
            inputs.file(mergedManifest)
                .withPropertyName("mergedManifest")

            doLast {
                val forbiddenModules = setOf(
                    "firebase-analytics",
                    "firebase-analytics-impl",
                    "firebase-analytics-ktx",
                    "firebase-crashlytics",
                    "firebase-crashlytics-ktx",
                    "firebase-installations",
                    "firebase-sessions",
                )
                val runtimeArtifacts = inputs.files.files
                    .asSequence()
                    .filterNot { it.name == "AndroidManifest.xml" }
                    .map { it.name.substringBeforeLast('.') }
                    .distinct()
                    .toList()
                val resolvedForbiddenModules = runtimeArtifacts
                    .filter { artifactName ->
                        forbiddenModules.any { module ->
                            artifactName == module || artifactName.startsWith("$module-")
                        } || artifactName.startsWith("play-services-measurement-")
                    }
                    .sorted()
                check(resolvedForbiddenModules.isEmpty()) {
                    "Debug runtime must not package Firebase Analytics/Crashlytics product modules: " +
                        resolvedForbiddenModules.joinToString()
                }

                check(runtimeArtifacts.any {
                    it == "transport-backend-cct" || it.startsWith("transport-backend-cct-")
                }) {
                    "Debug runtime must retain ML Kit's DataTransport CCT backend"
                }

                val manifestFile = inputs.files.files.singleOrNull { it.name == "AndroidManifest.xml" }
                    ?: error("Debug merged manifest input is missing")
                val manifest = manifestFile.readText()
                val forbiddenManifestEntries = listOf(
                    "com.google.firebase.provider.FirebaseInitProvider",
                    "com.google.firebase.crashlytics.startup.CrashlyticsInitProvider",
                    "com.google.firebase.sessions.FirebaseSessionsInitProvider",
                    "com.google.android.gms.measurement.AppMeasurementReceiver",
                    "com.google.android.gms.measurement.AppMeasurementService",
                    "com.google.android.gms.measurement.AppMeasurementJobService",
                    "com.google.android.gms.permission.AD_ID",
                    "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
                ).filter(manifest::contains)
                check(forbiddenManifestEntries.isEmpty()) {
                    "Debug merged manifest contains Firebase product initialization/advertising entries: " +
                        forbiddenManifestEntries.joinToString()
                }

                val requiredTransportEntries = listOf(
                    "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
                    "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
                    "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
                ).filterNot(manifest::contains)
                check(requiredTransportEntries.isEmpty()) {
                    "Debug merged manifest must retain ML Kit DataTransport components: " +
                        requiredTransportEntries.joinToString()
                }
            }
        }

        tasks.named("check").configure { dependsOn(verifyTask) }
        tasks.named("lint").configure { dependsOn(verifyTask) }
    }
}
