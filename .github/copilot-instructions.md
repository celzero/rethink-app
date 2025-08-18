# Rethink DNS + Firewall + VPN for Android

Rethink DNS + Firewall + VPN is an Android application built with Kotlin/Java that provides VPN, DNS, and firewall capabilities. It uses a fork of the go-based firestack library for core networking functionality.

Always reference these instructions first and fallback to search or bash commands only when you encounter unexpected information that does not match the info here.

## Working Effectively

### Prerequisites and Setup
- Install Java 17 (required by Gradle): `apt-get update && apt-get install -y openjdk-17-jdk`
- Set JAVA_HOME if needed: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`
- **CRITICAL**: Network access to dl.google.com and maven.google.com is REQUIRED for Android dependencies
- Verify network access: `ping dl.google.com` must succeed before attempting builds

### Building the Application
- Bootstrap the project:
  - `cd /path/to/rethink-app`
  - `chmod +x gradlew`
  - `./gradlew --version` -- verify Gradle works (downloads Gradle 8.13)
- Create required secrets file:
  - `mkdir -p app/src/debug`
  - Create `app/src/debug/google-services.json` with valid Google Services configuration
- Build the application:
  - `./gradlew lint` -- runs code linting (takes 2-3 minutes)
  - `./gradlew assembleWebsiteFullDebug --info --warning-mode all` -- builds debug APK (takes 5-10 minutes). NEVER CANCEL. Set timeout to 15+ minutes.
- Clean build:
  - `./gradlew clean` -- cleans all build artifacts
  - `./gradlew assembleWebsiteFullRelease` -- builds release APK with minification (takes 10-15 minutes). NEVER CANCEL. Set timeout to 20+ minutes.

### Testing
- Run unit tests:
  - `./gradlew test` -- runs JUnit tests (takes 2-3 minutes). NEVER CANCEL. Set timeout to 5+ minutes.
  - Tests are located in `app/src/test/java/com/celzero/bravedns/`
- Run instrumented tests (requires Android device/emulator):
  - `./gradlew connectedAndroidTest` -- runs UI tests (takes 5-10 minutes if device available). NEVER CANCEL. Set timeout to 15+ minutes.
  - Tests are located in `app/src/androidTest/java/com/celzero/bravedns/`

### Validation
- **PRIMARY**: Always run lint and build validation before committing:
  - `ping dl.google.com` -- MUST succeed before attempting builds
  - `./gradlew lint` -- must pass without errors
  - `./gradlew assembleWebsiteFullDebug` -- must complete successfully
- **SECONDARY**: If network access unavailable, validate at source level:
  - Check Kotlin syntax: examine files in `app/src/main/java/com/celzero/bravedns/`
  - Review imports and dependencies for obvious issues
  - Validate test structure: `find app/src -name "*.kt" -exec grep -l "@Test" {} \;`
- The project uses strict lint configuration (`lint { abortOnError true }`) so all lint issues must be fixed
- Cannot run the Android app in sandboxed environment, but build validation ensures code compiles correctly

## Project Structure

### Build Flavors and Types
The project uses Gradle product flavors with two dimensions:
- **Release Channels**: `play`, `fdroid`, `website` 
- **Release Types**: `full`, `headless`
- Common combinations: `websiteFullDebug`, `playFullRelease`, `fdroidFullDebug`

### Key Source Directories
- `app/src/main/java/com/celzero/bravedns/` -- main application code (399 Kotlin files, 5 Java files)
  - `service/` -- core VPN, DNS, and firewall services
  - `ui/` -- Android UI components and activities  
  - `net/` -- networking and protocol handling
  - `database/` -- Room database entities and DAOs
  - `util/` -- utility classes and helpers
  - `wireguard/` -- WireGuard VPN implementation
- `app/src/test/` -- unit tests (JUnit)
- `app/src/androidTest/` -- instrumented UI tests
- `app/src/fdroid/`, `app/src/play/`, `app/src/website/` -- flavor-specific code
- `app/src/full/`, `app/src/headless/` -- type-specific code

### Key Files
- `build.gradle` -- root project build configuration
- `app/build.gradle` -- app module build configuration with flavors, dependencies, and version management
- `gradle.properties` -- project properties including version code and firestack dependency settings
- `app/proguard-rules.pro` -- ProGuard/R8 configuration for release builds
- `.github/workflows/android.yml` -- CI/CD pipeline configuration

## Dependencies and External Libraries

### Core Dependencies
- **firestack**: Core networking library (Go-based) - version controlled via `firestackCommit` in gradle.properties
- **Room**: Database ORM for local data storage
- **Koin**: Dependency injection framework
- **Retrofit + OkHttp**: HTTP client for network requests
- **Kotlin Coroutines**: Asynchronous programming
- **Android Architecture Components**: LiveData, ViewModel, Navigation

### Build Dependencies
- Android Gradle Plugin 8.11.0
- Kotlin 2.1.0
- Target SDK 35, Min SDK 23
- Java 17 compilation

## Common Tasks

### Version Management
- Version code is set in `gradle.properties` (`VERSION_CODE=49`) or via environment variable
- Version name is auto-generated from git tags using `git describe --tags --always`
- Each ABI variant gets a different version code (arm64-v8a: 30000000+, x86_64: 90000000+, etc.)

### Debugging and Development
- Use `./gradlew assembleWebsiteFullDebug` for development builds
- Debug builds include additional logging and debugging symbols
- LeakCanary build type available: `./gradlew assembleLeakCanaryDebug`

### Release Builds
- Use `./gradlew assembleWebsiteFullRelease` for release builds
- Release builds enable minification, shrinkResources, and ProGuard obfuscation
- Signing configuration required for release builds (keystore.properties)

### Lint and Code Quality
- Strict linting enabled with `abortOnError true`
- Detekt configuration in `.github/detekt-config.yml`
- All lint issues must be resolved before builds succeed

## Build Timing Expectations
- **NEVER CANCEL** any build or test commands - they may take significant time
- Gradle sync/setup: 30-60 seconds (downloads Gradle 8.13 on first run)
- Lint: 2-3 minutes. NEVER CANCEL. Set timeout to 5+ minutes.
- Debug build: 5-10 minutes. NEVER CANCEL. Set timeout to 15+ minutes.
- Release build: 10-15 minutes. NEVER CANCEL. Set timeout to 20+ minutes.
- Unit tests: 2-3 minutes. NEVER CANCEL. Set timeout to 5+ minutes.
- Instrumented tests: 5-10 minutes (if device available). NEVER CANCEL. Set timeout to 15+ minutes.

## Troubleshooting

### Common Issues
- **Network connectivity CRITICAL**: Requires access to Google Maven repository (dl.google.com) for Android dependencies
  - Error: "dl.google.com: No address associated with hostname" indicates network blocking
  - MUST verify `ping dl.google.com` succeeds before attempting any Gradle commands
  - Alternative: Work in environment with full internet access
- **Missing google-services.json**: Create dummy file in `app/src/debug/` if not building with Google Services
- **Firestack dependency issues**: Check `firestackRepo` and `firestackCommit` settings in gradle.properties
- **Memory issues**: Large heap enabled in AndroidManifest.xml, increase Gradle JVM args if needed
- **Lint failures**: All lint issues must be fixed as `abortOnError` is enabled

### Network-Limited Environment Workarounds
If network access is limited:
- Use `./gradlew --offline` for builds with pre-downloaded dependencies (rarely works for fresh clones)
- Examine source code structure and configuration files instead of building
- Validate Kotlin/Java syntax with standalone compilers if available
- Use GitHub Actions or other CI environments with full network access for actual builds

### Environment Requirements
- Java 17 (critical - required by Android Gradle Plugin 8.11.0)
- Internet access for downloading dependencies
- Sufficient memory for Gradle builds (2GB+ recommended)
- Android SDK not required for basic compilation, but needed for device deployment

## Testing Strategy
- Focus on testing core networking logic in `service/` package
- UI tests in `androidTest/` validate critical user flows like AntiCensorshipActivity
- Key test files:
  - `app/src/test/java/com/celzero/bravedns/service/IpRulesTest.kt` -- validates firewall IP rule handling
  - `app/src/test/java/com/celzero/bravedns/ExampleUnitTest.kt` -- basic unit test example
  - `app/src/androidTest/java/com/celzero/bravedns/AntiCensorshipActivityTest.kt` -- UI test for anti-censorship features
- Always run `./gradlew test` before making changes to validate existing functionality
- For networking changes, especially focus on `IpRulesTest` which validates firewall rule handling

## Key Areas for Development
- **VPN Service**: `app/src/main/java/com/celzero/bravedns/service/BraveVPNService.kt` -- Core VPN implementation
- **DNS Handling**: `app/src/main/java/com/celzero/bravedns/net/doh/` -- DNS over HTTPS implementation
- **Firewall Rules**: `app/src/main/java/com/celzero/bravedns/service/IpRulesManager.kt` -- IP-based firewall rules
- **WireGuard**: `app/src/main/java/com/celzero/bravedns/wireguard/` -- WireGuard VPN configuration
- **Database**: `app/src/main/java/com/celzero/bravedns/database/` -- Room entities for app data
- **UI Activities**: `app/src/full/java/com/celzero/bravedns/ui/activity/` -- Main application screens (full flavor)
- **Headless UI**: `app/src/headless/java/com/celzero/bravedns/ui/` -- Simplified UI for headless builds

## CI/CD Integration
- GitHub Actions workflow runs `./gradlew lint` and `./gradlew assembleWebsiteFullDebug`
- All PRs must pass linting and build successfully
- Release builds require proper signing configuration

## Reference Information

### Common File Paths and Outputs
When exploring the codebase without builds, reference these key locations:

#### Repository Root Structure
```
.
├── .github/                 # CI/CD workflows and configurations
├── app/                     # Main application module
├── build.gradle            # Root build configuration
├── gradle.properties       # Project-wide properties
├── gradlew                  # Gradle wrapper script
└── README.md               # Project overview
```

#### App Module Structure  
```
app/src/
├── main/java/com/celzero/bravedns/    # Main source (399 Kotlin files)
│   ├── service/                       # Core VPN/DNS/Firewall services
│   ├── ui/                           # Android UI activities and fragments
│   ├── database/                     # Room database entities
│   ├── net/                          # Networking and protocol handling
│   └── util/                         # Utility classes
├── test/java/                        # Unit tests (JUnit)
├── androidTest/java/                 # Instrumented tests
└── [flavor]/java/                    # Flavor-specific code (fdroid, play, website)
```

#### Key Configuration Files
- `app/build.gradle` -- Build configuration with flavors and dependencies
- `app/proguard-rules.pro` -- Release build obfuscation rules
- `app/src/main/AndroidManifest.xml` -- App permissions and components
- `.github/workflows/android.yml` -- CI/CD pipeline
- `.github/detekt-config.yml` -- Code style configuration

### Quick File Lookups (No Build Required)
Use these commands to explore the codebase when builds fail:

```bash
# Find all test files
find app/src -name "*.kt" -exec grep -l "@Test" {} \;

# Count source files by type
find app/src -name "*.kt" | wc -l  # Kotlin files
find app/src -name "*.java" | wc -l  # Java files

# Explore key directories
ls -la app/src/main/java/com/celzero/bravedns/service/  # Core services
ls -la app/src/main/java/com/celzero/bravedns/database/  # Database entities
find app/src -path "*/ui/*" -name "*Activity*" | head -10  # UI activities

# Validate project structure
ls -la app/src/  # Source sets (main, test, androidTest, flavors)
grep -r "buildTypes\|productFlavors" app/build.gradle  # Build configuration
```
