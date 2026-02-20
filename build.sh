#!/usr/bin/env bash
#
# Build script for HeliBoard with VibeVoice ASR.
# Validates every environment prerequisite before invoking Gradle.
#
set -euo pipefail

# ──────────────────────────────────────────────────────────────
# Config — edit these if your paths differ
# ──────────────────────────────────────────────────────────────
JAVA_VERSION_REQUIRED="17"
COMPILE_SDK="35"
BUILD_TOOLS_VERSION="35.0.0"
NDK_VERSION="28.0.13004108"
GRADLE_WRAPPER_VERSION="8.14"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"

VIBEVOICE_VERSION="${VIBEVOICE_VERSION:-0.0.0-dev}"
export VIBEVOICE_VERSION

APK_RELATIVE="app/build/outputs/apk/debug/HeliBoard-VibeVoice_${VIBEVOICE_VERSION}-debug.apk"
APK_PATH="$PROJECT_DIR/$APK_RELATIVE"

# ──────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m' # No Color

pass()  { printf "  ${GREEN}✔${NC} %s\n" "$1"; }
warn()  { printf "  ${YELLOW}⚠${NC} %s\n" "$1"; }
fail()  {
    printf "\n${RED}${BOLD}ERROR:${NC} %s\n" "$1"
    shift
    # Print any additional context lines
    for line in "$@"; do
        printf "       %s\n" "$line"
    done
    exit 1
}

# ──────────────────────────────────────────────────────────────
# 1. Project directory
# ──────────────────────────────────────────────────────────────
printf "\n${BOLD}Validating environment...${NC}\n\n"

if [ ! -d "$PROJECT_DIR" ]; then
    fail "Project directory not found: $PROJECT_DIR" \
         "Run this script from the repository root."
fi
if [ ! -f "$PROJECT_DIR/gradlew" ]; then
    fail "Gradle wrapper not found: $PROJECT_DIR/gradlew" \
         "This doesn't look like the HeliBoard project root — gradlew is missing."
fi
if [ ! -x "$PROJECT_DIR/gradlew" ]; then
    chmod +x "$PROJECT_DIR/gradlew" 2>/dev/null || \
        fail "Cannot make gradlew executable: $PROJECT_DIR/gradlew" \
             "Try: chmod +x $PROJECT_DIR/gradlew"
    warn "Fixed: gradlew was not executable (chmod +x applied)"
fi
pass "Project directory: $PROJECT_DIR"

# ──────────────────────────────────────────────────────────────
# 2. Gradle wrapper version
# ──────────────────────────────────────────────────────────────
WRAPPER_PROPS="$PROJECT_DIR/gradle/wrapper/gradle-wrapper.properties"
if [ ! -f "$WRAPPER_PROPS" ]; then
    fail "Gradle wrapper properties missing: $WRAPPER_PROPS"
fi
DETECTED_GRADLE_VER=$(grep -oP 'gradle-\K[0-9]+\.[0-9]+' "$WRAPPER_PROPS" | head -1)
if [ -z "$DETECTED_GRADLE_VER" ]; then
    fail "Could not parse Gradle version from $WRAPPER_PROPS" \
         "Contents: $(head -5 "$WRAPPER_PROPS")"
fi
if [ "$DETECTED_GRADLE_VER" != "$GRADLE_WRAPPER_VERSION" ]; then
    warn "Gradle wrapper version is $DETECTED_GRADLE_VER (expected $GRADLE_WRAPPER_VERSION). Proceeding anyway."
else
    pass "Gradle wrapper version: $DETECTED_GRADLE_VER"
fi

# ──────────────────────────────────────────────────────────────
# 3. JAVA_HOME / JDK 17
# ──────────────────────────────────────────────────────────────
# Try these locations in order:
JAVA_CANDIDATES=(
    "${JAVA_HOME:-}"
    "/usr/lib/jvm/java-17-openjdk-amd64"
    "/usr/lib/jvm/java-17-openjdk"
    "/usr/lib/jvm/java-17"
)
FOUND_JAVA=""
for candidate in "${JAVA_CANDIDATES[@]}"; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/javac" ]; then
        FOUND_JAVA="$candidate"
        break
    fi
done

if [ -z "$FOUND_JAVA" ]; then
    fail "JDK $JAVA_VERSION_REQUIRED not found." \
         "Searched: ${JAVA_CANDIDATES[*]}" \
         "Install it:  sudo apt install openjdk-17-jdk" \
         "Or set JAVA_HOME to your JDK $JAVA_VERSION_REQUIRED installation."
fi

