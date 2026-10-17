plugins {
    alias(libs.plugins.androidTest)
}

android {
    namespace = "com.celzero.bravedns.benchmark"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // This benchmark build type is used for keyboard benchmarks
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidxBenchmarkBenchmarkMacroJunit4)
    implementation(libs.androidxTestExtJunit)
    implementation(libs.androidxTestUiautomatorUiautomator)
}
