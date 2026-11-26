// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
    System.getProperty("caochaoxing.dumpUiDir")?.let { dir ->
        systemProperty("caochaoxing.dumpUiDir", dir)
    }
}

compose.desktop {
    application {
        mainClass = "com.caochaoxing.windows.MainKt"

        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "-Dsun.java2d.dpiaware=true",
        )

        // Keep JNA / kotlinx.serialization / Kotlin reflection intact if *Release* tasks are run.
        buildTypes.release.proguard {
            version.set("7.5.0")
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Caochaoxing"
            packageVersion = "1.1.1"
            description = "Caochaoxing Windows Desktop Client (Course Study, Groups & Sign-In)"
            vendor = "Pybsama"
            copyright = "Copyright (c) 2026 Pybsama. Licensed under AGPL-3.0-only."
            licenseFile.set(rootProject.file("../LICENSE"))

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.naming",
                "java.net.http",
                "jdk.charsets",
                "jdk.crypto.ec",
                "jdk.localedata",
                "jdk.unsupported",
            )

            windows {
                menu = true
                menuGroup = "Caochaoxing"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "4c3b0e2a-9c5d-4b27-a10f-8f4d91d7e2a4"
                iconFile.set(project.file("packaging/Caochaoxing.ico"))
            }
        }
    }
}
