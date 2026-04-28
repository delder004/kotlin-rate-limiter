plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
}

val examplesSourceSet = sourceSets.create("examples")

group = "io.github.delder004"
// Set by CI from the release tag via `-PreleaseVersion=…`. The SNAPSHOT
// fallback is what local builds and any non-release CI run will use; it is
// not intended to be published.
version = providers.gradleProperty("releaseVersion").getOrElse("0.3.0-SNAPSHOT")

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
    explicitApi()
    jvmToolchain(17)
}

dokka {
    moduleName.set("kotlin-rate-limiter")
    dokkaSourceSets.named("main") {
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/delder004/kotlin-rate-limiter/blob/main/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
        externalDocumentationLinks.register("kotlinx-coroutines") {
            url("https://kotlinlang.org/api/kotlinx.coroutines/")
            packageListUrl("https://kotlinlang.org/api/kotlinx.coroutines/package-list")
        }
    }
}
