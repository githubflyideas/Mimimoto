#!/usr/bin/env bash
# Compile and test with kotlinc alone.
#
# Gradle is the normal path (see build.gradle.kts). This script exists for build
# hosts that cannot reach Maven: the project has no runtime dependencies beyond
# the Kotlin standard library, and the test suite uses the in-repo runner, so
# kotlinc by itself is enough to compile everything and run every test.
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-kotlinc}"
OUT=build/classes
TESTOUT=build/test-classes

echo "==> compiling main"
rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC" -nowarn -d "$OUT" src/main/kotlin

if [ "${1:-}" = "-test" ] || [ "${1:-}" = "" ]; then
  echo "==> compiling tests"
  rm -rf "$TESTOUT" && mkdir -p "$TESTOUT"
  "$KOTLINC" -nowarn -cp "$OUT" -Xfriend-paths="$OUT" -d "$TESTOUT" src/test/kotlin
  echo "==> running suite"
  java -cp "$TESTOUT:$OUT:$(dirname "$(command -v "$KOTLINC")")/../lib/kotlin-stdlib.jar" mimimoto.AllTestsKt
fi
