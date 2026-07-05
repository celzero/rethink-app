import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.util.Properties
import java.io.FileInputStream


plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)

    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}


// apply Google Services and Firebase Crashlytics plugins conditionally
val taskNames = gradle.startParameter.taskNames.joinToString(",").lowercase()
val apkBuild = taskNames.contains("full")
val fdroidBuild = taskNames.contains("fdroid")
val fdroidBuildServer = System.getenv("fdroidserver")
val isFdroidBuildServer = !fdroidBuildServer.isNullOrEmpty() && fdroidBuildServer != "null"
val deGoogled = !apkBuild || fdroidBuild || isFdroidBuildServer

println("app-task names: '$taskNames'")
println("gradle deGoogled? $deGoogled (fdroidBuild: $fdroidBuild, fdroidBuildServer: $isFdroidBuildServer, apkBuild: $apkBuild)")

if (!deGoogled) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
    println("app firebase plugins applied")
} else {
    println("app firebase plugins SKIPPED")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

val gitVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--always")
}.standardOutput.asText.get().trim()

fun getVersionCode(project: Project): Int {
    var code = 0
    try {
        val envCode = System.getenv("VERSION_CODE")
        if (envCode != null) {
            code = Integer.parseInt(envCode)
            project.logger.info("env version code: $code")
        }
    } catch (ex: NumberFormatException) {
        project.logger.info("missing env version code: ${ex.message}")
    }
    if (code == 0) {
        code = (project.properties["VERSION_CODE"] as? String)?.toIntOrNull() ?: 0
        project.logger.info("project properties version code: $code")
    }
    return code
}

try {
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }
} catch (ex: Exception) {
    logger.info("missing keystore prop: ${ex.message}")
    keystoreProperties["keyAlias"] = ""
    keystoreProperties["keyPassword"] = ""
    keystoreProperties["storeFile"] = "/dev/null"
    keystoreProperties["storePassword"] = ""
}

android {
    compileSdk = 36
    namespace = "com.celzero.bravedns"

    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "com.celzero.bravedns"
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("config") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            val storeFilePath = keystoreProperties["storeFile"] as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
            }
            storePassword = keystoreProperties["storePassword"] as String?
        }
        create("alpha") {
            keyAlias = System.getenv("ALPHA_KS_ALIAS")
            keyPassword = System.getenv("ALPHA_KS_PASSPHRASE")
            val storeFilePath = System.getenv("ALPHA_KS_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
            }
            storePassword = System.getenv("ALPHA_KS_STORE_PASSPHRASE")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("x86", "armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }



    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
            if (!deGoogled) {
                configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                    nativeSymbolUploadEnabled = true
                }
            }
        }
        create("leakCanary") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
        }
        create("alpha") {
            applicationIdSuffix = ".alpha"
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("alpha")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    if (!deGoogled) {
        afterEvaluate {
            tasks.configureEach {
                if (name.contains("injectCrashlyticsBuildIds")) {
                    enabled = false
                    logger.warn("disabled build id injection for: $name")
                }
                if (name.contains("uploadCrashlyticsSymbolFile")) {
                    doFirst {
                        logger.info("uploading crashlytics symbols: $name")
                    }
                }
            }
        }
    }



    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf("**/*.so")
        }
    }

    flavorDimensions += listOf("releaseChannel", "releaseType")
    productFlavors {
        create("play") {
            dimension = "releaseChannel"
        }
        create("fdroid") {
            dimension = "releaseChannel"
        }
        create("website") {
            dimension = "releaseChannel"
        }
        create("full") {
            dimension = "releaseType"
            versionCode = getVersionCode(project)
            versionName = gitVersion
            vectorDrawables.useSupportLibrary = true
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        baseline = file("lint-baseline.xml")
        xmlReport = true
        htmlReport = true
        sarifReport = true
    }
}

configure<DetektExtension> {
    parallel = true
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    baseline = file("$rootDir/config/detekt/baseline.xml")
    source.setFrom(
        files(
            "src/main/java",
            "src/full/java"
        )
    )
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        txt.required.set(false)
        md.required.set(false)
    }
}

val download by configurations.creating {
    isTransitive = false
}

val firestackRepo = project.findProperty("firestackRepo") as? String ?: "github"
val firestackCommit = project.findProperty("firestackCommit") as? String ?: "main"

fun firestackDependency(suffix: String = ":debug"): String {
    return when (firestackRepo) {
        "jitpack", "github" -> "com.github.celzero:firestack:$firestackCommit$suffix@aar"
        "ossrh" -> "com.celzero:firestack:$firestackCommit$suffix@aar"
        else -> throw GradleException("Unknown firestackRepo: $firestackRepo")
    }
}

