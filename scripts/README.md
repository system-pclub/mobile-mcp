# Latency Benchmark README

## 缩写说明
- `T`：`llm-app` 侧链路步骤（端到端）
- `S`：`tool-app service` 侧步骤（服务内部）

## 运行方式
在仓库根目录执行：

```bash
./scripts/run_latency_benchmark.sh
```

可选参数：

```bash
./scripts/run_latency_benchmark.sh -s <DEVICE_SERIAL> -o <OUTPUT_DIR> -t <TIMEOUT_SEC>
```
