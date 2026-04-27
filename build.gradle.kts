plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
}

val examplesSourceSet = sourceSets.create("examples")

group = "io.github.delder004"
version = "0.1.0"

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
    jvmToolchain(21)
}
