import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

// apply Google Services and Firebase Crashlytics plugins conditionally
// strategy: For command-line builds, check task names. For IDE, always apply plugins,
// but they'll only process play/website variants (Firebase deps are scoped to those variants)
val taskNames = gradle.startParameter.taskNames.joinToString(",").lowercase()
val apkBuild = taskNames.contains("full")
val fdroidBuild = taskNames.contains("fdroid")
// for alpha builds generate universal apk only
val alphaBuild = taskNames.contains("alpha")

// check for fdroidserver value is set in system env
val fdroidBuildServer: String? = System.getenv("fdroidserver")
val isFdroidBuildServer = !fdroidBuildServer.isNullOrEmpty() && fdroidBuildServer != "null"
val deGoogled = !apkBuild || fdroidBuild || isFdroidBuildServer
val shouldSplit = !alphaBuild

println("app-task names: '$taskNames'")
println("gradle deGoogled? $deGoogled (fdroidBuild: $fdroidBuild, fdroidBuildServer: $isFdroidBuildServer, apkBuild: $apkBuild)")
println("gradle alphaBuild? $alphaBuild, should split? $shouldSplit")

// don't apply firebase plugins for fdroid CLI builds
if (!deGoogled) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
    println("app firebase plugins applied")
} else {
    println("app firebase plugins SKIPPED")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

val abiVersionCodes = mapOf(
    "armeabi-v7a" to 2,
    "arm64-v8a" to 3,
    "x86" to 8,
    "x86_64" to 9
)

// https://github.com/celzero/rethink-app/issues/1032
// https://docs.gradle.org/8.2/userguide/configuration_cache.html#config_cache:requirements:external_processes
// get the git version from the command line
val gitVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--always")
}.standardOutput.asText.get().trim()

// for GitHub builds, the version code is set in the GitHub action via env
// for local builds, the version code is set in gradle.properties
fun getVersionCode(): Int {
    var code = 0
    try {
        val envCode = System.getenv("VERSION_CODE")
        if (!envCode.isNullOrEmpty()) {
            code = envCode.toInt()
            logger.info("env version code: $code")
        }
    } catch (ex: NumberFormatException) {
        logger.info("missing env version code: ${ex.message}")
    }
    if (code == 0) {
        code = project.properties["VERSION_CODE"]?.toString()?.toInt() ?: 0
        logger.info("project properties version code: $code")
    }
    return code
}

try {
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }
} catch (ex: Exception) {
    logger.info("missing keystore prop: ${ex.message}")
}

