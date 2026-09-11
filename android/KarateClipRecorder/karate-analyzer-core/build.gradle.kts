import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.register<JavaExec>("replayMotion") {
    group = "verification"
    description = "Replay a cached pose fixture with unchanged conservative-v1 parameters"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dk.lasse.karateanalyzer.capture.RealMotionReplayCli")
    doFirst {
        args(providers.gradleProperty("replayInput").get(), providers.gradleProperty("replayOutput").get())
    }
}
