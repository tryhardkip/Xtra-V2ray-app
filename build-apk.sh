#!/usr/bin/env bash
# One-shot toolchain setup + build helper for the FkVpn APK.
#
# This is meant to run on a full dev machine (Linux/macOS) with the Android
# SDK/NDK available — NOT inside the Termux app sandbox, which lacks the SDK.
set -euo pipefail

echo ">> Checking prerequisites..."
command -v rustup >/dev/null || { echo "install rustup first"; exit 1; }
command -v cargo  >/dev/null || { echo "install cargo first"; exit 1; }

echo ">> Adding Android Rust targets..."
rustup target add aarch64-linux-android armv7-linux-androideabi

echo ">> Installing cargo-ndk (idempotent)..."
cargo install cargo-ndk --locked || true

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your NDK path (e.g. \$ANDROID_SDK_ROOT/ndk/26.x.x)}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK path}"

echo ">> Building native core + assembling debug APK..."
# preBuild depends on the cargoBuild task, which runs cargo-ndk for both ABIs.
./gradlew :app:assembleDebug

echo ">> Done. APK at: app/build/outputs/apk/debug/app-debug.apk"
