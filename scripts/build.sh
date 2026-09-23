#!/usr/bin/env bash
# Compile and test the JVM side with kotlinc alone.
#
# Gradle is the normal path. This script exists for build hosts that cannot
# reach Maven: core and server have no runtime dependencies beyond the Kotlin
# standard library, and the test suite uses the in-repo runner, so kotlinc by
# itself compiles everything and runs every test. It does NOT build the Android
# app, which needs the Android SDK — CI does that (.github/workflows).
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-kotlinc}"
LIB="$(dirname "$(command -v "$KOTLINC")")/../lib/kotlin-stdlib.jar"
OUT=build/classes
TESTOUT=build/test-classes

echo "==> compiling core + server"
rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC" -nowarn -d "$OUT" core/src/main/kotlin server/src/main/kotlin

echo "==> compiling tests"
rm -rf "$TESTOUT" && mkdir -p "$TESTOUT"
"$KOTLINC" -nowarn -cp "$OUT" -Xfriend-paths="$OUT" -d "$TESTOUT" server/src/test/kotlin

echo "==> running suite"
java -cp "$TESTOUT:$OUT:$LIB" mimimoto.AllTestsKt
