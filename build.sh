#!/usr/bin/env bash
# Agent-VScreen build: reuses the Minis VScreen toolchain and Gradle cache; outputs go to /srv/avs-build.
set -euo pipefail
BASE=/data/workspace/minis-vscreen
export GRADLE_USER_HOME="$BASE/cache/gradle" TMPDIR=/srv/avs-build/tmp
export ANDROID_HOME="$BASE/toolchains/android-sdk" ANDROID_SDK_ROOT="$BASE/toolchains/android-sdk"
mkdir -p "$TMPDIR" /srv/avs-build/logs
cd "$(dirname "$0")"
TASK="${1:-:app:assembleRelease}"
# The built-in Tailscale node (Go) is built once; delete the .so to rebuild it.
[ -f app/src/main/jniLibs/arm64-v8a/libtsbridge.so ] || ./tsbridge/build.sh >> /srv/avs-build/logs/tsb.log 2>&1
./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process $TASK > /srv/avs-build/logs/gradle.log 2>&1
echo AVS_BUILD_SUCCESS
