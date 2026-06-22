// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    val kotlinVersion = "2.3.21"
    repositories {
        google()
        // https://jfrog.com/blog/into-the-sunset-bintray-jcenter-gocenter-and-chartcenter/
        // jcenter() is no longer getting updates, instead using mavenCentral
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.2.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        
        // add firebase plugins - will be conditionally applied in app/build.gradle
        val taskNames = gradle.startParameter.taskNames.joinToString(",").lowercase()
        val apkBuild = taskNames.contains("full")
        val fdroidBuild = taskNames.contains("fdroid")

        // check for fdroidserver value is set in system env
        val fdroidBuildServer = System.getenv("fdroidserver")
        val isFdroidBuildServer = !fdroidBuildServer.isNullOrEmpty() && fdroidBuildServer != "null"
        val deGoogled = !apkBuild || fdroidBuild || isFdroidBuildServer

        println("app-task names: '$taskNames'")
        println("app; deGoogled? $deGoogled (fdroidBuild: $fdroidBuild, fdroidBuildServer: $isFdroidBuildServer, apkBuild: $apkBuild)")

        if (!deGoogled) {
            classpath("com.google.gms:google-services:4.4.4")
            classpath("com.google.firebase:firebase-crashlytics-gradle:3.0.7")
        }
    }
}

plugins {
    id("com.google.devtools.ksp") version "2.3.9" apply false
}

allprojects {
    repositories {
        val firestackRepo = project.findProperty("firestackRepo")?.toString() ?: "github"

        when (firestackRepo) {
            "jitpack" -> {
                // jitpack.io/#celzero/firestack
                maven { url = uri("https://jitpack.io") }
            }
            "github" -> {
                // maven.pkg.github.com/celzero/firestack
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/celzero/firestack")
                    credentials {
                        username =
                            project.findProperty("gpr.user")?.toString() ?: System.getenv("USERNAME_GITHUB")
                        password =
                            project.findProperty("gpr.key")?.toString() ?: System.getenv("TOKEN_GITHUB")
                    }
                }
            }
            else -> {
                // ossrh: https://central.sonatype.com/artifact/com.celzero/firestack/
                // no-op; mavenCentral is already included
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
