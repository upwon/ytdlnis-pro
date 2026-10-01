#!/usr/bin/env bash
# Runs inside the emulator started by reactivecircus/android-emulator-runner.
set -u
mkdir -p e2e-logs
# keep the emulator screen on and unlocked: UI tests need it
adb shell svc power stayon true || true
adb shell settings put system screen_off_timeout 2147483647 || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

# local test site (emulator reaches the host as 10.0.2.2) for the online-subtitle test
python3 .github/scripts/prepare-e2e-site.py
# detached from the step's stdio, otherwise the step cannot finish while the server is alive
(cd e2e-site && setsid nohup python3 -m http.server 8000 --bind 0.0.0.0 < /dev/null > ../e2e-logs/site.log 2>&1 &)
SITE_PID=$(pgrep -f "http.server 8000" | head -1)

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

# Run gradle in the background and stream test progress from logcat into the CI log while it runs,
# so a stuck test is visible live instead of after the fact.
timeout 40m ./gradlew :app:connectedGithubDebugAndroidTest "${ARGS[@]}" &
GRADLE_PID=$!
SEEN=0
while kill -0 $GRADLE_PID 2>/dev/null; do
  sleep 45
  TOTAL=$(wc -l < e2e-logs/logcat-full.txt)
  if [ "$TOTAL" -gt "$SEEN" ]; then
    tail -n +$((SEEN + 1)) e2e-logs/logcat-full.txt | grep -E "DubbingE2E|TestRunner|DubbingWorker|FATAL|WM-WorkerWrapper" \
      | grep -vE "\): 	at " | cut -c1-240 | sed 's/^/[live] /' || true
    SEEN=$TOTAL
  fi
done
wait $GRADLE_PID
STATUS=$?

kill $LOGCAT_PID 2>/dev/null || true
[ -n "${SITE_PID:-}" ] && kill $SITE_PID 2>/dev/null || true
# screenshots written by the tests
adb shell "ls /sdcard/Android/data/$PKG/files/screens 2>/dev/null" > /dev/null 2>&1 && \
  adb pull "/sdcard/Android/data/$PKG/files/screens" e2e-logs/screens || true
# the interesting log lines, inline in the CI log
echo "=================== DubbingE2E / DubbingWorker / crashes ==================="
grep -E "DubbingE2E|DubbingWorker|AndroidRuntime|FATAL|F/linker|TestRunner" e2e-logs/logcat-full.txt \
  | grep -vE "chatty|Choreographer|\): 	at |\): Caused by|\.\.\. [0-9]+ more" | cut -c1-300 | tail -n 160 || true
exit $STATUS