// ref: developer.android.com/build/jdks#target-compat
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    compileSdk = 37
    // https://developer.android.com/studio/build/configure-app-module
    namespace = "com.celzero.bravedns"

    defaultConfig {
        applicationId = "com.celzero.bravedns"
        minSdk = 23
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("config") {
            keyAlias = keystoreProperties.getProperty("keyAlias", "")
            keyPassword = keystoreProperties.getProperty("keyPassword", "")
            storeFile = keystoreProperties.getProperty("storeFile")?.let { file(it) } ?: file("/dev/null")
            storePassword = keystoreProperties.getProperty("storePassword", "")
        }
        // archive.is/wlwD8
        create("alpha") {
            keyAlias = System.getenv("ALPHA_KS_ALIAS")
            keyPassword = System.getenv("ALPHA_KS_PASSPHRASE")
            // https://stackoverflow.com/a/34640602
            storeFile = System.getenv("ALPHA_KS_FILE")?.let { file(it) }
            storePassword = System.getenv("ALPHA_KS_STORE_PASSPHRASE")
        }
    }

    // https://developer.android.com/studio/build/configure-apk-splits
    // alpha builds produce a single universal apk
    // release builds produce split apk and universal apk
    splits {
        abi {
            if (!shouldSplit) {
                println("universal apk only (splits disabled)")
                isEnable = false
            } else {
                println("split apks and universal apk (splits enabled)")
                isEnable = true
                reset()
                // comma-separated list of ABIs to generate apks for
                include("x86", "armeabi-v7a", "arm64-v8a", "x86_64")
                // generates a universal APK in addition to per-ABI apks
                isUniversalApk = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            // modified as part of #352, now webview is removed from app, flipping back
            // the setting to true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                // Use SYMBOL_TABLE to reduce symbol file size significantly
                debugSymbolLevel = "SYMBOL_TABLE"
                // Only process symbols for the most common ABIs to avoid Crashlytics index errors
                // This reduces the total symbol data Crashlytics needs to process
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
            }
            if (!deGoogled) {
                // nativeSymbolUploadEnabled is only available when the crashlytics plugin is applied
                // Since it's applied conditionally, we use the string-based API to configure it
                // to avoid compilation errors when the plugin is not applied or its classes are not visible
                val crashlyticsExtension = extensions.findByName("firebaseCrashlytics")
                if (crashlyticsExtension != null) {
                    val method = crashlyticsExtension.javaClass.getMethod("setNativeSymbolUploadEnabled", Boolean::class.javaPrimitiveType)
                    method.invoke(crashlyticsExtension, true)
                }
            }
        }
        create("leakCanary") {
            matchingFallbacks += listOf("debug")
            initWith(getByName("debug"))
        }
        create("alpha") {
            // archive.is/y8uCB
            applicationIdSuffix = ".alpha"
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("alpha")
            resValue("string", "app_name", "Rethink(α)")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // fix: injectCrashlyticsBuildIds task has a buffer overflow bug
    // apply crashlytics configuration to firebase builds only
    if (!deGoogled) {
        // workaround for crashlytics gradle plugin bug with large native symbols
        afterEvaluate {
            tasks.configureEach {
                if (name.contains("injectCrashlyticsBuildIds")) {
                    enabled = false
                    logger.warn("disabled build id injection for: $name (workaround for IndexOutOfBoundsException)")
                    logger.warn("native symbols will still be uploaded via uploadCrashlyticsSymbolFile task")
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
        resValues = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
        }
        // required for Google Play developer api model classes
        resources {
            excludes.add("META-INF/INDEX.LIST")
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
            // getPackageInfo().versionCode not returning the correct value (in prod builds) when
            // value is set in AndroidManifest.xml so setting it here
            // for build type alpha, versionCode is set in env overriding gradle.properties
            versionCode = getVersionCode()
            versionName = gitVersion
            vectorDrawables.useSupportLibrary = true
        }
    }
    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-Xwarning-level=SENSELESS_COMPARISON:disabled")
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            if (abi != null) {
                // ABI-specific output: assign the correct per-ABI version code
                // eg for arm64-v8a: 3 * 10000000 + versionCode
                val multiplier = abiVersionCodes[abi] ?: 0
                val v = multiplier * 10000000 + (output.versionCode.get())
                output.versionCode.set(v)
            } else {
                // universal APK: preserve existing production behaviour (arm64-v8a multiplier)
                // so installed universal APKs are not treated as a downgrade
                // when splits are disabled (e.g. alpha builds), firstAbi is null; fall back to
                // the arm64-v8a multiplier (3) so versionCode arithmetic still works
                val firstAbi =
                    variant.outputs.find { it.filters.any { f -> f.filterType == FilterConfiguration.FilterType.ABI } }
                        ?.filters?.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
                val multiplier = if (firstAbi != null) abiVersionCodes[firstAbi] ?: 3 else 3
                val v = multiplier * 10000000 + (output.versionCode.get())
                output.versionCode.set(v)
            }
        }
    }
}

val download by configurations.creating {
    isTransitive = false
}

val firestackRepo = project.findProperty("firestackRepo")?.toString() ?: "github"
val firestackCommit = project.findProperty("firestackCommit")?.toString() ?: "main"

fun firestackDependency(): String {
    return when (firestackRepo) {
        "jitpack" -> "com.github.celzero:firestack:$firestackCommit@aar"
        "github" -> "com.github.celzero:firestack:$firestackCommit@aar"
        "ossrh" -> "com.celzero:firestack:$firestackCommit@aar"
        else -> throw GradleException("Unknown firestackRepo: $firestackRepo")
    }
}

dependencies {
    val roomVersion = "2.8.4"
    val pagingVersion = "3.5.0"

    implementation("com.google.guava:guava:33.6.0-android")

    // https://developer.android.com/studio/write/java8-support
    // included to fix issues with Android 6 support, issue#563
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    "fullImplementation"("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21")
    "fullImplementation"("androidx.appcompat:appcompat:1.7.1")
    "fullImplementation"("androidx.core:core-ktx:1.19.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    "fullImplementation"("androidx.constraintlayout:constraintlayout:2.2.1")
    "fullImplementation"("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")

    "fullImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    "fullImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // LiveData
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.10.0")
    implementation("com.google.code.gson:gson:2.14.0")

    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-paging:$roomVersion")

    "fullImplementation"("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    "fullImplementation"("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    // Pagers Views
    implementation("androidx.paging:paging-runtime-ktx:$pagingVersion")
    "fullImplementation"("androidx.fragment:fragment-ktx:1.8.9")
    implementation("com.google.android.material:material:1.14.0")
    "fullImplementation"("androidx.viewpager2:viewpager2:1.1.0")

    "fullImplementation"("com.squareup.okhttp3:okhttp:5.3.2")
    "fullImplementation"("com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2")
    "fullImplementation"("com.squareup.okhttp3:logging-interceptor:5.3.2")

    "fullImplementation"("com.squareup.retrofit2:retrofit:3.0.0")
    "fullImplementation"("com.squareup.retrofit2:converter-gson:3.0.0")

    implementation("com.squareup.okio:okio-jvm:3.17.0")
    // Glide
    "fullImplementation"("com.github.bumptech.glide:glide:5.0.7") {
        exclude(group = "glide-parent")
    }
    "fullImplementation"("com.github.bumptech.glide:okhttp3-integration:5.0.7") {
        exclude(group = "glide-parent")
    }

    // Ref: https://stackoverflow.com/a/46638213
    "kspFull"("com.github.bumptech.glide:compiler:5.0.7")
    // Swipe button animation
    "fullImplementation"("com.facebook.shimmer:shimmer:0.5.0")

    // Koin core
    download("io.insert-koin:koin-core:4.2.1")
    implementation("io.insert-koin:koin-core:4.2.1")
    // Koin main (Scope, ViewModel ...)
    download("io.insert-koin:koin-android:4.2.1")
    implementation("io.insert-koin:koin-android:4.2.1")

    download("hu.autsoft:krate:2.0.0")
    implementation("hu.autsoft:krate:2.0.0")

    // viewBinding without reflection
    "fullImplementation"("com.github.kirich1409:viewbindingpropertydelegate:1.5.9")
    "fullImplementation"("com.github.kirich1409:viewbindingpropertydelegate-noreflection:1.5.9")

    // add ":debug" suffix to the dependency to include debug symbols
    download(firestackDependency())
    "websiteImplementation"(firestackDependency())
    "fdroidImplementation"(firestackDependency())
    "playImplementation"(firestackDependency())

    // Work manager
    implementation("androidx.work:work-runtime-ktx:2.11.2") {
        modules {
            module("com.google.guava:listenablefuture") {
                replacedBy("com.google.guava:guava", "listenablefuture is part of guava")
            }
        }
    }

    // for handling IP addresses and subnets, both IPv4 and IPv6
    // seancfoley.github.io/IPAddress/ipaddress.html
    download("com.github.seancfoley:ipaddress:5.6.2")
    implementation("com.github.seancfoley:ipaddress:5.6.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    // Added test dependencies for comprehensive testing
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("io.mockk:mockk-android:1.14.11")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.insert-koin:koin-test:4.2.1")
    testImplementation("io.insert-koin:koin-test-junit4:4.2.1")
    androidTestImplementation("io.mockk:mockk-android:1.14.11")

    "leakCanaryImplementation"("com.squareup.leakcanary:leakcanary-android:2.14")

    "fullImplementation"("androidx.navigation:navigation-fragment-ktx:2.9.8")
    "fullImplementation"("androidx.navigation:navigation-ui-ktx:2.9.8")

    "fullImplementation"("androidx.biometric:biometric:1.1.0")

    "playImplementation"("com.google.android.play:app-update:2.1.0")
    "playImplementation"("com.google.android.play:app-update-ktx:2.1.0")

    // for encrypting wireguard configuration files
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.security:security-app-authenticator:1.0.0")
    androidTestImplementation("androidx.security:security-app-authenticator:1.0.0")

    // barcode scanner for wireguard
    "fullImplementation"("com.journeyapps:zxing-android-embedded:4.3.0")
    "fullImplementation"("com.simplecityapps:recyclerview-fastscroll:2.0.1")

    // for confetti animation
    "fullImplementation"("nl.dionsegijn:konfetti-xml:2.0.5")

    constraints {
        implementation("androidx.annotation:annotation-experimental:1.6.0")
    }

    // for in-app purchases
    "playImplementation"("com.android.billingclient:billing:8.3.0")
    "websiteImplementation"("com.android.billingclient:billing:8.3.0")
    // for stripe payment gateway
    //"websiteImplementation"("com.stripe:stripe-android:21.21.0)"
    //"fdroidImplementation"("com.stripe:stripe-android:21.21.0)"

    // Google Play developer api model classes (ProductPurchaseV2, SubscriptionPurchaseV2, …)
    // Only model classes + GsonFactory are needed, Apache HTTP transport is excluded.
    // The version v3-rev20240301-2.0.0 cited in the API docs does not exist on Maven Central;
    // v3-rev20260318-2.0.0 is the closest available release with the same model classes.
    // ref: github.com/googleapis/google-api-java-client-services/tree/main/clients/google-api-services-androidpublisher/v3
    "playImplementation"("com.google.apis:google-api-services-androidpublisher:v3-rev20260318-2.0.0") {
        // Exclude Apache HTTP transport, conflicts with Android's built-in HTTP stack
        exclude(group = "com.google.http-client", module = "google-http-client-apache-v2")
        exclude(group = "org.apache.httpcomponents")
        // Exclude OAuth, not needed for model-only parsing
        exclude(group = "com.google.oauth-client")
    }
    "websiteImplementation"("com.google.apis:google-api-services-androidpublisher:v3-rev20260318-2.0.0") {
        exclude(group = "com.google.http-client", module = "google-http-client-apache-v2")
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "com.google.oauth-client")
    }

    lintChecks("com.android.security.lint:lint:1.0.4")

    // battery optimization permission helper
    implementation("com.waseemsabir:betterypermissionhelper:1.0.3")

    // Firebase dependencies for error reporting (website and play variants only)
    "websiteImplementation"(platform("com.google.firebase:firebase-bom:34.14.1"))
    "websiteImplementation"("com.google.firebase:firebase-crashlytics")
    "websiteImplementation"("com.google.firebase:firebase-crashlytics-ndk")

    "playImplementation"(platform("com.google.firebase:firebase-bom:34.14.1"))
    "playImplementation"("com.google.firebase:firebase-crashlytics")
    "playImplementation"("com.google.firebase:firebase-crashlytics-ndk")
}
