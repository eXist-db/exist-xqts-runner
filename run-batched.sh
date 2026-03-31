#!/usr/bin/env bash
#
# Batch XQTS Runner — runs the exist-xqts-runner JAR in batches to avoid OOM.
#
# Each batch runs in a fresh JVM, so thread pool / BrokerPool leaks are
# cleaned up between batches. JUnit XML results accumulate in a single
# output directory across batches.
#
# Usage:
#   ./run-batched.sh [OPTIONS]
#
# Options:
#   --xqts-version VERSION   3.1, HEAD, QT4, or FTTS (default: QT4)
#   --batch-size N           test sets per batch (default: 50)
#   --heap SIZE              JVM heap size (default: 4g)
#   --timeout SECS           per-batch timeout in seconds (default: 180)
#   --output-dir DIR         output directory (default: target)
#   --test-set-pattern PAT   regex filter for test set names
#   --exclude-test-set SETS  comma-separated test sets to exclude
#   --enable-feature FEATS   comma-separated features to enable
#   --parallel N             run N batch streams in parallel (default: 1)
#   --resume                 skip test sets that already have result XML
#   --dry-run                print batches without running
#   --                       remaining args passed through to runner JAR
#
# Examples:
#   ./run-batched.sh --xqts-version QT4 --batch-size 40 --heap 6g
#   ./run-batched.sh --xqts-version 3.1 --resume
#   ./run-batched.sh --xqts-version QT4 --test-set-pattern 'fn-.*' --batch-size 30

set -euo pipefail

# === Defaults ===
XQTS_VERSION="QT4"
BATCH_SIZE=50
HEAP="4g"
BATCH_TIMEOUT=300
OUTPUT_DIR="target"
TEST_SET_PATTERN=""
EXCLUDE_TEST_SETS=""
ENABLE_FEATURES=""
PARALLEL=1
RESUME=false
DRY_RUN=false
EXTRA_ARGS=()
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/exist-xqts-runner-assembly-2.0.0-SNAPSHOT.jar"
JAVA_HOME="${JAVA_HOME:-/Users/wicentowskijc/.asdf/installs/java/zulu-21.38.21}"

# === Parse args ===
while [[ $# -gt 0 ]]; do
  case "$1" in
    --xqts-version) XQTS_VERSION="$2"; shift 2 ;;
    --batch-size)   BATCH_SIZE="$2"; shift 2 ;;
    --heap)         HEAP="$2"; shift 2 ;;
    --timeout)      BATCH_TIMEOUT="$2"; shift 2 ;;
    --output-dir)   OUTPUT_DIR="$2"; shift 2 ;;
    --test-set-pattern) TEST_SET_PATTERN="$2"; shift 2 ;;
    --exclude-test-set) EXCLUDE_TEST_SETS="$2"; shift 2 ;;
    --enable-feature)   ENABLE_FEATURES="$2"; shift 2 ;;
    --parallel)     PARALLEL="$2"; shift 2 ;;
    --resume)       RESUME=true; shift ;;
    --dry-run)      DRY_RUN=true; shift ;;
    --)             shift; EXTRA_ARGS+=("$@"); break ;;
    *)              EXTRA_ARGS+=("$1"); shift ;;
  esac
done

# === Resolve catalog ===
case "$XQTS_VERSION" in
  3.1)   CATALOG="$SCRIPT_DIR/work/QT3_1_0/catalog.xml" ;;
  HEAD)  CATALOG="$SCRIPT_DIR/work/qt3tests-master/catalog.xml" ;;
  QT4)   CATALOG="$SCRIPT_DIR/work/qt4tests-master/catalog.xml" ;;
  FTTS)  CATALOG="$SCRIPT_DIR/work/XQFTTS_1_0_4/XQFTTSCatalog.xml" ;;
  *)     echo "ERROR: Unknown XQTS version: $XQTS_VERSION"; exit 1 ;;
esac

if [[ ! -f "$CATALOG" ]]; then
  echo "ERROR: Catalog not found: $CATALOG"
  echo "Run the JAR once with no test sets to trigger download, or check work/ dir."
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: Runner JAR not found: $JAR"
  exit 1
fi

# === Extract test set names from catalog ===
if [[ "$XQTS_VERSION" == "FTTS" ]]; then
  # XQFTTS uses a different catalog format
  ALL_SETS=$(grep '<test-group ' "$CATALOG" | sed 's/.*name="\([^"]*\)".*/\1/' | sort)
else
  ALL_SETS=$(grep '<test-set ' "$CATALOG" | sed 's/.*name="\([^"]*\)".*/\1/' | sort)
fi

# Apply pattern filter
if [[ -n "$TEST_SET_PATTERN" ]]; then
  ALL_SETS=$(echo "$ALL_SETS" | grep -E "$TEST_SET_PATTERN" || true)
fi

