import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    // rethink-tv fork: Compose Compiler plugin for the `tv` flavor's
    // Compose-for-TV UI. Safe to apply project-wide — phone variants
    // contain no @Composable and the plugin then no-ops.
    id("org.jetbrains.kotlin.plugin.compose")
    // To generate a BOM in CycloneDX format:
    // ./gradlew cyclonedxBom
    // id("org.cyclonedx.bom") version "3.2.4"
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
val deGoogled = !apkBuild || fdroidBuild || isFdroidBuildServer || alphaBuild
val shouldSplit = !alphaBuild

// Pass -PwebsiteDegoogled=true when building the fdroid flavor with our own keys.
// Official F-Droid builds omit this flag and will be labeled as "fdroid".
val isWebsiteDegoogled = providers.gradleProperty("websiteDegoogled")
    .orNull?.toBoolean() ?: false

// Add google-services.json only for play/website builds.
// local dev: copy from the sibling ../firebase/{debug,release} directory (gitignored, outside repo).
// CI: the secret is written to app/src/google-services.json by GitHub Actions, so no local copy is needed.
if (!deGoogled) {
    val isRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
    val buildTypeName = if (isRelease) "release" else "debug"
    val sourceFile = file("${rootDir.parent}/firebase/$buildTypeName/google-services.json")
    val targetFile = file("$projectDir/google-services.json")

    when {
        sourceFile.exists() -> {
            sourceFile.copyTo(targetFile, overwrite = true)
            logger.lifecycle("google-services.json: copied from $sourceFile")
        }
        targetFile.exists() -> logger.lifecycle("google-services.json: using existing $targetFile")
        else -> {
            val srcDir = file("src")
            val hasSrcSetFile = srcDir.listFiles()?.any {
                it.isDirectory && File(it, "google-services.json").exists()
            } == true
            if (hasSrcSetFile || file("src/google-services.json").exists()) {
                logger.lifecycle("google-services.json: no local copy; deferring to google-services plugin (variant source-set resolution, e.g. CI)")
            } else {
                throw GradleException(
                    "google-services.json not found for '$buildTypeName' build.\n" +
                        "  Local dev : place it at $sourceFile\n" +
                        "             (or directly at $targetFile)\n" +
                        "  CI        : ensure the secret is written to app/src/google-services.json"
                )
            }
        }
    }
} else {
    logger.info("skipping google-services.json for de-googled/F-Droid build")
}

logger.info("app-task names: '$taskNames'")
logger.info("gradle deGoogled? $deGoogled (fdroidBuild: $fdroidBuild, fdroidBuildServer: $isFdroidBuildServer, apkBuild: $apkBuild)")
logger.info("gradle alphaBuild? $alphaBuild, should split? $shouldSplit")

