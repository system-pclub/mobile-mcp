# Latency Benchmark 脚本操作文档

本文档对应脚本：`scripts/run_latency_benchmark.sh`

## 1. 脚本作用

这个脚本用于一键触发 `llm-app` 的延迟测试（benchmark），并把测试日志和两端 trace 导出到本地目录，便于后续统计分析。

它主要覆盖四件事：
- 启动 benchmark（`trace_benchmark=true`）
- 等待测试完成标志（`"record_type":"benchmark_done"`）
- 导出产物（`logcat` + `llm-app/tool-app` 的 `latency_trace.jsonl`）
- 自动生成统计汇总（`txt + csv`）

## 2. 脚本做了哪些操作

脚本按固定 7 步执行：

1. 清理旧数据
- `adb logcat -c`
- 清空 `com.example.llm_app` 的 `files/latency_trace.jsonl`
- 清空 `com.example.mcpdemo` 的 `files/latency_trace.jsonl`

2. 开始抓日志
- 后台执行：`adb logcat -v time -s LatencyTrace:D > latency_logcat.txt`

3. 预热 tool-app（前台）
- 执行：`adb shell am start -n com.example.mcpdemo/.MainActivity`

4. 启动 benchmark
- 执行：`adb shell am start -S -W -n com.example.llm_app/.MainActivity --ez trace_benchmark true`

5. 等待 benchmark 结束
- 循环检查 `latency_logcat.txt` 中是否出现：
  - `"record_type":"benchmark_done"`
- 超时则退出（默认 `900` 秒）

6. 导出 trace 文件
- 导出 `llm-app` trace 到 `llm_latency_trace.jsonl`
- 导出 `tool-app` trace 到 `tool_latency_trace.jsonl`

7. 自动统计分析
- 生成 `benchmark_summary_granular.txt`
- 生成 `report_avg_by_capability_step.csv`
- 生成 `report_avg_granular_s2_steps.csv`

## 3. 运行前置条件

- 已安装 `adb`，并可在终端直接执行
- 至少有一个设备在线（真机或模拟器）
- 设备上已安装以下 App（debug 包更稳）：
  - `com.example.llm_app`
  - `com.example.mcpdemo`
- `llm-app` 内存在可用 OpenAI key（否则 benchmark 会报错）

## 4. 如何运行

在仓库根目录执行：

```bash
scripts/run_latency_benchmark.sh
```

可选参数：

```bash
scripts/run_latency_benchmark.sh -s <DEVICE_SERIAL> -o <OUTPUT_DIR> -t <TIMEOUT_SEC>
```

参数说明：
- `-s`：指定设备序列号（多设备时建议显式指定）
- `-o`：输出目录（默认：`summary/benchmark_YYYYmmdd_HHMMSS`）
- `-t`：超时时间，单位秒（默认：`900`）

示例：

```bash
scripts/run_latency_benchmark.sh -s emulator-5554 -o summary/run_20260208 -t 1200
```

## 5. 输出结果说明

输出目录下有 6 个核心文件：

- `latency_logcat.txt`
  - 过滤 tag 为 `LatencyTrace` 的 logcat 原始日志
- `llm_latency_trace.jsonl`
  - `llm-app` 侧链路埋点（T 系列 + 部分同步点）
- `tool_latency_trace.jsonl`
  - `tool-app` 侧链路埋点（S 系列 + capability 内细粒度步骤）
- `benchmark_summary_granular.txt`
  - 文字汇总（包含缩写说明、D1、各功能均值与占比、token 均值）
- `report_avg_by_capability_step.csv`
  - 分 capability + step 的平均耗时表
- `report_avg_granular_s2_steps.csv`
  - S2 细粒度步骤平均耗时表

## 6. 常见问题排查

1. 一直卡住不结束
- 先看 `latency_logcat.txt` 是否有错误日志
- 是否出现 `"record_type":"benchmark_done"`；若没有，可能 benchmark 过程报错或被中断

2. 导出的 trace 文件为空
- 常见原因是 `run-as` 不可用（例如 release 包或包名不一致）
- 确认设备中安装的是可 `run-as` 的对应包

3. 多设备连接导致命令失败
- 使用 `-s <DEVICE_SERIAL>` 显式指定设备

4. 脚本超时退出
- 增大 `-t`，例如 `-t 1200`
- 检查网络、OpenAI key、App 是否崩溃

## 7. 快速检查命令

```bash
adb devices -l
scripts/run_latency_benchmark.sh -h
```
