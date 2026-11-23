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
gradle_parallel="${GRADLE_CI_PARALLEL:-true}"
kotlin_parallel="${GRADLE_CI_KOTLIN_PARALLEL:-true}"
for setting in gradle_xmx kotlin_xmx gradle_metaspace kotlin_metaspace; do
  value="${!setting}"
  if [[ ! "$value" =~ ^[1-9][0-9]*[mMgG]$ ]]; then
    echo "::error::$setting must be a positive JVM memory size such as 2g or 768m (received: $value)." >&2
    exit 64
  fi
done
for setting in gradle_parallel kotlin_parallel; do
  value="${!setting}"
  if [[ "$value" != "true" && "$value" != "false" ]]; then
    echo "::error::$setting must be true or false (received: $value)." >&2
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

stream_snapshot() {
  local total_kb available_kb swap_free_kb
  local cgroup_path cgroup_dir current_bytes peak_bytes max_bytes oom_events
  local memory_psi cpu_psi io_psi loadavg top_rss timestamp current_mib peak_mib
  total_kb="$(awk '$1 == "MemTotal:" { print $2; exit }' /proc/meminfo 2>/dev/null || printf '0')"
  available_kb="$(awk '$1 == "MemAvailable:" { print $2; exit }' /proc/meminfo 2>/dev/null || printf '0')"
  swap_free_kb="$(awk '$1 == "SwapFree:" { print $2; exit }' /proc/meminfo 2>/dev/null || printf '0')"
  current_bytes=NA; peak_bytes=NA; max_bytes=NA; oom_events=NA
  cgroup_path="$(awk -F: '$1 == "0" { print $3; exit }' /proc/self/cgroup 2>/dev/null)"
  cgroup_dir="/sys/fs/cgroup$cgroup_path"
  if [[ -n "$cgroup_path" && -d "$cgroup_dir" ]]; then
    [[ -r "$cgroup_dir/memory.current" ]] && current_bytes="$(cat "$cgroup_dir/memory.current")"
    [[ -r "$cgroup_dir/memory.peak" ]] && peak_bytes="$(cat "$cgroup_dir/memory.peak")"
    [[ -r "$cgroup_dir/memory.max" ]] && max_bytes="$(cat "$cgroup_dir/memory.max")"
    if [[ -r "$cgroup_dir/memory.events" ]]; then
      oom_events="$(awk '$1 == "oom" || $1 == "oom_kill" || $1 == "high" || $1 == "max" { printf "%s=%s,", $1, $2 }' "$cgroup_dir/memory.events" | sed 's/,$//')"
    fi
  fi
  memory_psi=NA; cpu_psi=NA; io_psi=NA
  if [[ -r /proc/pressure/memory ]]; then
    memory_psi="$(awk '/^some / { for (i=2;i<=NF;i++) if ($i ~ /^avg10=/) some=$i } /^full / { for (i=2;i<=NF;i++) if ($i ~ /^avg10=/) full=$i } END { print some "/" full }' /proc/pressure/memory)"
  fi
  if [[ -r /proc/pressure/cpu ]]; then cpu_psi="$(awk '/^some / { for (i=2;i<=NF;i++) if ($i ~ /^avg10=/) { print $i; exit } }' /proc/pressure/cpu)"; fi
  if [[ -r /proc/pressure/io ]]; then io_psi="$(awk '/^some / { for (i=2;i<=NF;i++) if ($i ~ /^avg10=/) { print $i; exit } }' /proc/pressure/io)"; fi
  loadavg="$(awk '{ print $1 "," $2 "," $3 }' /proc/loadavg 2>/dev/null || printf 'NA')"
  top_rss="$(ps -eo rss,comm --sort=-rss 2>/dev/null | awk 'NR > 1 && NR <= 6 { printf "%s:%sMiB,", $2, int($1/1024) }' | sed 's/,$//')"
  timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  current_mib=NA; peak_mib=NA
  if [[ "$current_bytes" =~ ^[0-9]+$ ]]; then current_mib="$((current_bytes / 1048576))"; fi
  if [[ "$peak_bytes" =~ ^[0-9]+$ ]]; then peak_mib="$((peak_bytes / 1048576))"; fi
  printf '[ci-resource] utc=%s label=%s available_mib=%s total_mib=%s swap_free_mib=%s cgroup_current_mib=%s cgroup_peak_mib=%s cgroup_max=%s memory_events="%s" memory_psi_avg10="%s" cpu_psi_avg10="%s" io_psi_avg10="%s" loadavg="%s" top_rss="%s"\n' \
    "$timestamp" "$label" "$((available_kb / 1024))" "$((total_kb / 1024))" "$((swap_free_kb / 1024))" \
    "$current_mib" "$peak_mib" "$max_bytes" "$oom_events" "$memory_psi" "$cpu_psi" "$io_psi" "$loadavg" "$top_rss"
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
  stream_snapshot "terminated-by-SIG$signal"
  exit "$exit_code"
}

trap 'handle_signal INT 130' INT
trap 'handle_signal TERM 143' TERM

{
  printf 'Gradle diagnostic label: %s\n' "$label"
  printf 'Gradle worker cap: %s\n' "$max_workers"
  printf 'Gradle parallel: %s | Kotlin parallel: %s\n' "$gradle_parallel" "$kotlin_parallel"
  printf 'Resource sample interval: %s seconds\n' "$sample_seconds"
  printf 'Resource log: %s\n' "$resource_log"
} | tee -a "$resource_log"

(
  while true; do
    snapshot "periodic"
    stream_snapshot "periodic"
    sleep "$sample_seconds"
  done
) &
monitor_pid=$!

# Override the large developer-machine heap only for this CI invocation. The Kotlin
# daemon is capped separately because it has an independent JVM and memory space.
gradle_jvmargs="-Xmx$gradle_xmx -XX:MaxMetaspaceSize=$gradle_metaspace -Dfile.encoding=UTF-8 -XX:+UseG1GC"
kotlin_jvmargs="-Xmx$kotlin_xmx -XX:MaxMetaspaceSize=$kotlin_metaspace"
# Keep JVM and task concurrency settings identical across Gradle invocations in one CI job.
# This avoids retaining a developer-heap daemon beside a separately capped daemon.
set -- "$gradle_executable" \
  "--max-workers=$max_workers" \
  "-Dorg.gradle.jvmargs=$gradle_jvmargs" \
  "-Dorg.gradle.parallel=$gradle_parallel" \
  "-Pkotlin.daemon.jvmargs=$kotlin_jvmargs" \
  "-Pkotlin.parallel.tasks.in.project=$kotlin_parallel" \
  "$@"

"$@" &
gradle_pid=$!
wait "$gradle_pid"
gradle_status=$?
gradle_pid=""

stop_monitor
snapshot "final-gradle-exit-$gradle_status"
stream_snapshot "final-gradle-exit-$gradle_status"

printf 'Gradle diagnostic wrapper completed with exit code %s.\n' "$gradle_status"
exit "$gradle_status"
