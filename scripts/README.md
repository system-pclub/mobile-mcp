# Latency Benchmark README

## 缩写说明
- `T`：`llm-app` 侧链路步骤（端到端）
- `S`：`tool-app service` 侧步骤（服务内部）
- `D`：Discovery 阶段步骤（启动时扫描 MCP 信息）
- `T1`：App 选择 Prompt 构建
- `T2`：第 1 次 LLM 网络调用
- `T3`：App 选择结果解析
- `T4`：Capability Prompt 构建
- `T5`：第 2 次 LLM 网络调用
- `T6`：命令 JSON 重组
- `T7`：Service 拉起/调度延迟（`S0 - T7b`）
- `T8`：回调投递延迟（`T8a - S3b`）
- `S1`：服务端 JSON 解析
- `S2`：服务端 capability 执行
- `S3`：`callback.send` 自身耗时（`S3b - S3a`）
- `D1`：discovery 耗时（扫描全部 MCP service 和 app 信息）

## 运行方式
在仓库根目录执行：

```bash
./scripts/run_latency_benchmark.sh
```

可选参数：

```bash
./scripts/run_latency_benchmark.sh -s <DEVICE_SERIAL> -o <OUTPUT_DIR> -t <TIMEOUT_SEC>
```
