plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    named("main") {
        java.setSrcDirs(listOf("${rootDir}/../core/src/main/java"))
    }
    named("test") {
        java.setSrcDirs(listOf("${rootDir}/../core/src/test/java"))
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
