#!/bin/bash
# Builds the signed release APK and leaves it in dist/ as MarcarPro-<version>.apk, ready to attach
# to a GitHub release tagged v<version> in the updates repository (see CLAUDE.md, "Updates").
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f "$HOME/.marcarpro/keystore.properties" ] || { echo "Falta la clave de firma (~/.marcarpro)."; exit 1; }
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home
./gradlew -q assembleRelease lintDebug testDebugUnitTest
version=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk "dist/MarcarPro-$version.apk"
echo "dist/MarcarPro-$version.apk  →  publicar como release v$version"
