#!/usr/bin/env bash
set -euo pipefail

capture_review() {
    mkdir -p ui-review
    adb pull /sdcard/Download/vescurus-ui/. ui-review/ || true
}

# Keep any screenshots even when instrumentation fails, without hiding its exit code.
trap capture_review EXIT
bash gradlew :app:connectedDebugAndroidTest \
    --no-daemon --no-parallel --max-workers=2 --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.uiReview=true \
    -Dorg.gradle.jvmargs="-Xmx2g -XX:+UseParallelGC -Dfile.encoding=UTF-8"
adb shell test -s /sdcard/Download/vescurus-ui/cook-home.png
adb shell test -s /sdcard/Download/vescurus-ui/cooking-step.png
