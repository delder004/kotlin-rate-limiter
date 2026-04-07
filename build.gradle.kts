plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.delder004"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name.set("kotlin-rate-limiter")
        description.set("Coroutine-native, client-side rate limiter for Kotlin")
        url.set("https://github.com/delder004/kotlin-rate-limiter")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("delder004")
                name.set("Delos Elder")
            }
        }
        scm {
            url.set("https://github.com/delder004/kotlin-rate-limiter")
            connection.set("scm:git:git://github.com/delder004/kotlin-rate-limiter.git")
            developerConnection.set("scm:git:ssh://github.com/delder004/kotlin-rate-limiter.git")
        }
    }
}
