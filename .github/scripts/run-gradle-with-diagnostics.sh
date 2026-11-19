#!/usr/bin/env bash
# Run one Gradle command with CI-only worker limiting and periodic runner resource snapshots.
# Developer builds are unaffected: this wrapper is used only by GitHub Actions workflows.
set -uo pipefail

if (( $# < 2 )); then
  echo "Usage: $0 <diagnostic-label> <path-to-gradlew> [Gradle arguments...]" >&2
  exit 64
fi

label="$1"
shift
gradle_executable="$1"
shift

if [[ "$(basename "$gradle_executable")" != "gradlew" ]]; then
  echo "::error::Expected a Gradle wrapper executable named gradlew, received: $gradle_executable" >&2
  exit 64
fi

max_workers="${GRADLE_CI_MAX_WORKERS:-2}"
if [[ ! "$max_workers" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error::GRADLE_CI_MAX_WORKERS must be a positive integer (received: $max_workers)." >&2
  exit 64
fi

cpu_count="$(nproc 2>/dev/null || printf '1')"
if [[ "$cpu_count" =~ ^[1-9][0-9]*$ ]] && (( max_workers > cpu_count )); then
  max_workers="$cpu_count"
fi

gradle_xmx="${GRADLE_CI_GRADLE_XMX:-5g}"
kotlin_xmx="${GRADLE_CI_KOTLIN_XMX:-2g}"
gradle_metaspace="${GRADLE_CI_GRADLE_METASPACE:-1g}"
kotlin_metaspace="${GRADLE_CI_KOTLIN_METASPACE:-768m}"
for setting in gradle_xmx kotlin_xmx gradle_metaspace kotlin_metaspace; do
  value="${!setting}"
  if [[ ! "$value" =~ ^[1-9][0-9]*[mMgG]$ ]]; then
    echo "::error::$setting must be a positive JVM memory size such as 2g or 768m (received: $value)." >&2
    exit 64
  fi
done
sample_seconds="${GRADLE_CI_SAMPLE_SECONDS:-15}"
if [[ ! "$sample_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error::GRADLE_CI_SAMPLE_SECONDS must be a positive integer (received: $sample_seconds)." >&2
  exit 64
fi

if [[ -n "${RUNNER_TEMP:-}" ]]; then
  report_root="$RUNNER_TEMP/gradle-ci-diagnostics"
else
  report_root="${TMPDIR:-/tmp}/gradle-ci-diagnostics"
fi
mkdir -p "$report_root"

safe_label="$(printf '%s' "$label" | tr -cs '[:alnum:]_.-' '-')"
resource_log="$report_root/$safe_label.resources.log"

workspace="$PWD"
if [[ -n "${GITHUB_WORKSPACE:-}" ]]; then
  workspace="$GITHUB_WORKSPACE"
fi

snapshot() {
  {
    printf '\n===== RESOURCE SNAPSHOT: %s =====\n' "$1"
    printf 'UTC: %s\n' "$(date -u --iso-8601=seconds 2>/dev/null || date -u)"
    printf 'Runner CPU count: %s | Gradle max workers: %s | Gradle heap: %s | Kotlin daemon heap: %s\n' "$cpu_count" "$max_workers" "$gradle_xmx" "$kotlin_xmx"

    echo "--- Memory ---"
    free -h 2>&1 || true
    if [[ -r /proc/meminfo ]]; then
      grep -E '^(MemTotal|MemFree|MemAvailable|SwapTotal|SwapFree|Cached|Buffers|SReclaimable):' /proc/meminfo || true
    fi

    echo "--- Memory pressure ---"
    if [[ -r /proc/pressure/memory ]]; then
      cat /proc/pressure/memory
    else
      echo "/proc/pressure/memory unavailable"
    fi

    echo "--- Cgroup memory limits, usage, and OOM counters ---"
    v2_path="$(awk -F: '$1 == "0" { print $3; exit }' /proc/self/cgroup 2>/dev/null)"
    v2_dir="/sys/fs/cgroup$v2_path"
    if [[ -n "$v2_path" && -d "$v2_dir" ]]; then
      for file in memory.current memory.peak memory.max memory.events memory.pressure; do
        if [[ -r "$v2_dir/$file" ]]; then
          printf '%s:\n' "$file"
          cat "$v2_dir/$file"
        fi
      done
    else
      v1_path="$(awk -F: '$2 ~ /(^|,)memory(,|$)/ { print $3; exit }' /proc/self/cgroup 2>/dev/null)"
      v1_mount="$(awk '$3 == "cgroup" && $4 ~ /(^|,)memory(,|$)/ { print $2; exit }' /proc/mounts 2>/dev/null)"
      if [[ -n "$v1_path" && -n "$v1_mount" ]]; then
        v1_dir="$v1_mount$v1_path"
        for file in memory.usage_in_bytes memory.max_usage_in_bytes memory.limit_in_bytes memory.failcnt memory.oom_control; do
          if [[ -r "$v1_dir/$file" ]]; then
            printf '%s:\n' "$file"
            cat "$v1_dir/$file"
          fi
        done
      else
        echo "No readable cgroup memory controller found."
      fi
    fi

    echo "--- Kernel OOM counters ---"
    if [[ -r /proc/vmstat ]]; then
      awk '$1 == "oom_kill" || $1 == "oom_group_kill" { print }' /proc/vmstat
    fi

    echo "--- Disk capacity and inodes ---"
    df -h "$workspace" / 2>&1 || true
    df -i "$workspace" / 2>&1 || true

    echo "--- Highest-RSS processes ---"
    ps -eo pid,ppid,rss,vsz,%mem,%cpu,etime,comm --sort=-rss 2>&1 | head -n 18 || true
    now_seconds="$SECONDS"
    if (( now_seconds - last_jvm_snapshot >= 60 )) || [[ "$1" == "initial" || "$1" == final-* || "$1" == terminated-* ]]; then
      echo "--- JVM heap snapshots (Gradle/Kotlin daemons and workers) ---"
      jcmd_bin=""
      if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jcmd" ]]; then jcmd_bin="$JAVA_HOME/bin/jcmd"; else jcmd_bin="$(command -v jcmd || true)"; fi
      if [[ -n "$jcmd_bin" ]]; then
        while read -r java_pid main_class; do
          [[ -n "$java_pid" && -n "$main_class" ]] || continue
          case "$main_class" in
            *GradleDaemon*|*KotlinCompileDaemon*|*GradleWorkerMain*|*com.android.tools.r8*)
              printf 'JVM PID=%s MAIN=%s\n' "$java_pid" "$main_class"
              timeout 8s "$jcmd_bin" "$java_pid" VM.flags 2>&1 || true
              timeout 8s "$jcmd_bin" "$java_pid" GC.heap_info 2>&1 || true
              ;;
          esac
        done < <("$jcmd_bin" -l 2>/dev/null | head -n 16)
      else
        echo "jcmd unavailable; JVM heap details were not sampled."
      fi
      last_jvm_snapshot="$now_seconds"
    fi
  } >> "$resource_log" 2>&1
}

monitor_pid=""
gradle_pid=""
last_jvm_snapshot=0

stop_monitor() {
  if [[ -n "$monitor_pid" ]]; then
    kill "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
    monitor_pid=""
  fi
}

handle_signal() {
  signal="$1"
  exit_code="$2"
  echo "::warning::Gradle diagnostic wrapper received SIG$signal; forwarding it to Gradle."
  if [[ -n "$gradle_pid" ]]; then
    kill -s "$signal" "$gradle_pid" 2>/dev/null || true
  fi
  stop_monitor
  snapshot "terminated-by-SIG$signal"
  exit "$exit_code"
}

trap 'handle_signal INT 130' INT
trap 'handle_signal TERM 143' TERM

{
  printf 'Gradle diagnostic label: %s\n' "$label"
  printf 'Gradle worker cap: %s\n' "$max_workers"
  printf 'Resource sample interval: %s seconds\n' "$sample_seconds"
  printf 'Resource log: %s\n' "$resource_log"
} | tee -a "$resource_log"

(
  while true; do
    snapshot "periodic"
    sleep "$sample_seconds"
  done
) >/dev/null 2>&1 &
monitor_pid=$!

# Override the large developer-machine heap only for this CI invocation. The Kotlin
# daemon is capped separately because it has an independent JVM and memory space.
gradle_jvmargs="-Xmx$gradle_xmx -XX:MaxMetaspaceSize=$gradle_metaspace -Dfile.encoding=UTF-8 -XX:+UseG1GC"
kotlin_jvmargs="-Xmx$kotlin_xmx -XX:MaxMetaspaceSize=$kotlin_metaspace"
# Retain parallel project execution while limiting workers and compiler JVMs.
set -- "$gradle_executable" \
  "--max-workers=$max_workers" \
  "-Dorg.gradle.jvmargs=$gradle_jvmargs" \
  "-Pkotlin.daemon.jvmargs=$kotlin_jvmargs" \
  "$@"

"$@" &
gradle_pid=$!
wait "$gradle_pid"
gradle_status=$?
gradle_pid=""

stop_monitor
snapshot "final-gradle-exit-$gradle_status"

printf 'Gradle diagnostic wrapper completed with exit code %s.\n' "$gradle_status"
exit "$gradle_status"
