plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Same classes the desktop module compiles, against the Android parser artifact.
    api(libs.parser.android)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

sourceSets {
    named("main") {
        java.setSrcDirs(listOf("${rootDir}/../shared/src/main/java"))
    }
    named("test") {
        java.setSrcDirs(listOf("${rootDir}/../shared/src/test/java"))
    }
}

tasks.test {
    useJUnitPlatform()
}
