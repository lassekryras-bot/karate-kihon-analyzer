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

tasks.register<JavaExec>("calibrateMotion") {
    group = "verification"
    description = "Run a small explicit offline motion calibration plan"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dk.lasse.karateanalyzer.capture.MotionCalibrationCli")
    doFirst {
        args(providers.gradleProperty("replayInput").get(), providers.gradleProperty("calibrationPlan").get(),
            providers.gradleProperty("replayOutput").get())
    }
}

tasks.register<JavaExec>("continuousMotion") {
    group = "verification"
    description = "Run a continuous multi-movement session replay"
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dk.lasse.karateanalyzer.capture.ContinuousSessionCli")
    doFirst {
        val input = providers.gradleProperty("replayInput").get()
        val output = providers.gradleProperty("replayOutput").get()
        val side = providers.gradleProperty("cameraNearArmSide").orNull ?: "RIGHT"
        val cadence = providers.gradleProperty("cadence").orNull ?: "NORMAL"
        val scope = providers.gradleProperty("settlingEvidenceScope").orNull
            ?: providers.gradleProperty("scope").orNull ?: "WHOLE_BODY"

        args(listOf(input, output, side, cadence, scope))
    }
}
