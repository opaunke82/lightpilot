// Pure Kotlin: the decision logic, testable without a Karoo or Android.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}
