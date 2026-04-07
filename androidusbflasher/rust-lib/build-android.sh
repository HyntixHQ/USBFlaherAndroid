#!/bin/bash
# Build script for Android arm64 target
# Usage: ./build-android.sh

set -e

# Configuration
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/30.0.14904198}"
TARGET="aarch64-linux-android"
API_LEVEL="26"

# Check NDK
if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "Error: ANDROID_NDK_HOME not found at $ANDROID_NDK_HOME"
    echo "Please set ANDROID_NDK_HOME environment variable"
    exit 1
fi

echo "Using NDK: $ANDROID_NDK_HOME"

# Setup toolchain paths
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"
export CC_aarch64_linux_android="aarch64-linux-android${API_LEVEL}-clang"
export CXX_aarch64_linux_android="aarch64-linux-android${API_LEVEL}-clang++"
export AR_aarch64_linux_android="llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="aarch64-linux-android${API_LEVEL}-clang"

echo "Building for $TARGET..."

# Build the JNI library
cargo build --release --target $TARGET -p hyntix-usb-flasher-jni

# Show output location
OUTPUT_DIR="target/$TARGET/release"
echo ""
echo "Build complete!"
echo "Library location: $OUTPUT_DIR/libhyntix_usb_flasher_jni.so"

# Copy to Android jniLibs if library module directory exists
# We prefer copying to the library module: libs/androidusbflasher
LIB_JNI_DIR="../src/main/jniLibs/arm64-v8a"

if [ -d "$(dirname $LIB_JNI_DIR)" ]; then
    mkdir -p "$LIB_JNI_DIR"
    cp "$OUTPUT_DIR/libhyntix_usb_flasher_jni.so" "$LIB_JNI_DIR/"
    echo "Copied to: $LIB_JNI_DIR/libhyntix_usb_flasher_jni.so"
else
    echo "Warning: Destination $LIB_JNI_DIR not found. Skipping copy."
fi
