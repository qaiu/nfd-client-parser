import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersion: String by extra

/** 0.2.0 -> 200, 1.12.3-rc.1 -> 11203; pre-release tags share the code of their release. */
fun versionCodeOf(version: String): Int {
    val parts = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    return parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

/** Per-ABI version code suffix; the universal APK keeps suffix 0. */
val abiCodes = linkedMapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

val releaseKeystore: String? = System.getenv("NFD_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "cn.qaiu.nfd.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.qaiu.nfd.android"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOf(appVersion).coerceAtLeast(1)
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("NFD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NFD_KEY_ALIAS")
                keyPassword = System.getenv("NFD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the release key secrets CI signs with the debug key so the APK still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    androidResources {
        noCompress += "tgz"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abiCodes.keys.toTypedArray())
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

val daemonBundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

/** Puts the Termux daemon bundle and installer into assets/termux for the in-app bootstrap server. */
abstract class DaemonAssetsTask : DefaultTask() {
    @get:InputFiles
    abstract val bundle: ConfigurableFileCollection

    @get:InputFile
    abstract val installer: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile.resolve("termux")
        target.deleteRecursively()
        target.mkdirs()
        val archive = bundle.singleFile
        // AGP unpacks *.gz assets while merging, so the bundle is stored as .tgz.
        Files.copy(archive.toPath(), target.resolve("nfd-termux-daemon.tgz").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(installer.get().asFile.toPath(), target.resolve("install.sh").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val daemonAssets = tasks.register<DaemonAssetsTask>("daemonAssets") {
    bundle.from(daemonBundle)
    installer.set(rootProject.layout.projectDirectory.file("../termux/dist/bin/install.sh"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(daemonAssets, DaemonAssetsTask::outputDir)
        val base = versionCodeOf(appVersion).coerceAtLeast(1) * 10
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
            output.versionCode.set(base + (abi?.let { abiCodes[it.identifier] } ?: 0))
        }
    }
}

dependencies {
    daemonBundle(project(path = ":daemon", configuration = "daemonBundle"))

    implementation(libs.okhttp)

    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.2.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