// don't apply firebase plugins for fdroid CLI builds
if (!deGoogled) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
    logger.info("app firebase plugins applied")
} else {
    logger.info("app firebase plugins SKIPPED")
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

val appVersionCode = getVersionCode()

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

val tvKsAlias: String? = System.getenv("TV_RELEASE_KS_ALIAS")
val tvKsPassphrase: String? = System.getenv("TV_RELEASE_KS_PASSPHRASE")
val tvKsFile: String? = System.getenv("TV_RELEASE_KS_FILE")
val tvKsStorePassphrase: String? = System.getenv("TV_RELEASE_KS_STORE_PASSPHRASE")
val hasTvReleaseSigningConfig =
    listOf(tvKsAlias, tvKsPassphrase, tvKsFile, tvKsStorePassphrase)
        .all { value -> !value.isNullOrEmpty() }

android {
    compileSdk = 37
    // https://developer.android.com/studio/build/configure-app-module
    namespace = "com.celzero.bravedns"

    defaultConfig {
        applicationId = "com.celzero.bravedns"
        minSdk = 23
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Defaults to false; the fdroid flavor overrides it via -PwebsiteDegoogled=true.
        buildConfigField("boolean", "IS_WEBSITE_DEGOOGLD_BUILD", "false")
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
        create("izzyondroid") {
            keyAlias = System.getenv("IZZYONDROID_KS_ALIAS")
            keyPassword = System.getenv("IZZYONDROID_KS_PASSWORD")
            storeFile = System.getenv("IZZYONDROID_KS_FILE")?.let { file(it) }
            storePassword = System.getenv("IZZYONDROID_KS_PASSWORD")
        }
    }

    // https://developer.android.com/studio/build/configure-apk-splits
    // alpha builds produce a single universal apk
    // release builds produce split apk and universal apk
    splits {
        abi {
            if (!shouldSplit) {
                logger.info("universal apk only (splits disabled)")
                isEnable = false
            } else {
                logger.info("split apks and universal apk (splits enabled)")
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
            signingConfig = if (isWebsiteDegoogled) {
                logger.info("IzzyOnDroid build: using izzyondroid signing config")
                signingConfigs.getByName("izzyondroid")
            } else {
                logger.info("Normal build: using config signing config")
                signingConfigs.getByName("config")
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
        create("releaseDebug") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
            }
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
        compose = true
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
            // true only when built with -PwebsiteDegoogled=true (our own keys).
            buildConfigField("boolean", "IS_WEBSITE_DEGOOGLD_BUILD", isWebsiteDegoogled.toString())
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
            buildConfigField("int", "BASE_VERSION_CODE", appVersionCode.toString())
            vectorDrawables.useSupportLibrary = true
        }
        create("tv") {
            dimension = "releaseType"
            applicationIdSuffix = ".tv"
            versionCode = getVersionCode()
            versionName = gitVersion
            buildConfigField("int", "BASE_VERSION_CODE", appVersionCode.toString())
            vectorDrawables.useSupportLibrary = true
        }
    }
    sourceSets {
        getByName("main").res.directories.add("src/full/res")
        getByName("full").res.directories.clear()
    }
    lint {
        abortOnError = true
    }

    if (hasTvReleaseSigningConfig) {
        val tvRelease = signingConfigs.create("tvRelease") {
            keyAlias = tvKsAlias
            keyPassword = tvKsPassphrase
            storeFile = file(requireNotNull(tvKsFile))
            storePassword = tvKsStorePassphrase
        }
        buildTypes.getByName("release").signingConfig = tvRelease
        println("rethink-tv: TV_RELEASE_KS_* env vars detected; 'release' build type will be signed with signingConfigs.tvRelease")
    } else {
        println("rethink-tv: TV_RELEASE_KS_* env vars NOT set; using the configured release signing config")
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
                val firstAbi = variant.outputs
                    .firstOrNull { candidate ->
                        candidate.filters.any { it.filterType == FilterConfiguration.FilterType.ABI }
                    }
                    ?.filters
                    ?.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                    ?.identifier
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

    implementation(libs.googleGuavaGuava)

    // https://developer.android.com/studio/write/java8-support
    // included to fix issues with Android 6 support, issue#563
    coreLibraryDesugaring(libs.androidToolsDesugarJdkLibs)

    "fullImplementation"(libs.jetbrainsKotlinKotlinStdlibJdk8)
    "fullImplementation"(libs.androidxAppcompatAppcompat)
    "fullImplementation"(libs.androidxCoreCoreKtx)
    implementation(libs.androidxPreferencePreferenceKtx)
    "fullImplementation"(libs.androidxConstraintlayoutConstraintlayout)
    "fullImplementation"(libs.androidxSwiperefreshlayoutSwiperefreshlayout)

    "fullImplementation"(libs.jetbrainsKotlinxKotlinxCoroutinesCore)
    "fullImplementation"(libs.jetbrainsKotlinxKotlinxCoroutinesAndroid)

    // LiveData
    implementation(libs.androidxLifecycleLifecycleLivedataKtx)
    implementation(libs.googleCodeGsonGson)

    implementation(libs.androidxRoomRoomRuntime)
    ksp(libs.androidxRoomRoomCompiler)
    implementation(libs.androidxRoomRoomKtx)
    implementation(libs.androidxRoomRoomPaging)

    "fullImplementation"(libs.androidxLifecycleLifecycleViewmodelKtx)
    "fullImplementation"(libs.androidxLifecycleLifecycleRuntimeKtx)

    // Pagers Views
    implementation(libs.androidxPagingPagingRuntimeKtx)
    "fullImplementation"(libs.androidxFragmentFragmentKtx)
    implementation(libs.googleAndroidMaterialMaterial)
    "fullImplementation"(libs.androidxViewpager2Viewpager2)

    "fullImplementation"(libs.squareupOkhttp3Okhttp)
    "fullImplementation"(libs.squareupOkhttp3OkhttpDnsoverhttps)
    "fullImplementation"(libs.squareupOkhttp3LoggingInterceptor)

    "fullImplementation"(libs.squareupRetrofit2Retrofit)
    "fullImplementation"(libs.squareupRetrofit2ConverterGson)

    implementation(libs.squareupOkioOkioJvm)
    // Glide
    "fullImplementation"(libs.githubBumptechGlideGlide) {
        exclude(group = "glide-parent")
    }
    "fullImplementation"(libs.githubBumptechGlideOkhttp3Integration) {
        exclude(group = "glide-parent")
    }

    // Ref: https://stackoverflow.com/a/46638213
    "kspFull"(libs.githubBumptechGlideCompiler)
    // Swipe button animation
    "fullImplementation"(libs.facebookShimmerShimmer)

    // Koin core
    download(libs.insertKoinKoinCore)
    implementation(libs.insertKoinKoinCore)
    // Koin main (Scope, ViewModel ...)
    download(libs.insertKoinKoinAndroid)
    implementation(libs.insertKoinKoinAndroid)

    download(libs.huAutsoftKrate)
    implementation(libs.huAutsoftKrate)

    // viewBinding without reflection
    "fullImplementation"(libs.githubKirich1409Viewbindingpropertydelegate)
    "fullImplementation"(libs.githubKirich1409ViewbindingpropertydelegateNoreflection)

    // add ":debug" suffix to the dependency to include debug symbols
    download(firestackDependency())
    "websiteImplementation"(firestackDependency())
    "fdroidImplementation"(firestackDependency())
    "playImplementation"(firestackDependency())

    // Work manager
    implementation(libs.androidxWorkWorkRuntimeKtx) {
        modules {
            module("com.google.guava:listenablefuture") {
                replacedBy("com.google.guava:guava", "listenablefuture is part of guava")
            }
        }
    }

    // for handling IP addresses and subnets, both IPv4 and IPv6
    // seancfoley.github.io/IPAddress/ipaddress.html
    download(libs.githubSeancfoleyIpaddress)
    implementation(libs.githubSeancfoleyIpaddress)

    testImplementation(libs.junitJunit)
    testImplementation(libs.androidxWorkWorkTesting)
    androidTestImplementation(libs.androidxTestExtJunit)
    androidTestImplementation(libs.androidxTestEspressoEspressoCore)
    androidTestImplementation(libs.androidxTestEspressoEspressoAccessibility)
    androidTestImplementation(libs.androidxTestRules)
    testImplementation(libs.robolectricRobolectric)
    testImplementation(libs.androidxTestCore)
    testImplementation(libs.androidxTestExtJunit)
    testImplementation(libs.mockitoMockitoCore)
    // Added test dependencies for comprehensive testing
    testImplementation(libs.mockkMockk)
    testImplementation(libs.mockkMockkAndroid)
    testImplementation(libs.androidxArchCoreCoreTesting)
    testImplementation(libs.jetbrainsKotlinxKotlinxCoroutinesTest)
    testImplementation(libs.insertKoinKoinTest)
    testImplementation(libs.insertKoinKoinTestJunit4)
    androidTestImplementation(libs.insertKoinKoinTest)
    androidTestImplementation(libs.insertKoinKoinTestJunit4)
    androidTestImplementation(libs.mockkMockkAndroid)

    "leakCanaryImplementation"(libs.squareupLeakcanaryLeakcanaryAndroid)

    "fullImplementation"(libs.androidxNavigationNavigationFragmentKtx)
    "fullImplementation"(libs.androidxNavigationNavigationUiKtx)

    "fullImplementation"(libs.androidxBiometricBiometric)

    "playImplementation"(libs.googleAndroidPlayAppUpdate)
    "playImplementation"(libs.googleAndroidPlayAppUpdateKtx)

    // for encrypting wireguard configuration files
    implementation(libs.androidxSecuritySecurityCrypto)
    implementation(libs.androidxSecuritySecurityAppAuthenticator)
    androidTestImplementation(libs.androidxSecuritySecurityAppAuthenticator)

    // barcode scanner for wireguard
    "fullImplementation"(libs.journeyappsZxingAndroidEmbedded)
    "fullImplementation"(libs.simplecityappsRecyclerviewFastscroll)

    // for confetti animation
    "fullImplementation"(libs.nlDionsegijnKonfettiXml)

    "tvImplementation"(libs.jetbrainsKotlinKotlinStdlibJdk8)
    "tvImplementation"(libs.androidxAppcompatAppcompat)
    "tvImplementation"(libs.androidxCoreCoreKtx)
    "tvImplementation"(libs.androidxConstraintlayoutConstraintlayout)
    "tvImplementation"(libs.androidxSwiperefreshlayoutSwiperefreshlayout)
    "tvImplementation"(libs.jetbrainsKotlinxKotlinxCoroutinesCore)
    "tvImplementation"(libs.jetbrainsKotlinxKotlinxCoroutinesAndroid)
    "tvImplementation"(libs.androidxLifecycleLifecycleViewmodelKtx)
    "tvImplementation"(libs.androidxLifecycleLifecycleRuntimeKtx)
    "tvImplementation"(libs.androidxFragmentFragmentKtx)
    "tvImplementation"(libs.androidxViewpager2Viewpager2)
    "tvImplementation"(libs.squareupOkhttp3Okhttp)
    "tvImplementation"(libs.squareupOkhttp3OkhttpDnsoverhttps)
    "tvImplementation"(libs.squareupOkhttp3LoggingInterceptor)
    "tvImplementation"(libs.squareupRetrofit2Retrofit)
    "tvImplementation"(libs.squareupRetrofit2ConverterGson)
    "tvImplementation"(libs.githubBumptechGlideGlide) { exclude(group = "glide-parent") }
    "tvImplementation"(libs.githubBumptechGlideOkhttp3Integration) { exclude(group = "glide-parent") }
    "kspTv"(libs.githubBumptechGlideCompilerTv)
    "tvImplementation"(libs.facebookShimmerShimmer)
    "tvImplementation"(libs.githubKirich1409Viewbindingpropertydelegate)
    "tvImplementation"(libs.githubKirich1409ViewbindingpropertydelegateNoreflection)
    "tvImplementation"(libs.androidxNavigationNavigationFragmentKtx)
    "tvImplementation"(libs.androidxNavigationNavigationUiKtx)
    "tvImplementation"(libs.androidxBiometricBiometric)
    "tvImplementation"(libs.journeyappsZxingAndroidEmbedded)
    "tvImplementation"(libs.simplecityappsRecyclerviewFastscroll)
    "tvImplementation"(libs.nlDionsegijnKonfettiXml)

    implementation(platform(libs.androidxComposeComposeBom))
    implementation(libs.androidxComposeRuntimeRuntime)
    "tvImplementation"(platform(libs.androidxComposeComposeBomTv))
    "tvImplementation"(libs.androidxComposeUiUi)
    "tvImplementation"(libs.androidxComposeUiUiToolingPreview)
    "tvImplementation"(libs.androidxComposeFoundationFoundation)
    "tvImplementation"(libs.androidxComposeRuntimeRuntimeLivedata)
    "tvImplementation"(libs.androidxComposeMaterial3Material3)
    "tvImplementation"(libs.androidxTvTvMaterial)
    "tvImplementation"(libs.androidxActivityActivityCompose)
    "tvImplementation"(libs.androidxLifecycleLifecycleRuntimeCompose)
    "tvImplementation"(libs.androidxLifecycleLifecycleViewmodelCompose)
    "tvImplementation"(libs.insertKoinKoinAndroidxCompose)
    "tvImplementation"(libs.androidxNavigationNavigationCompose)
    "tvImplementation"(libs.androidxComposeMaterialMaterialIconsExtended)
    "tvImplementation"(libs.androidxPagingPagingCompose)

    constraints {
        implementation(libs.androidxAnnotationAnnotationExperimental)
    }

    // for in-app purchases
    "playImplementation"(libs.androidBillingclientBilling)
    "websiteImplementation"(libs.androidBillingclientBilling)
    // for stripe payment gateway
    //"websiteImplementation"("com.stripe:stripe-android:21.21.0)"
    //"fdroidImplementation"("com.stripe:stripe-android:21.21.0)"

    // Google Play developer api model classes (ProductPurchaseV2, SubscriptionPurchaseV2, …)
    // Only model classes + GsonFactory are needed, Apache HTTP transport is excluded.
    // The version v3-rev20240301-2.0.0 cited in the API docs does not exist on Maven Central;
    // v3-rev20260318-2.0.0 is the closest available release with the same model classes.
    // ref: github.com/googleapis/google-api-java-client-services/tree/main/clients/google-api-services-androidpublisher/v3
    "playImplementation"(libs.googleApisGoogleApiServicesAndroidpublisher) {
        // Exclude Apache HTTP transport, conflicts with Android's built-in HTTP stack
        exclude(group = "com.google.http-client", module = "google-http-client-apache-v2")
        exclude(group = "org.apache.httpcomponents")
        // Exclude OAuth, not needed for model-only parsing
        exclude(group = "com.google.oauth-client")
    }
    "websiteImplementation"(libs.googleApisGoogleApiServicesAndroidpublisher) {
        exclude(group = "com.google.http-client", module = "google-http-client-apache-v2")
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "com.google.oauth-client")
    }

    lintChecks(libs.androidSecurityLintLint)

    // battery optimization permission helper
    implementation(libs.waseemsabirBetterypermissionhelper)

    // Firebase dependencies for error reporting (website and play variants only)
    "websiteImplementation"(platform(libs.googleFirebaseFirebaseBom))
    "websiteImplementation"(libs.googleFirebaseFirebaseCrashlytics)
    "websiteImplementation"(libs.googleFirebaseFirebaseCrashlyticsNdk)

    "playImplementation"(platform(libs.googleFirebaseFirebaseBom))
    "playImplementation"(libs.googleFirebaseFirebaseCrashlytics)
    "playImplementation"(libs.googleFirebaseFirebaseCrashlyticsNdk)
}