# Verify it's actually JDK 17
JAVA_VER_OUTPUT=$("$FOUND_JAVA/bin/javac" -version 2>&1)
JAVA_MAJOR=$(echo "$JAVA_VER_OUTPUT" | grep -oP '\d+' | head -1)
if [ "$JAVA_MAJOR" != "$JAVA_VERSION_REQUIRED" ]; then
    fail "JDK at $FOUND_JAVA is version $JAVA_MAJOR, but version $JAVA_VERSION_REQUIRED is required." \
         "javac -version output: $JAVA_VER_OUTPUT" \
         "Install JDK 17:  sudo apt install openjdk-17-jdk"
fi
export JAVA_HOME="$FOUND_JAVA"
pass "JAVA_HOME: $JAVA_HOME (JDK $JAVA_MAJOR)"

# ──────────────────────────────────────────────────────────────
# 4. ANDROID_HOME / Android SDK
# ──────────────────────────────────────────────────────────────
SDK_CANDIDATES=(
    "${ANDROID_HOME:-}"
    "${ANDROID_SDK_ROOT:-}"
    "$HOME/Android/Sdk"
    "$HOME/android-sdk"
    "/opt/android-sdk"
)
FOUND_SDK=""
for candidate in "${SDK_CANDIDATES[@]}"; do
    if [ -n "$candidate" ] && [ -d "$candidate/platforms" ]; then
        FOUND_SDK="$candidate"
        break
    fi
done

if [ -z "$FOUND_SDK" ]; then
    fail "Android SDK not found." \
         "Searched: ${SDK_CANDIDATES[*]}" \
         "Install Android SDK and set ANDROID_HOME, or place it at ~/Android/Sdk"
fi
export ANDROID_HOME="$FOUND_SDK"
export ANDROID_SDK_ROOT="$FOUND_SDK"   # some tools still read this
pass "ANDROID_HOME: $ANDROID_HOME"

# Verify local.properties points to the right SDK
LOCAL_PROPS="$PROJECT_DIR/local.properties"
if [ -f "$LOCAL_PROPS" ]; then
    PROPS_SDK=$(grep -oP 'sdk\.dir=\K.*' "$LOCAL_PROPS" 2>/dev/null || true)
    if [ -n "$PROPS_SDK" ] && [ "$PROPS_SDK" != "$FOUND_SDK" ]; then
        warn "local.properties sdk.dir=$PROPS_SDK differs from ANDROID_HOME=$FOUND_SDK"
        warn "Updating local.properties to match."
        sed -i "s|sdk\.dir=.*|sdk.dir=$FOUND_SDK|" "$LOCAL_PROPS"
    fi
else
    echo "sdk.dir=$FOUND_SDK" > "$LOCAL_PROPS"
    warn "Created local.properties with sdk.dir=$FOUND_SDK"
fi

# ──────────────────────────────────────────────────────────────
# 5. Android SDK platform (compileSdk)
# ──────────────────────────────────────────────────────────────
PLATFORM_DIR="$FOUND_SDK/platforms/android-$COMPILE_SDK"
if [ ! -d "$PLATFORM_DIR" ]; then
    INSTALLED_PLATFORMS=$(ls "$FOUND_SDK/platforms/" 2>/dev/null || echo "(none)")
    fail "Android SDK platform $COMPILE_SDK not installed." \
         "Expected: $PLATFORM_DIR" \
         "Installed platforms: $INSTALLED_PLATFORMS" \
         "Install it:  sdkmanager 'platforms;android-$COMPILE_SDK'"
fi
pass "SDK platform: android-$COMPILE_SDK"

# ──────────────────────────────────────────────────────────────
# 6. Build tools
# ──────────────────────────────────────────────────────────────
BT_DIR="$FOUND_SDK/build-tools/$BUILD_TOOLS_VERSION"
if [ ! -d "$BT_DIR" ]; then
    INSTALLED_BT=$(ls "$FOUND_SDK/build-tools/" 2>/dev/null || echo "(none)")
    fail "Android build-tools $BUILD_TOOLS_VERSION not installed." \
         "Expected: $BT_DIR" \
         "Installed build-tools: $INSTALLED_BT" \
         "Install it:  sdkmanager 'build-tools;$BUILD_TOOLS_VERSION'"
fi
pass "Build tools: $BUILD_TOOLS_VERSION"

