# Latency Benchmark README

## Abbreviation Meanings
- `T`: `llm-app` side pipeline steps (end-to-end)
- `S`: `tool-app service` side steps (inside service)
- `D`: Discovery phase steps (scan MCP info at startup)
- `T1`: App-selection prompt construction
- `T2`: First LLM network call
- `T3`: App-selection result parsing
- `T4`: Capability prompt construction
- `T5`: Second LLM network call
- `T6`: Command JSON reassembly
- `T7`: Service launch/scheduling delay (`S0 - T7b`)
- `T8`: Callback delivery delay (`T8a - S3b`)
- `S1`: Service-side JSON parsing
- `S2`: Service-side capability execution
- `S3`: `callback.send` self cost (`S3b - S3a`)
- `D1`: Discovery cost (scan all MCP services and apps)

## How To Run
Run from the repository root:

```bash
./scripts/run_latency_benchmark.sh
```

Optional parameters:

```bash
./scripts/run_latency_benchmark.sh -s <DEVICE_SERIAL> -o <OUTPUT_DIR> -t <TIMEOUT_SEC>
```
