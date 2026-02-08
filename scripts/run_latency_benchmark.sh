#!/usr/bin/env bash
set -euo pipefail

SERIAL=""
OUT_DIR=""
TIMEOUT_SEC=900
LOGCAT_PID=""

usage() {
  cat <<'EOF2'
Usage:
  scripts/run_latency_benchmark.sh [-s DEVICE_SERIAL] [-o OUTPUT_DIR] [-t TIMEOUT_SEC]

Options:
  -s  adb device serial (optional when only one device is connected)
  -o  output directory (default: summary/benchmark_YYYYmmdd_HHMMSS)
  -t  timeout seconds (default: 900)
  -h  show this help
EOF2
}

while getopts ":s:o:t:h" opt; do
  case "$opt" in
    s) SERIAL="$OPTARG" ;;
    o) OUT_DIR="$OPTARG" ;;
    t) TIMEOUT_SEC="$OPTARG" ;;
    h)
      usage
      exit 0
      ;;
    \?)
      echo "Unknown option: -$OPTARG" >&2
      usage
      exit 1
      ;;
  esac
done

adb_cmd() {
  if [[ -n "$SERIAL" ]]; then
    adb -s "$SERIAL" "$@"
  else
    adb "$@"
  fi
}

cleanup() {
  if [[ -n "${LOGCAT_PID:-}" ]] && kill -0 "$LOGCAT_PID" 2>/dev/null; then
    echo "Stopping logcat process..."
    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# Check dependencies
if ! command -v adb >/dev/null 2>&1; then
  echo "Error: adb not found in PATH" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "Error: python3 not found in PATH" >&2
  exit 1
fi

echo "[0/7] Checking device connection..."
adb_cmd wait-for-device >/dev/null

# Verify device is accessible
if ! adb_cmd shell echo "OK" >/dev/null 2>&1; then
  echo "Error: Device connected but shell access failed" >&2
  exit 1
fi

# Check if llm-app is installed
if ! adb_cmd shell pm list packages | grep -q "com.example.llm_app"; then
  echo "Error: com.example.llm_app not installed on device" >&2
  exit 1
fi

# Setup output directory
if [[ -z "$OUT_DIR" ]]; then
  TS="$(date +"%Y%m%d_%H%M%S")"
  OUT_DIR="summary/benchmark_${TS}"
fi
mkdir -p "$OUT_DIR"

LOG_FILE="${OUT_DIR}/latency_logcat.txt"
LLM_TRACE="${OUT_DIR}/llm_latency_trace.jsonl"
TOOL_TRACE="${OUT_DIR}/tool_latency_trace.jsonl"
SUMMARY_FILE="${OUT_DIR}/benchmark_summary_granular.txt"
AVG_STEP_CSV="${OUT_DIR}/report_avg_by_capability_step.csv"
AVG_GRANULAR_CSV="${OUT_DIR}/report_avg_granular_s2_steps.csv"

echo "[1/7] Clearing previous logs/traces..."
adb_cmd logcat -c

if ! adb_cmd shell run-as com.example.llm_app rm -f files/latency_trace.jsonl 2>/dev/null \
  || ! adb_cmd shell run-as com.example.llm_app touch files/latency_trace.jsonl 2>/dev/null; then
  echo "Warning: Failed to clear llm_app trace"
fi

if ! adb_cmd shell run-as com.example.mcpdemo rm -f files/latency_trace.jsonl 2>/dev/null \
  || ! adb_cmd shell run-as com.example.mcpdemo touch files/latency_trace.jsonl 2>/dev/null; then
  echo "Warning: Failed to clear mcpdemo trace"
fi

echo "[2/7] Starting latency log capture..."
adb_cmd logcat -v time -s LatencyTrace:D >"$LOG_FILE" &
LOGCAT_PID=$!

# Wait for logcat to establish connection
sleep 1

echo "[3/7] Bringing tool-app to foreground..."
if ! adb_cmd shell am start -n com.example.mcpdemo/.MainActivity >/dev/null 2>&1; then
  echo "Warning: Failed to start tool-app (may not be installed)"
fi

echo "[4/7] Launching llm-app benchmark..."
adb_cmd shell am start -S -W -n com.example.llm_app/.MainActivity --ez trace_benchmark true >/dev/null

echo "[5/7] Waiting for benchmark_done (timeout=${TIMEOUT_SEC}s)..."
START_TS="$(date +%s)"
LAST_PROGRESS_SEC=-1
NO_LOG_HINTED=0
while true; do
  if [[ -f "$LOG_FILE" ]] && grep -q '"record_type":"benchmark_done"' "$LOG_FILE"; then
    echo "Benchmark completed successfully."
    break
  fi
  
  NOW_TS="$(date +%s)"
  ELAPSED=$((NOW_TS - START_TS))
  
  if (( ELAPSED > TIMEOUT_SEC )); then
    echo "Error: Timed out after ${TIMEOUT_SEC}s waiting for benchmark_done" >&2
    echo "Check log file: $LOG_FILE" >&2
    if [[ ! -s "$LOG_FILE" ]]; then
      echo "Hint: no LatencyTrace logs captured. Verify app emits tag 'LatencyTrace' and trace_benchmark flow is active." >&2
    fi
    exit 1
  fi
  
  if (( NO_LOG_HINTED == 0 && ELAPSED >= 20 )) && [[ ! -s "$LOG_FILE" ]]; then
    echo "  ... no LatencyTrace logs yet; benchmark may not have started."
    NO_LOG_HINTED=1
  fi

  # Progress indicator every 30 seconds
  if (( ELAPSED > 0 && ELAPSED % 30 == 0 && ELAPSED != LAST_PROGRESS_SEC )); then
    echo "  ... still waiting (${ELAPSED}s elapsed)"
    LAST_PROGRESS_SEC=$ELAPSED
  fi
  
  sleep 0.5
done

cleanup
trap - EXIT INT TERM

echo "[6/7] Exporting trace files..."
adb_cmd exec-out run-as com.example.llm_app cat files/latency_trace.jsonl >"$LLM_TRACE" 2>/dev/null || true
adb_cmd exec-out run-as com.example.mcpdemo cat files/latency_trace.jsonl >"$TOOL_TRACE" 2>/dev/null || true

echo "[7/7] Analyzing results..."
if ! python3 - "$OUT_DIR" <<'PY'
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

out_dir = Path(sys.argv[1])
log_file = out_dir / "latency_logcat.txt"
llm_trace = out_dir / "llm_latency_trace.jsonl"
tool_trace = out_dir / "tool_latency_trace.jsonl"
summary_file = out_dir / "benchmark_summary_granular.txt"
avg_step_csv = out_dir / "report_avg_by_capability_step.csv"
avg_granular_csv = out_dir / "report_avg_granular_s2_steps.csv"

caps = ["clock_in_today", "query_clock_in", "make_up_clock_in"]
main_steps = ["T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "S1", "S2", "S3"]
all_steps_csv = main_steps + ["T9"]
granular_order = {
    "clock_in_today": ["S2_CLOCK_BROADCAST_CLICK", "S2_CLOCK_BUILD_SUCCESS_JSON"],
    "query_clock_in": ["S2_QUERY_READ_DATE", "S2_QUERY_HAS_CLOCKED_IN", "S2_QUERY_BUILD_RESULT_JSON"],
    "make_up_clock_in": [
        "S2_MAKEUP_READ_DATE",
        "S2_MAKEUP_WRITE_CLOCK_IN",
        "S2_MAKEUP_BROADCAST_UI_REFRESH",
        "S2_MAKEUP_BUILD_RESULT_JSON",
    ],
}

def load_jsonl(path: Path):
    if not path.exists():
        return []
    out = []
    try:
        for ln in path.read_text(encoding='utf-8', errors='ignore').splitlines():
            ln = ln.strip()
            if not ln:
                continue
            try:
                out.append(json.loads(ln))
            except json.JSONDecodeError:
                continue
    except Exception as e:
        print(f"Warning: Failed to read {path}: {e}", file=sys.stderr)
    return out

def avg(arr):
    return sum(arr) / len(arr) if arr else 0.0

llm_records = load_jsonl(llm_trace)
tool_records = load_jsonl(tool_trace)

llm_steps = [
    r for r in llm_records
    if r.get("record_type") == "step"
    and r.get("capability") in caps
]
tool_steps = [
    r for r in tool_records
    if r.get("record_type") == "step"
    and r.get("capability") in caps
]

vals = defaultdict(list)
for r in llm_steps:
    vals[(r["capability"], r.get("step"))].append(float(r.get("duration_ms", 0.0)))

gran_vals = defaultdict(list)
for r in tool_steps:
    st = r.get("step")
    if isinstance(st, str) and st.startswith("S2_"):
        gran_vals[(r["capability"], st)].append(float(r.get("duration_ms", 0.0)))

pt = defaultdict(list)
ct = defaultdict(list)
for r in llm_steps:
    st = r.get("step")
    if st in {"T2", "T5"}:
        p = r.get("prompt_tokens", -1)
        c = r.get("completion_tokens", -1)
        if isinstance(p, (int, float)) and p >= 0:
            pt[(r["capability"], st)].append(float(p))
        if isinstance(c, (int, float)) and c >= 0:
            ct[(r["capability"], st)].append(float(c))

discovery_ms = None
if log_file.exists():
    try:
        for line in log_file.read_text(encoding='utf-8', errors='ignore').splitlines():
            if "D1_DISCOVERY_LOAD_MCP_SERVICES" not in line:
                continue
            idx = line.find("{")
            if idx < 0:
                continue
            try:
                obj = json.loads(line[idx:])
                discovery_ms = float(obj.get("duration_ms", 0.0))
                break
            except Exception:
                continue
    except Exception as e:
        print(f"Warning: Failed to parse discovery time from log: {e}", file=sys.stderr)

if discovery_ms is None:
    for r in llm_records:
        if r.get("record_type") == "step" and r.get("step") == "D1_DISCOVERY_LOAD_MCP_SERVICES":
            try:
                discovery_ms = float(r.get("duration_ms", 0.0))
                break
            except Exception:
                pass

# CSV 1: common steps
try:
    with avg_step_csv.open("w", newline="", encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=["capability", "step", "runs", "avg_ms"])
        writer.writeheader()
        for cap in caps:
            for st in all_steps_csv:
                arr = vals.get((cap, st), [])
                if arr:
                    writer.writerow({
                        "capability": cap,
                        "step": st,
                        "runs": len(arr),
                        "avg_ms": f"{avg(arr):.3f}",
                    })
except Exception as e:
    print(f"Error writing CSV: {e}", file=sys.stderr)
    sys.exit(1)

# CSV 2: granular S2 steps
try:
    with avg_granular_csv.open("w", newline="", encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=["capability", "step", "runs", "avg_ms"])
        writer.writeheader()
        for cap in caps:
            for st in granular_order[cap]:
                arr = gran_vals.get((cap, st), [])
                if arr:
                    writer.writerow({
                        "capability": cap,
                        "step": st,
                        "runs": len(arr),
                        "avg_ms": f"{avg(arr):.6f}",
                    })
except Exception as e:
    print(f"Error writing granular CSV: {e}", file=sys.stderr)
    sys.exit(1)

sample_counts = []
for cap in caps:
    sample_counts.append(len(vals.get((cap, "T9"), [])))

if sample_counts and len(set(sample_counts)) == 1:
    sample_text = f"每功能{sample_counts[0]}次"
else:
    sample_text = "不一致: " + ", ".join(f"{caps[i]}={sample_counts[i]}" for i in range(len(caps)))

lines = []
lines.append("字母缩写说明")
lines.append("- T = LLM-app 侧链路步骤（端到端）")
lines.append("- S = Tool-app Service 侧步骤（服务内部）")
lines.append("- D = Discovery 阶段步骤（启动时扫描MCP信息）")
lines.append("- T1: App选择 Prompt 构建")
lines.append("- T2: 第1次 LLM 网络调用")
lines.append("- T3: App选择结果解析")
lines.append("- T4: Capability Prompt 构建")
lines.append("- T5: 第2次 LLM 网络调用")
lines.append("- T6: 命令 JSON 重组")
lines.append("- T7a: startService 调用前")
lines.append("- T7b: startService 调用后")
lines.append("- T7: Service 拉起/调度延迟（S0 - T7b）")
lines.append("- T8a: llm-app Receiver 收到回调入口")
lines.append("- T8: 回调投递延迟（T8a - S3b）")
lines.append("- T9: 端到端总耗时（从T0到最终结果）")
lines.append("- S0: onStartCommand 进入")
lines.append("- S1: 服务端 JSON 解析")
lines.append("- S2: 服务端 capability 执行")
lines.append("- S3a: callback.send 调用前")
lines.append("- S3b: callback.send 调用后")
lines.append("- S3: callback.send 自身耗时（S3b - S3a）")
lines.append("- D1: discovery耗时（扫描全部MCP service和app信息）")
lines.append("")
lines.append("脚本测试汇总（run_latency_benchmark.sh）")
lines.append(f"输出目录: {out_dir}")
lines.append(f"样本量: {sample_text}")
lines.append(f"D1 discovery耗时(ms): {discovery_ms if discovery_ms is not None else '未捕获'}")
lines.append(f"D1 discovery是否已记录: {'是' if discovery_ms is not None else '否'}")
lines.append("")

for cap in caps:
    t9_arr = vals.get((cap, "T9"), [])
    if not t9_arr:
        lines.append(f"功能: {cap}")
        lines.append("- 无有效T9数据")
        lines.append("")
        continue
    
    t9 = avg(t9_arr)
    if t9 <= 0:
        lines.append(f"功能: {cap}")
        lines.append("- T9平均为0，跳过百分比计算")
        lines.append("")
        continue

    lines.append(f"功能: {cap}")
    lines.append(f"- T9平均: {t9:.3f} ms")
    for st in main_steps:
        arr = vals.get((cap, st), [])
        if not arr:
            continue
        st_avg = avg(arr)
        lines.append(f"  - {st}: {st_avg:.3f} ms ({(st_avg / t9) * 100:.2f}%)")

    lines.append("  - S2细粒度:")
    for st in granular_order[cap]:
        arr = gran_vals.get((cap, st), [])
        if arr:
            lines.append(f"    * {st}: {avg(arr):.6f} ms")
        else:
            lines.append(f"    * {st}: 无数据")

    t2_in = avg(pt.get((cap, "T2"), []))
    t2_out = avg(ct.get((cap, "T2"), []))
    t5_in = avg(pt.get((cap, "T5"), []))
    t5_out = avg(ct.get((cap, "T5"), []))
    lines.append(
        f"  - token: T2({t2_in:.2f}/{t2_out:.2f}), "
        f"T5({t5_in:.2f}/{t5_out:.2f}), "
        f"total({(t2_in + t5_in):.2f}/{(t2_out + t5_out):.2f})"
    )
    lines.append("")

try:
    summary_file.write_text("\n".join(lines) + "\n", encoding='utf-8')
except Exception as e:
    print(f"Error writing summary: {e}", file=sys.stderr)
    sys.exit(1)
PY
then
  echo ""
  echo "Error: Failed to analyze results" >&2
  echo "Raw files available at: $OUT_DIR" >&2
  exit 1
fi

echo ""
echo "=== Benchmark Results ==="
echo "Output directory: $OUT_DIR"
echo ""

# Verify logcat
if [[ -s "$LOG_FILE" ]]; then
  LINE_COUNT=$(wc -l < "$LOG_FILE")
  echo "✓ Logcat: $LOG_FILE ($LINE_COUNT lines)"
else
  echo "✗ Warning: Logcat file is empty"
fi

# Verify LLM trace
if [[ -s "$LLM_TRACE" ]]; then
  RECORD_COUNT=$(wc -l < "$LLM_TRACE")
  echo "✓ LLM trace: $LLM_TRACE ($RECORD_COUNT records)"
else
  echo "✗ Warning: LLM trace is empty or missing"
fi

# Verify tool trace
if [[ -s "$TOOL_TRACE" ]]; then
  RECORD_COUNT=$(wc -l < "$TOOL_TRACE")
  echo "✓ Tool trace: $TOOL_TRACE ($RECORD_COUNT records)"
else
  echo "✗ Warning: Tool trace is empty or missing"
fi

# Verify summary
if [[ -s "$SUMMARY_FILE" ]]; then
  echo "✓ Summary: $SUMMARY_FILE"
else
  echo "✗ Warning: Summary file is empty or missing"
fi

# Verify CSVs
if [[ -s "$AVG_STEP_CSV" ]]; then
  ROW_COUNT=$(tail -n +2 "$AVG_STEP_CSV" | wc -l)
  echo "✓ Step CSV: $AVG_STEP_CSV ($ROW_COUNT data rows)"
else
  echo "✗ Warning: Step CSV is empty or missing"
fi

if [[ -s "$AVG_GRANULAR_CSV" ]]; then
  ROW_COUNT=$(tail -n +2 "$AVG_GRANULAR_CSV" | wc -l)
  echo "✓ Granular CSV: $AVG_GRANULAR_CSV ($ROW_COUNT data rows)"
else
  echo "✗ Warning: Granular CSV is empty or missing"
fi

echo ""
echo "Benchmark completed successfully!"
