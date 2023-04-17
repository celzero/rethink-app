SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
yes | prebuilts/cmdline-tools/tools/bin/sdkmanager --licenses --sdk_root="$SCRIPT_DIR"
TASK="assembleFdroidHeadless"
ANDROID_HOME="$SCRIPT_DIR" JAVA_HOME=prebuilts/jdk/jdk17/linux-x86 "$SCRIPT_DIR"/gradlew -p "$SCRIPT_DIR" "$TASK"