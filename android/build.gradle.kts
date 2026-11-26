plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
}

val versionFile = rootDir.resolve("../VERSION")

/** `-PappVersion=0.2.0-rc.1` from CI wins; otherwise the repository VERSION file. */
val appVersion: String = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() }
    ?: versionFile.readText().trim()

allprojects {
    extra["appVersion"] = appVersion
}
