#!/usr/bin/env bash
# Compile and test the JVM side with kotlinc alone.
#
# Gradle is the normal path. This script exists for build hosts that cannot
# reach Maven: core, server and the test runner have no dependencies beyond the
# Kotlin standard library, so kotlinc by itself compiles everything and runs
# every test. It does NOT build the Android app, which needs the Android SDK —
# CI does that (.github/workflows).
#
# Each module compiles to its OWN output directory, which is the only thing
# that makes this script agree with Gradle about module boundaries. An earlier
# version compiled core and server together into one directory and passed a
# single blanket -Xfriend-paths; everything built and every test passed, while
# `internal` meant nothing at all and CI — which does compile them separately —
# failed on the first push. A verification path that is more permissive than
# the real one does not verify; it launders.
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-kotlinc}"
LIB="$(dirname "$(command -v "$KOTLINC")")/../lib/kotlin-stdlib.jar"
B=build/modules
rm -rf "$B" && mkdir -p "$B"

# :testkit — the runner. Nothing depends on it but test code.
echo "==> testkit"
"$KOTLINC" -nowarn -d "$B/testkit" testkit/src/main/kotlin

# :core — no classpath at all. If this ever needs one, core has stopped being
# the module the Android app can take unchanged.
echo "==> core"
"$KOTLINC" -nowarn -d "$B/core" core/src/main/kotlin

echo "==> core tests"
"$KOTLINC" -nowarn -cp "$B/core:$B/testkit" -Xfriend-paths="$B/core" \
    -d "$B/core-test" core/src/test/kotlin

# :server sees core's public API only — no friend path. That is what makes a
# reach into core's internals fail here rather than three days later in CI.
echo "==> server"
"$KOTLINC" -nowarn -cp "$B/core" -d "$B/server" server/src/main/kotlin

echo "==> server tests"
"$KOTLINC" -nowarn -cp "$B/core:$B/server:$B/testkit" -Xfriend-paths="$B/server" \
    -d "$B/server-test" server/src/test/kotlin

# Both suites run even if the first fails, so one broken module does not hide
# the state of the other.
rc=0
echo
echo "==> core suite"
java -cp "$B/core-test:$B/core:$B/testkit:$LIB" mimimoto.CoreTestsKt || rc=1
echo
echo "==> server suite"
java -cp "$B/server-test:$B/server:$B/core:$B/testkit:$LIB" mimimoto.AllTestsKt || rc=1
exit $rc