# ──────────────────────────────────────────────────────────────
# 7. NDK
# ──────────────────────────────────────────────────────────────
NDK_DIR="$FOUND_SDK/ndk/$NDK_VERSION"
if [ ! -d "$NDK_DIR" ]; then
    INSTALLED_NDK=$(ls "$FOUND_SDK/ndk/" 2>/dev/null || echo "(none)")
    fail "Android NDK $NDK_VERSION not installed." \
         "Expected: $NDK_DIR" \
         "Installed NDK versions: $INSTALLED_NDK" \
         "This project uses ndkBuild for native JNI code — the NDK is required." \
         "Install it:  sdkmanager 'ndk;$NDK_VERSION'"
fi
if [ ! -x "$NDK_DIR/ndk-build" ]; then
    fail "NDK directory exists but ndk-build is not executable: $NDK_DIR/ndk-build" \
         "The NDK installation may be corrupt. Reinstall with: sdkmanager 'ndk;$NDK_VERSION'"
fi
pass "NDK: $NDK_VERSION"

# ──────────────────────────────────────────────────────────────
# 8. Disk space check (need ~500 MB free for build)
# ──────────────────────────────────────────────────────────────
AVAIL_KB=$(df --output=avail "$PROJECT_DIR" 2>/dev/null | tail -1 | tr -d ' ')
if [ -n "$AVAIL_KB" ] && [ "$AVAIL_KB" -lt 524288 ]; then
    AVAIL_MB=$((AVAIL_KB / 1024))
    fail "Low disk space: only ${AVAIL_MB} MB available on the partition containing $PROJECT_DIR." \
         "The build typically needs at least 500 MB of free space."
fi
pass "Disk space: $((AVAIL_KB / 1024)) MB available"

# ──────────────────────────────────────────────────────────────
# 9. Key source files sanity check
# ──────────────────────────────────────────────────────────────
CRITICAL_FILES=(
    "app/src/main/java/helium314/keyboard/latin/LatinIME.java"
    "app/src/main/java/helium314/keyboard/latin/voice/VibeVoiceClient.kt"
    "app/src/main/java/helium314/keyboard/latin/voice/VoiceInputController.kt"
    "app/src/main/AndroidManifest.xml"
)
for f in "${CRITICAL_FILES[@]}"; do
    if [ ! -f "$PROJECT_DIR/$f" ]; then
        fail "Critical source file missing: $f" \
             "The project tree appears incomplete or corrupted."
    fi
done
pass "Source tree integrity (${#CRITICAL_FILES[@]} key files present)"

# ──────────────────────────────────────────────────────────────
# 10. Check for leaked secrets (safety net)
# ──────────────────────────────────────────────────────────────
if grep -rq "ronenzyroff" "$PROJECT_DIR/app/src/main/" 2>/dev/null; then
    fail "Hardcoded server domain found in source code." \
         "The VibeVoice server URL should be configured via Settings, not hardcoded." \
         "Check VibeVoiceClient.kt and network_security_config.xml."
fi
if grep -rq "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9" "$PROJECT_DIR/app/src/main/" 2>/dev/null; then
    fail "Hardcoded JWT token found in source code." \
         "The auth token should be configured via Settings, not hardcoded." \
         "Check VibeVoiceClient.kt."
fi
pass "No hardcoded secrets in source"

printf "\n${GREEN}${BOLD}All checks passed.${NC}\n"

# ──────────────────────────────────────────────────────────────
# Build
# ──────────────────────────────────────────────────────────────
printf "\n${BOLD}Building HeliBoard debug APK...${NC}\n\n"

cd "$PROJECT_DIR"

# Clean previous APK so we can detect a real fresh build
rm -f "$APK_PATH"

if ! ./gradlew assembleDebug; then
    fail "Gradle build failed." \
         "Scroll up for compiler errors." \
         "JAVA_HOME=$JAVA_HOME" \
         "ANDROID_HOME=$ANDROID_HOME"
fi

# ──────────────────────────────────────────────────────────────
# Verify output
# ──────────────────────────────────────────────────────────────
if [ ! -f "$APK_PATH" ]; then
    fail "Build appeared to succeed but APK not found at expected path." \
         "Expected: $APK_PATH" \
         "Check for non-standard build output paths."
fi

APK_SIZE=$(stat --printf="%s" "$APK_PATH" 2>/dev/null || stat -f%z "$APK_PATH" 2>/dev/null)
APK_SIZE_MB=$(awk "BEGIN {printf \"%.1f\", $APK_SIZE / 1048576}")

printf "\n${GREEN}${BOLD}Build successful!${NC}\n"
printf "\n  APK: ${BOLD}%s${NC}\n" "$APK_PATH"
printf "  Size: %s MB\n\n" "$APK_SIZE_MB"
