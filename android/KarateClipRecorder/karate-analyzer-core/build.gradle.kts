import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `java-library`
}

apply(plugin = "org.jetbrains.kotlin.jvm")

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}
