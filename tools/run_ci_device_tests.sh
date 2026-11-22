#!/usr/bin/env bash
set -euo pipefail

capture_review() {
    mkdir -p ui-review
    adb pull /sdcard/Download/vescurus-ui/. ui-review/ || true
}

# Keep any screenshots even when instrumentation fails, without hiding its exit code.
trap capture_review EXIT

# Only the known background launcher is stopped, and only on the hosted CI emulator.
# Do not disable ANR dialogs or change any VESCURUS process/error handling.
launcher_review_args=()
if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    launcher_component="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
    if [[ "$launcher_component" == com.google.android.apps.nexuslauncher/* ]]; then
        echo '::notice::Stopping the known Pixel Launcher before isolated VESCURUS UI tests.'
        adb shell am force-stop com.google.android.apps.nexuslauncher
        launcher_review_args=(-Pandroid.testInstrumentationRunnerArguments.clearPixelLauncherAnr=true)
    fi
fi

bash gradlew :app:connectedDebugAndroidTest \
    --no-daemon --no-parallel --max-workers=2 --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.uiReview=true \
    "${launcher_review_args[@]}" \
    -Dorg.gradle.jvmargs="-Xmx2g -XX:+UseParallelGC -Dfile.encoding=UTF-8"
adb shell test -s /sdcard/Download/vescurus-ui/cook-home.png
adb shell test -s /sdcard/Download/vescurus-ui/cooking-step.png
