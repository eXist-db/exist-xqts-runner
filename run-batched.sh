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
#   --output-dir DIR         output directory (default: target)
#   --test-set-pattern PAT   regex filter for test set names
#   --exclude-test-set SETS  comma-separated test sets to exclude
#   --enable-feature FEATS   comma-separated features to enable
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
OUTPUT_DIR="target"
TEST_SET_PATTERN=""
EXCLUDE_TEST_SETS=""
ENABLE_FEATURES=""
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
    --output-dir)   OUTPUT_DIR="$2"; shift 2 ;;
    --test-set-pattern) TEST_SET_PATTERN="$2"; shift 2 ;;
    --exclude-test-set) EXCLUDE_TEST_SETS="$2"; shift 2 ;;
    --enable-feature)   ENABLE_FEATURES="$2"; shift 2 ;;
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
echo "Version:    $XQTS_VERSION"
echo "Test sets:  $TOTAL"
echo "Batch size: $BATCH_SIZE"
echo "Batches:    $BATCHES"
echo "Heap:       $HEAP"
echo "Output:     $OUTPUT_DIR"
echo "JAR:        $JAR"
echo ""

# === Run batches ===
BATCH_NUM=0
FAILURES=0
START_TIME=$(date +%s)

for (( i=0; i<TOTAL; i+=BATCH_SIZE )); do
  BATCH_NUM=$((BATCH_NUM + 1))
  END=$((i + BATCH_SIZE))
  if (( END > TOTAL )); then END=$TOTAL; fi

  # Build comma-separated test set list for this batch
  BATCH_SETS=""
  for (( j=i; j<END; j++ )); do
    if [[ -n "$BATCH_SETS" ]]; then BATCH_SETS+=","; fi
    BATCH_SETS+="${SET_ARRAY[$j]}"
  done

  echo "=== Batch $BATCH_NUM/$BATCHES (sets $((i+1))-$END of $TOTAL) ==="

  if [[ "$DRY_RUN" == true ]]; then
    echo "  Sets: $BATCH_SETS"
    echo "  [dry run, skipping]"
    continue
  fi

  # Build runner command
  CMD=("$JAVA_HOME/bin/java" "-Xmx${HEAP}" "-jar" "$JAR"
    "--xqts-version" "$XQTS_VERSION"
    "--test-set" "$BATCH_SETS"
    "--local-dir" "$SCRIPT_DIR/work"
    "--output-dir" "$OUTPUT_DIR"
  )

  if [[ -n "$ENABLE_FEATURES" ]]; then
    CMD+=("--enable-feature" "$ENABLE_FEATURES")
  fi

  # Pass through extra args
  if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
    CMD+=("${EXTRA_ARGS[@]}")
  fi

  BATCH_START=$(date +%s)

  # Run with timeout (5 min per batch — no legitimate batch exceeds 3 min)
  # Use --kill-after=15 to SIGKILL Java processes that ignore SIGTERM
  # Redirect to temp file instead of piping through tail (pipe prevents timeout from killing process tree)
  BATCH_LOG=$(mktemp /tmp/xqts-batch.XXXXXX)
  set +e
  timeout --kill-after=15 300 "${CMD[@]}" > "$BATCH_LOG" 2>&1
  EXIT_CODE=$?
  set -e
  tail -20 "$BATCH_LOG"
  rm -f "$BATCH_LOG"

  BATCH_END=$(date +%s)
  BATCH_ELAPSED=$((BATCH_END - BATCH_START))

  if [[ $EXIT_CODE -eq 0 ]]; then
    echo "  Batch $BATCH_NUM completed in ${BATCH_ELAPSED}s"
  elif [[ $EXIT_CODE -eq 124 || $EXIT_CODE -eq 137 ]]; then
    echo "  WARNING: Batch $BATCH_NUM TIMED OUT after 300s (exit $EXIT_CODE)"
    FAILURES=$((FAILURES + 1))
  else
    echo "  WARNING: Batch $BATCH_NUM exited with code $EXIT_CODE (${BATCH_ELAPSED}s)"
    FAILURES=$((FAILURES + 1))
  fi
  echo ""
done

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
