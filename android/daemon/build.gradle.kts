plugins {
    `java-library`
}

val appVersion: String by extra

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    named("main") {
        java.setSrcDirs(listOf("${rootDir}/../termux/src/main/java", "${rootDir}/../shared/src/main/java"))
    }
    named("test") {
        java.setSrcDirs(listOf("${rootDir}/../termux/src/test/java", "${rootDir}/../shared/src/test/java"))
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.parser)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("nfd-termux-daemon.jar")
    manifest {
        attributes(
            "Implementation-Title" to "nfd-termux-daemon",
            "Implementation-Version" to appVersion,
            "Main-Class" to "cn.qaiu.nfd.daemon.DaemonMain",
        )
    }
}

val distDir = "${rootDir}/../termux/dist"

/** Plain classpath layout (no fat jar) so META-INF/services from Vert.x and Nashorn stay intact. */
val daemonDist by tasks.registering(Tar::class) {
    group = "distribution"
    description = "Termux daemon bundle: lib/*.jar plus the nfd-termux launcher."
    archiveFileName.set("nfd-termux-daemon-${appVersion}.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    compression = Compression.GZIP
    into("nfd-termux") {
        from(distDir) {
            include("bin/**")
            filePermissions { unix("rwxr-xr-x") }
        }
        from(distDir) {
            include("conf/**")
        }
        from(tasks.jar) { into("lib") }
        from(configurations.runtimeClasspath) { into("lib") }
        from(rootDir.resolve("../LICENSE"))
        from(rootDir.resolve("../VERSION")) {
            filter { appVersion }
        }
    }
}

val daemonBundle by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(daemonBundle.name, daemonDist)
}

val embeddedDist by tasks.registering(Zip::class) {
    archiveFileName.set("daemon.zip")
    destinationDirectory.set(layout.buildDirectory.dir("embedded"))
    from(distDir) { include("conf/**") }
    from(tasks.jar) { into("lib") }
    from(configurations.runtimeClasspath) { into("lib") }
}

val embeddedBundle by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(embeddedBundle.name, embeddedDist)
}