# Apply exclusions
if [[ -n "$EXCLUDE_TEST_SETS" ]]; then
  IFS=',' read -ra EXCL <<< "$EXCLUDE_TEST_SETS"
  for ex in "${EXCL[@]}"; do
    ALL_SETS=$(echo "$ALL_SETS" | grep -v "^${ex}$" || true)
  done
fi

# If --resume, skip test sets that already have XML results
if [[ "$RESUME" == true ]]; then
  DATA_DIR="$OUTPUT_DIR/junit/data"
  if [[ -d "$DATA_DIR" ]]; then
    BEFORE=$(echo "$ALL_SETS" | wc -l | tr -d ' ')
    FILTERED=""
    while IFS= read -r ts; do
      if [[ ! -f "$DATA_DIR/TEST-${ts}.xml" ]]; then
        FILTERED+="$ts"$'\n'
      fi
    done <<< "$ALL_SETS"
    ALL_SETS=$(echo "$FILTERED" | sed '/^$/d')
    AFTER=$(echo "$ALL_SETS" | wc -l | tr -d ' ')
    echo "Resume mode: skipping $((BEFORE - AFTER)) already-completed test sets ($AFTER remaining)"
  fi
fi

# Convert to array (portable — no mapfile)
SET_ARRAY=()
while IFS= read -r line; do
  line="${line// /}"
  [[ -n "$line" ]] && SET_ARRAY+=("$line")
done <<< "$ALL_SETS"

