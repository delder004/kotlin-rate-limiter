plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
}

val examplesSourceSet = sourceSets.create("examples")

group = "io.github.delder004"
// Set by CI from the release tag via `-PreleaseVersion=…`. The SNAPSHOT
// fallback is what local builds and any non-release CI run will use; it is
// not intended to be published.
version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenCentral()
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    add("examplesImplementation", sourceSets.main.get().output)
    add("examplesImplementation", libs.ktor.client.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(examplesSourceSet.output)
}

tasks.test {
    useJUnitPlatform()
}

configurations.named(examplesSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get())
}

configurations.named(examplesSourceSet.compileOnlyConfigurationName) {
    extendsFrom(configurations.compileOnly.get())
}

configurations.named(examplesSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.runtimeOnly.get())
}

kotlin {
    jvmToolchain(17)
}
