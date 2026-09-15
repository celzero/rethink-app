plugins {
    id("com.android.test")
}

android {
    namespace = "com.celzero.bravedns.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        targetSdk = 37

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
