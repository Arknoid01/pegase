#!/bin/bash
# Compile llama-server (arm64) depuis le llama.cpp embarqué d'Orbe et le dépose
# dans app/src/main/jniLibs/arm64-v8a/libllamaserver.so (exécuté par LocalLlmServer).
# Le binaire n'est pas versionné (.gitignore) : relancer ce script après un clone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/cpp/llama.cpp"
NDK="${ANDROID_NDK:-}"
if [ -z "$NDK" ]; then
  for c in /mnt/jeux/Android/Sdk/ndk/* "$HOME/Android/Sdk/ndk/"*; do [ -d "$c" ] && NDK="$c"; done
fi
[ -d "$NDK" ] || { echo "NDK introuvable (ANDROID_NDK)"; exit 1; }
BUILD="$ROOT/build/llama-server-android"
cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release \
  -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_OPENMP=OFF \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DBUILD_SHARED_LIBS=OFF -DLLAMA_USE_PREBUILT_UI=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF
cmake --build "$BUILD" -j"$(nproc)" --target llama-server
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$OUT/libllamaserver.so" "$BUILD/bin/llama-server"
ls -la "$OUT/libllamaserver.so"
