#!/usr/bin/env bash
# Runs inside the emulator started by reactivecircus/android-emulator-runner.
set -u
mkdir -p e2e-logs
adb logcat -c || true
adb logcat -v time > e2e-logs/logcat-full.txt &
LOGCAT_PID=$!

# Runtime permissions the real app asks for on first launch
PKG=com.deniscerri.ytdl

ARGS=(--console=plain --stacktrace "-Pandroid.injected.build.abi=x86_64" "-Pandroid.testInstrumentationRunnerArguments.OPENROUTER_API_KEY=${OPENROUTER_API_KEY:-}")
if [ -n "${TEST_FILTER:-}" ]; then
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=${TEST_FILTER}")
else
  ARGS+=("-Pandroid.testInstrumentationRunnerArguments.package=com.deniscerri.ytdl.dubbing")
fi

./gradlew :app:connectedGithubDebugAndroidTest "${ARGS[@]}"
STATUS=$?

kill $LOGCAT_PID 2>/dev/null || true
# screenshots written by the tests
adb shell "ls /sdcard/Android/data/$PKG/files/screens 2>/dev/null" > /dev/null 2>&1 && \
  adb pull "/sdcard/Android/data/$PKG/files/screens" e2e-logs/screens || true
# the interesting log lines, inline in the CI log
echo "=================== DubbingE2E / DubbingWorker / crashes ==================="
grep -E "DubbingE2E|DubbingWorker|AndroidRuntime|FATAL|F/linker|TestRunner" e2e-logs/logcat-full.txt \
  | grep -vE "chatty|Choreographer|\): 	at |\): Caused by|\.\.\. [0-9]+ more" | cut -c1-300 | tail -n 160 || true
exit $STATUS