TOTAL=${#SET_ARRAY[@]}
BATCHES=$(( (TOTAL + BATCH_SIZE - 1) / BATCH_SIZE ))

echo "=== XQTS Batch Runner ==="
echo "Parser:     ${PARSER:-antlr2}"
echo "Version:    $XQTS_VERSION"
echo "Test sets:  $TOTAL"
echo "Batch size: $BATCH_SIZE"
echo "Batches:    $BATCHES"
echo "Parallel:   $PARALLEL"
echo "Heap:       $HEAP"
echo "Timeout:    ${BATCH_TIMEOUT}s"
echo "Output:     $OUTPUT_DIR"
echo "JAR:        $JAR"
echo ""

# === Run a single batch ===
# Args: batch_num total_batches start_idx end_idx stream_id
run_batch() {
  local batch_num=$1 total_batches=$2 start_idx=$3 end_idx=$4 stream_id=$5

  # Build comma-separated test set list
  local batch_sets=""
  for (( j=start_idx; j<end_idx; j++ )); do
    if [[ -n "$batch_sets" ]]; then batch_sets+=","; fi
    batch_sets+="${SET_ARRAY[$j]}"
  done

  echo "=== Batch $batch_num/$total_batches (sets $((start_idx+1))-$end_idx of $TOTAL) [stream $stream_id] ==="

  if [[ "$DRY_RUN" == true ]]; then
    echo "  Sets: $batch_sets"
    echo "  [dry run, skipping]"
    return 0
  fi

  # Each parallel stream gets its own eXist-db home directory to avoid
  # BrokerPool data directory lock conflicts between JVMs.
  local exist_home
  exist_home=$(mktemp -d /tmp/xqts-stream.XXXXXX)

  # Build runner command
  local cmd=("$JAVA_HOME/bin/java" "-Xmx${HEAP}" "-XX:+ExitOnOutOfMemoryError"
    "-Dexist.home=$exist_home"
    "-Dexist.parser=${PARSER:-antlr2}"
    "-jar" "$JAR"
    "--xqts-version" "$XQTS_VERSION"
    "--test-set" "$batch_sets"
    "--local-dir" "$SCRIPT_DIR/work"
    "--output-dir" "$OUTPUT_DIR"
  )

  if [[ -n "$ENABLE_FEATURES" ]]; then
    cmd+=("--enable-feature" "$ENABLE_FEATURES")
  fi

  if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
    cmd+=("${EXTRA_ARGS[@]}")
  fi

  local batch_start batch_end batch_elapsed exit_code
  batch_start=$(date +%s)

  local batch_log jstack_file
  batch_log=$(mktemp /tmp/xqts-batch.XXXXXX)
  jstack_file="$OUTPUT_DIR/jstack-batch-${batch_num}.txt"

  # Run the batch in the background so we can monitor for timeouts
  # and capture a thread dump before the hard kill.
  local jstack_buffer=15
  local jstack_delay=$((BATCH_TIMEOUT - jstack_buffer))
  if (( jstack_delay < 10 )); then jstack_delay=$((BATCH_TIMEOUT / 2)); fi

  timeout --kill-after=30 "$BATCH_TIMEOUT" "${cmd[@]}" > "$batch_log" 2>&1 &
  local batch_pid=$!

  # Monitor: if still running near timeout, capture jstack
  (
    sleep "$jstack_delay"
    if kill -0 $batch_pid 2>/dev/null; then
      echo "  Batch $batch_num approaching timeout — capturing thread dump..."
      local java_pid
      java_pid=$(pgrep -P $batch_pid java 2>/dev/null | head -1 || true)
      if [[ -n "$java_pid" ]]; then
        "$JAVA_HOME/bin/jstack" "$java_pid" > "$jstack_file" 2>&1 || true
        echo "  Thread dump saved to $jstack_file"
      fi
    fi
  ) &
  local monitor_pid=$!

  # Wait for the batch to complete (or timeout)
  exit_code=0
  wait $batch_pid 2>/dev/null || exit_code=$?

  # Clean up monitor and any lingering Java processes
  kill $monitor_pid 2>/dev/null || true
  wait $monitor_pid 2>/dev/null || true

  tail -20 "$batch_log" 2>/dev/null || true
  rm -f "$batch_log" 2>/dev/null || true

  # Kill any lingering Java processes from this batch (BrokerPool shutdown hangs)
  pkill -9 -f "exist.home=$exist_home" 2>/dev/null || true
  sleep 1
  rm -rf "$exist_home" 2>/dev/null || true

  batch_end=$(date +%s)
  batch_elapsed=$((batch_end - batch_start))

  if [[ $exit_code -eq 124 || $exit_code -eq 137 ]]; then
    echo "  WARNING: Batch $batch_num TIMED OUT after ${BATCH_TIMEOUT}s (exit $exit_code) [stream $stream_id]"
    if [[ -f "$jstack_file" ]]; then
      echo "  Thread dump: $jstack_file"
    fi
    return 1
  elif [[ $exit_code -gt 1 && $exit_code -ne 255 ]]; then
    echo "  WARNING: Batch $batch_num crashed with code $exit_code (${batch_elapsed}s) [stream $stream_id]"
    return 1
  else
    # exit 0 = all tests passed, exit 1 = some test failures (normal), exit 255 = runner error (non-fatal)
    echo "  Batch $batch_num completed in ${batch_elapsed}s (exit $exit_code) [stream $stream_id]"
  fi
  return 0
}

# === Run a stream of batches sequentially ===
# Args: stream_id batch_indices...
# Writes failure count to /tmp/xqts-stream-failures-$stream_id
run_stream() {
  local stream_id=$1; shift
  local failures=0
  local indices=("$@")

  for batch_idx in "${indices[@]}"; do
    local start_idx=$((batch_idx * BATCH_SIZE))
    local end_idx=$((start_idx + BATCH_SIZE))
    if (( end_idx > TOTAL )); then end_idx=$TOTAL; fi
    local batch_num=$((batch_idx + 1))

    run_batch "$batch_num" "$BATCHES" "$start_idx" "$end_idx" "$stream_id" || failures=$((failures + 1))
    echo ""
  done

  echo "$failures" > "/tmp/xqts-stream-failures-$stream_id"
}

# === Dispatch batches ===
mkdir -p "$OUTPUT_DIR/junit/data"
START_TIME=$(date +%s)
FAILURES=0

if [[ "$PARALLEL" -le 1 ]]; then
  # Sequential mode (original behavior)
  for (( batch_idx=0; batch_idx<BATCHES; batch_idx++ )); do
    local_start=$((batch_idx * BATCH_SIZE))
    local_end=$((local_start + BATCH_SIZE))
    if (( local_end > TOTAL )); then local_end=$TOTAL; fi

    run_batch "$((batch_idx + 1))" "$BATCHES" "$local_start" "$local_end" "1" || FAILURES=$((FAILURES + 1))
    echo ""
  done
else
  # Parallel mode: distribute batches round-robin across streams
  echo "Starting $PARALLEL parallel streams..."
  echo ""

  # Build batch index arrays for each stream
  declare -a STREAM_PIDS
  for (( s=0; s<PARALLEL; s++ )); do
    STREAM_INDICES=()
    for (( batch_idx=s; batch_idx<BATCHES; batch_idx+=PARALLEL )); do
      STREAM_INDICES+=("$batch_idx")
    done

    if [[ ${#STREAM_INDICES[@]} -gt 0 ]]; then
      run_stream "$((s + 1))" "${STREAM_INDICES[@]}" &
      STREAM_PIDS+=($!)
    fi
  done

  # Wait for all streams
  for pid in "${STREAM_PIDS[@]}"; do
    wait "$pid" || true
  done

  # Collect failure counts
  for (( s=0; s<PARALLEL; s++ )); do
    local_file="/tmp/xqts-stream-failures-$((s + 1))"
    if [[ -f "$local_file" ]]; then
      FAILURES=$((FAILURES + $(cat "$local_file")))
      rm -f "$local_file"
    fi
  done
fi

END_TIME=$(date +%s)
TOTAL_ELAPSED=$((END_TIME - START_TIME))

echo "=== Summary ==="
echo "Total time: ${TOTAL_ELAPSED}s ($((TOTAL_ELAPSED / 60))m $((TOTAL_ELAPSED % 60))s)"
echo "Batches:    $BATCHES ($FAILURES failed)"

# Write timing log for trend analysis
TIMING_LOG="$OUTPUT_DIR/timing.log"
echo "run=$(basename $OUTPUT_DIR) version=$XQTS_VERSION total_time=${TOTAL_ELAPSED}s batches=$BATCHES failures=$FAILURES date=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$TIMING_LOG"

# Count result files
if [[ -d "$OUTPUT_DIR/junit/data" ]]; then
  RESULT_COUNT=$(ls "$OUTPUT_DIR/junit/data"/TEST-*.xml 2>/dev/null | wc -l | tr -d ' ')
  echo "Results:    $RESULT_COUNT XML files in $OUTPUT_DIR/junit/data/"

  # Quick aggregate: count pass/fail/error across all XML files
  if command -v xmllint &>/dev/null && [[ $RESULT_COUNT -gt 0 ]]; then
    TOTAL_TESTS=0
    TOTAL_FAILURES=0
    TOTAL_ERRORS=0
    TOTAL_SKIPPED=0
    for f in "$OUTPUT_DIR/junit/data"/TEST-*.xml; do
      T=$(xmllint --xpath 'string(//testsuite/@tests)' "$f" 2>/dev/null || echo 0)
      F=$(xmllint --xpath 'string(//testsuite/@failures)' "$f" 2>/dev/null || echo 0)
      E=$(xmllint --xpath 'string(//testsuite/@errors)' "$f" 2>/dev/null || echo 0)
      S=$(xmllint --xpath 'string(//testsuite/@skipped)' "$f" 2>/dev/null || echo 0)
      TOTAL_TESTS=$((TOTAL_TESTS + T))
      TOTAL_FAILURES=$((TOTAL_FAILURES + F))
      TOTAL_ERRORS=$((TOTAL_ERRORS + E))
      TOTAL_SKIPPED=$((TOTAL_SKIPPED + S))
    done
    PASSED=$((TOTAL_TESTS - TOTAL_FAILURES - TOTAL_ERRORS - TOTAL_SKIPPED))
    echo ""
    echo "Aggregate:  $TOTAL_TESTS tests, $PASSED passed, $TOTAL_FAILURES failed, $TOTAL_ERRORS errors, $TOTAL_SKIPPED skipped"
    if [[ $TOTAL_TESTS -gt 0 ]]; then
      PCT=$(echo "scale=1; $PASSED * 100 / $TOTAL_TESTS" | bc)
      echo "Pass rate:  ${PCT}% ($PASSED / $TOTAL_TESTS)"
    fi
  fi
fi

# Per-test-set timing report (sorted by time, descending)
if [[ -d "$OUTPUT_DIR/junit/data" ]] && command -v python3 &>/dev/null; then
  TIMING_REPORT="$OUTPUT_DIR/timing-report.txt"
  python3 -c "
import xml.etree.ElementTree as ET, glob, sys
results = []
for f in sorted(glob.glob('$OUTPUT_DIR/junit/data/TEST-*.xml')):
    root = ET.parse(f).getroot()
    name = root.get('name','').replace('XQTS_QT4.','').replace('XQTS_3_1.','').replace('XQTS_FTTS_1_0.','')
    t = float(root.get('time','0'))
    tests = int(root.get('tests','0'))
    fails = int(root.get('failures','0'))
    errs = int(root.get('errors','0'))
    passed = tests - fails - errs - int(root.get('skipped','0'))
    results.append((t, name, tests, passed, fails, errs))
results.sort(reverse=True)
total_time = sum(r[0] for r in results)
print(f'Per-test-set timing report ({len(results)} sets, {total_time:.0f}s total)')
print(f'{\"Time\":>8} {\"Tests\":>6} {\"Pass\":>6} {\"Fail\":>5} {\"Err\":>4}  Set')
for t, name, tests, p, f, e in results:
    if t >= 1.0:
        flag = ' !!!' if t > 60 else ' !' if t > 10 else ''
        print(f'{t:>7.1f}s {tests:>6} {p:>6} {f:>5} {e:>4}  {name}{flag}')
slow = [r for r in results if r[0] > 60]
if slow:
    print(f'\n{len(slow)} test sets >60s — investigate for performance issues')
" 2>/dev/null | tee "$TIMING_REPORT"
  echo ""
  echo "Timing report saved to: $TIMING_REPORT"
fi

# List test sets that were expected but produced no results (killed by timeout)
if [[ $FAILURES -gt 0 ]]; then
  echo ""
  echo "WARNING: $FAILURES batch(es) timed out or failed. Some test sets may have no results."
fi

echo ""
echo "Done."
