# Mobile-MCP

## Overview

Mobile-MCP enables Android applications to expose capabilities to LLM-based assistants through a standardized protocol, using Android's native Intent mechanism.

## Architecture

```
User Natural Language Input
        ↓
    [LLM-APP]
        ├─ Service Discovery (PackageManager)
        ├─ App Selection (LLM reasoning)
        ├─ Service Selection (LLM reasoning)
        └─ Intent-based Invocation
        ↓
    [Tool-APP]
        └─ Service Handler → Result
```

## Comparison

| Approach         | Third-party Tools | Protocol-based | Security Control |
| ---------------- | ----------------- | -------------- | ---------------- |
| Coordinated APIs | ✗                 | ✗              | ✓                |
| GUI Observation  | ✓                 | ✗              | ✗                |
| **Mobile-MCP**   | **✓**             | **✓**          | **✓**            |

## Performance Benchmark

End-to-end latency breakdown:

| Component               | Latency | %     |
| ----------------------- | ------- | ----- |
| Service Data Collection | 17.4ms  | 1.2%  |
| Prompt Generation       | 1.6ms   | 0.1%  |
| App Selection (LLM)     | 673.3ms | 46.3% |
| Service Selection (LLM) | 749.9ms | 51.5% |
| Local Service Execution | 12.5ms  | 0.9%  |

## Reproducing Results

### Prerequisites

- Android device (API 24+)
- OpenAI API key
- adb, python3

### Quick Start

- Convert to `test-latency-benchmark` branch for recreating our performance results

```bash
# 1. Install apps
./gradlew :llm-app:installDebug
./gradlew :tool-app:installDebug

# 2. Configure API key
echo "your-api-key" > llm-app/app/src/main/assets/openai_key.txt

# 3. Run benchmark
./scripts/run_latency_benchmark.sh -o results/

# 4. View results
cat results/benchmark_summary_granular.txt
```

## Contributors

- Xiheng Li - [xhl0724@gmail.com](mailto:xhl0724@gmail.com)
- Mengting He - [mvh6224@psu.edu](mailto:mvh6224@psu.edu)
- Chengcheng Wan - [ccwan@sei.ecnu.edu.cn](mailto:ccwan@sei.ecnu.edu.cn)
- Linhai Song - [songlinhai@ict.ac.cn](mailto:songlinhai@ict.ac.cn)

## Video Link

- [Project Demo Video](https://youtu.be/Bc2LG3sR1NY)
