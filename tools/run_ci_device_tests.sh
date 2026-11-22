#!/usr/bin/env bash
set -euo pipefail

capture_review() {
    mkdir -p ui-review
    adb pull /sdcard/Android/data/com.example.vescurus/files/ui-review/. ui-review/ || true
}

# Keep any screenshots even when instrumentation fails, without hiding its exit code.
trap capture_review EXIT
bash gradlew :app:connectedDebugAndroidTest \
    --no-daemon --no-parallel --max-workers=2 --stacktrace \
    -Dorg.gradle.jvmargs="-Xmx2g -XX:+UseParallelGC -Dfile.encoding=UTF-8"
