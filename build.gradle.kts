// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.ben.manes.versions)
}

allprojects {
    repositories {
        google()
        mavenCentral()

        val firestackRepo = project.findProperty("firestackRepo") as? String ?: "github"

        if (firestackRepo == "jitpack") {
            // jitpack.io/#celzero/firestack
            maven("https://jitpack.io")
        } else if (firestackRepo == "github") {
            // maven.pkg.github.com/celzero/firestack
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/celzero/firestack")
                credentials {
                    username = project.findProperty("gpr.user") as? String ?: System.getenv("USERNAME_GITHUB")
                    password = project.findProperty("gpr.key") as? String ?: System.getenv("TOKEN_GITHUB")
                }
            }
        } else {
            // ossrh: https://central.sonatype.com/artifact/com.celzero/firestack/
            // no-op; mavenCentral is already included
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
