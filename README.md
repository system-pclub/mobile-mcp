# Mobile-MCP

## Overview

Mobile-MCP enables Android applications to expose capabilities to LLM-based assistants through a standardized protocol, using Android's native Intent mechanism.

## Project Structure

```text
mobile-mcp/
├── llm-app/                                # AI Assistant app
│   └── app/src/main/
│       ├── AndroidManifest.xml             # App registry
│       ├── java/com/example/llm_app/
│       │   ├── MainActivity.kt             # Discover MCP services, plan via LLM, invoke target service
│       │   ├── McpResultBus.kt             # One-shot callback registry keyed by requestId
│       │   └── McpResultReceiver.kt        # Receives service callback broadcast and dispatches to McpResultBus
│       └── res/                            # UI/resources
├── tool-app/                               # Tool provider app
│   └── app/src/main/
│       ├── AndroidManifest.xml             # App registry: exported MCP service, capability metadata link
│       ├── java/com/example/mcpdemo/
│       │   ├── MainActivity.java           # Clock-in demo UI and local interactions
│       │   ├── CommandGatewayService.java  # Parses MCP command JSON and returns callback result
│       │   └── ClockInManager.java         # Stores/queries clock-in data
│       └── res/xml/mcp_capabilities.xml    # Tool capability schema
├── gradle/wrapper/                         # Gradle wrapper config
├── settings.gradle.kts                     # Composite build entry
├── build.gradle.kts                        # Root build config
├── gradle.properties                       # Root Gradle properties
├── gradlew / gradlew.bat                   # Build launchers
└── README.md
```

## Architecture

```
User Natural Language Input
          ↓
      [LLM-APP]
          ├─ MCP Service Discovery (PackageManager)
          ├─ App Selection (LLM reasoning)
          ├─ Service Selection (LLM reasoning)
          └─ Intent-based Invocation (with PendingIntent callback)
          ↓
      [Tool-APP]
          ├─ CommandGatewayService (parse/dispatch command)
          └─ Result Callback
                ↓
      [LLM-APP]
          └─ McpResultReceiver → McpResultBus → UI
```

## Comparison

| Approach         | Third-party Tools | Protocol-based | Security Control |
| ---------------- | ----------------- | -------------- | ---------------- |
| Qwen / Apple Intelligence | ✗        | ✗              | ✓                |
| AppAgent / AppAgentX      | ✓        | ✗              | ✗                |
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

- Xiheng Li
- Mengting He
- Chengcheng Wan
- Linhai Song

## Video Link

- [Project Demo Video](https://youtu.be/Bc2LG3sR1NY)