dependencies {
    implementation(libs.guava)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    "fullImplementation"(libs.kotlin.stdlib.jdk8)
    "fullImplementation"(libs.androidx.appcompat)
    "fullImplementation"(libs.androidx.core.ktx)
    implementation(libs.androidx.preference.ktx)
    "fullImplementation"(libs.androidx.constraintlayout)
    "fullImplementation"(libs.androidx.swiperefreshlayout)
    
    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.materialkolor)
    debugImplementation(libs.androidx.ui.tooling)

    "fullImplementation"(libs.kotlinx.coroutines.core)
    "fullImplementation"(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.gson)
    implementation(libs.napier)

    // Room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)

    "fullImplementation"(libs.androidx.lifecycle.viewmodel.ktx)
    "fullImplementation"(libs.androidx.lifecycle.runtime.ktx)

    // Paging
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)
    "fullImplementation"(libs.androidx.fragment.ktx)
    "fullImplementation"(libs.androidx.viewpager2)

    "fullImplementation"(libs.okhttp)
    "fullImplementation"(libs.okhttp.dnsoverhttps)

    "fullImplementation"(libs.retrofit)
    "fullImplementation"(libs.retrofit.converter.gson)

    implementation(libs.okio.jvm)

    "fullImplementation"(libs.glide) {
        exclude(group = "glide-parent")
    }
    "fullImplementation"(libs.glide.okhttp3.integration) {
        exclude(group = "glide-parent")
    }

    "kspFull"(libs.glide.compiler)

    "fullImplementation"(libs.shimmer)

    download(libs.koin.core)
    implementation(libs.koin.core)
    download(libs.koin.android)
    implementation(libs.koin.android)

    download(libs.krate)
    implementation(libs.krate)

    "fullImplementation"(libs.viewbindingpropertydelegate)
    "fullImplementation"(libs.viewbindingpropertydelegate.noreflection)

    download(firestackDependency())
    "websiteImplementation"(firestackDependency())
    "fdroidImplementation"(firestackDependency())
    "playImplementation"(firestackDependency())

    implementation(libs.androidx.work.runtime.ktx) {
        modules {
            module("com.google.guava:listenablefuture") {
                replacedBy("com.google.guava:guava", "listenablefuture is part of guava")
            }
        }
    }

    download(libs.ipaddress)
    implementation(libs.ipaddress)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockk)
    testImplementation(libs.mockk.android)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    androidTestImplementation(libs.mockk.android)

    "leakCanaryImplementation"(libs.leakcanary.android)

    "fullImplementation"(libs.androidx.navigation.fragment.ktx)
    "fullImplementation"(libs.androidx.navigation.ui.ktx)

    "fullImplementation"(libs.androidx.biometric)

    "playImplementation"(libs.play.app.update)
    "playImplementation"(libs.play.app.update.ktx)

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.security.app.authenticator)
    androidTestImplementation(libs.androidx.security.app.authenticator)

    "fullImplementation"(libs.zxing.embedded)
    "fullImplementation"(libs.recyclerview.fastscroll)
    "fullImplementation"(libs.konfetti)
    
    // lint
    lintChecks(libs.android.security.lint)

    implementation(libs.betterypermissionhelper)

    "websiteImplementation"(platform(libs.firebase.bom))
    "websiteImplementation"(libs.firebase.crashlytics)
    "websiteImplementation"(libs.firebase.crashlytics.ndk)

    "playImplementation"(platform(libs.firebase.bom))
    "playImplementation"(libs.firebase.crashlytics)
    "playImplementation"(libs.firebase.crashlytics.ndk)
}

androidComponents {
    onVariants { variant ->
        val versionCodes = mapOf(
            "armeabi-v7a" to 2,
            "arm64-v8a" to 3,
            "x86" to 8,
            "x86_64" to 9
        )
        val mainOutput = variant.outputs.singleOrNull {
            it.filters.any { filter -> filter.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
        }
        mainOutput?.let { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val baseAbiVersionCode = versionCodes[abi]
            if (baseAbiVersionCode != null) {
                // Use map to calculate version code properly from the provider
                val calculatedVersionCode = variant.outputs.first().versionCode.map { base ->
                    ((baseAbiVersionCode * 10000000) + (base ?: 0)).toInt()
                }
                output.versionCode.set(calculatedVersionCode)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xwarning-level=SENSELESS_COMPARISON:disabled")
    }
}
