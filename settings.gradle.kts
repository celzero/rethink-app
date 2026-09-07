rootProject.name = "rethink"
include(":app")
include(":benchmark")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("gradle/lib.toml"))
        }
    }

    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    repositories {
        google()
        mavenCentral()
    }
}
