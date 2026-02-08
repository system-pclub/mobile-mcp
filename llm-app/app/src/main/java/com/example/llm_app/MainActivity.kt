package com.example.llm_app

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.SystemClock

import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat

class MainActivity : ComponentActivity() {

    data class ChatMessage(
        val text: String,
        val isUser: Boolean
    )

    data class McpCapability(
        val serviceName: String,
        val capabilitiesXml: String
    )

    data class McpAppInfo(
        val packageName: String,
        val appName: String,
        val appDescription: String,
        val capabilities: MutableList<McpCapability> = mutableListOf()
    )

    data class TraceContext(
        val requestId: String,
        val capability: String,
        val runIndex: Int,
        val params: JSONObject? = null
    )

    data class OpenAiCallResult(
        val content: String,
        val promptChars: Int,
        val promptBytes: Int,
        val responseChars: Int,
        val responseBytes: Int,
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    )

    data class BenchmarkCase(
        val capability: String,
        val runIndex: Int,
        val recognizedText: String,
        val params: JSONObject?
    )

    val mcpAppMap: MutableMap<String, McpAppInfo> = mutableMapOf()
    private var benchmarkJob: Job? = null
    private var benchmarkPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1️⃣ Load MCP capabilities at startup
        retrieveMcpCapabilities()

        // 2️⃣ Set Compose UI
        setContent {
            VoiceToCommandScreen()
        }

        benchmarkPending = intent?.getBooleanExtra("trace_benchmark", false) == true
    }

    override fun onResume() {
        super.onResume()
        if (benchmarkPending) {
            benchmarkPending = false
            startBenchmarkSuite()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("trace_benchmark", false)) {
            benchmarkPending = true
        }
    }

    private fun retrieveMcpCapabilities() {
        val discoveryRequestId = "discovery_${System.currentTimeMillis()}"
        val d1StartNs = SystemClock.elapsedRealtimeNanos()
        var discoveredServiceCount = 0
        var discoveredAppCount = 0

        val pm = packageManager
        val intent = Intent("com.example.mcpdemo.COMMAND_GATEWAY")

        try {
            val services = pm.queryIntentServices(
                intent,
                PackageManager.GET_META_DATA
            )
            discoveredServiceCount = services.size

            Log.d("mcpSearcher", "Found ${services.size} MCP services")

            for (resolveInfo in services) {
                val serviceInfo = resolveInfo.serviceInfo
                val appInfo = serviceInfo.applicationInfo

                val meta = serviceInfo.metaData ?: continue
                if (!meta.containsKey("mcp.capabilities")) continue

                try {
                    val resId = meta.getInt("mcp.capabilities")

                    // Remote resources
                    val remoteRes = pm.getResourcesForApplication(appInfo)
                    val xml = xmlToString(remoteRes, resId)

                    val pkg = appInfo.packageName
                    val serviceName = serviceInfo.name   // fully-qualified service class

                    // App display name
                    val appName = pm.getApplicationLabel(appInfo).toString()

                    // App description (optional, from manifest or fallback)
                    val appDescription = try {
                        if (appInfo.descriptionRes != 0)
                            remoteRes.getString(appInfo.descriptionRes)
                        else
                            "No description"
                    } catch (e: Exception) {
                        "No description"
                    }

                    val capability = McpCapability(
                        serviceName = serviceName,
                        capabilitiesXml = xml
                    )

                    val appEntry = mcpAppMap.getOrPut(pkg) {
                        McpAppInfo(
                            packageName = pkg,
                            appName = appName,
                            appDescription = appDescription
                        )
                    }

                    appEntry.capabilities.add(capability)

                    Log.d(
                        "mcpSearcher",
                        """
                MCP App:
                Package : ${appEntry.packageName}
                Name    : ${appEntry.appName}
                Desc    : ${appEntry.appDescription}

                Added Capability:
                Service : $serviceName
                XML:
                $xml
                """.trimIndent()
                    )

                } catch (e: Exception) {
                    Log.e("mcpSearcher", "Failed loading MCP for ${serviceInfo.packageName}", e)
                }
            }

            // Final summary
            Log.d("mcpSearcher", "Total MCP apps found: ${mcpAppMap.size}")
            discoveredAppCount = mcpAppMap.size
        } finally {
            val d1EndNs = SystemClock.elapsedRealtimeNanos()
            LatencyTraceLogger.logSpan(
                context = this,
                requestId = discoveryRequestId,
                capability = "discovery",
                runIndex = 0,
                step = "D1_DISCOVERY_LOAD_MCP_SERVICES",
                startNs = d1StartNs,
                endNs = d1EndNs,
                extra = JSONObject()
                    .put("discovered_service_count", discoveredServiceCount)
                    .put("discovered_app_count", discoveredAppCount)
            )
        }
    }

    fun xmlToString(res: Resources, resId: Int): String {
        val parser = res.getXml(resId)
        val sb = StringBuilder()
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    sb.append("<${parser.name}")

                    // Read attributes
                    for (i in 0 until parser.attributeCount) {
                        sb.append(" ${parser.getAttributeName(i)}=\"${parser.getAttributeValue(i)}\"")
                    }

                    sb.append(">")
                }

                XmlPullParser.TEXT -> {
                    sb.append(parser.text)
                }

                XmlPullParser.END_TAG -> {
                    sb.append("</${parser.name}>")
                }
            }
            eventType = parser.next()
        }

        return sb.toString()
    }

    private fun startBenchmarkSuite() {
        if (benchmarkJob?.isActive == true) {
            return
        }
        benchmarkJob = CoroutineScope(Dispatchers.IO).launch {
            runBenchmarkSuite()
        }
    }

    private suspend fun runBenchmarkSuite() {
        LatencyTraceLogger.clear(this@MainActivity)
        LatencyTraceLogger.logMeta(
            this@MainActivity,
            JSONObject()
                .put("meta", true)
                .put("device_model", Build.MODEL)
                .put("android_version", Build.VERSION.RELEASE)
                .put("llm_app_version", packageManager.getPackageInfo(packageName, 0).versionName)
                .put("cold_start", false)
        )

        val cases = buildBenchmarkCases()
        for (case in cases) {
            val finished = CompletableDeferred<String>()
            val requestId = UUID.randomUUID().toString()
            val traceContext = TraceContext(
                requestId = requestId,
                capability = case.capability,
                runIndex = case.runIndex,
                params = case.params
            )
            callOpenAIAndExecute(
                recognizedText = case.recognizedText,
                onStatusUpdate = { status ->
                    Log.d(
                        "LatencyTrace",
                        "benchmark_status capability=${case.capability} run=${case.runIndex} status=$status"
                    )
                },
                traceContext = traceContext,
                onFinished = { result ->
                    if (!finished.isCompleted) {
                        finished.complete(result)
                    }
                }
            )
            try {
                withTimeout(180_000) {
                    finished.await()
                }
            } catch (e: Exception) {
                if (!finished.isCompleted) {
                    finished.complete("timeout:${e.message}")
                }
            }
            delay(1_200)
        }

        Log.d("LatencyTrace", """{"record_type":"benchmark_done","total_runs":${cases.size}}""")
    }

    private fun buildBenchmarkCases(): List<BenchmarkCase> {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

        val cases = mutableListOf<BenchmarkCase>()
        for (i in 1..5) {
            cases.add(
                BenchmarkCase(
                    capability = "clock_in_today",
                    runIndex = i,
                    recognizedText = "Please clock in today now. Use capability clock_in_today.",
                    params = null
                )
            )
            cases.add(
                BenchmarkCase(
                    capability = "query_clock_in",
                    runIndex = i,
                    recognizedText = "Query whether $todayStr has been clocked in. Use capability query_clock_in.",
                    params = JSONObject().put("date", todayStr)
                )
            )
            cases.add(
                BenchmarkCase(
                    capability = "make_up_clock_in",
                    runIndex = i,
                    recognizedText = "Make up clock-in for date $yesterdayStr. Use capability make_up_clock_in.",
                    params = JSONObject().put("date", yesterdayStr)
                )
            )
        }
        return cases
    }

    private fun callOpenAIAndExecute(
        recognizedText: String,
        onStatusUpdate: (String) -> Unit,
        traceContext: TraceContext? = null,
        onFinished: ((String) -> Unit)? = null
    ) {
        val requestId = traceContext?.requestId ?: UUID.randomUUID().toString()
        var capability = traceContext?.capability ?: "unknown"
        val runIndex = traceContext?.runIndex ?: -1
        val params = traceContext?.params
        val overallStartNs = SystemClock.elapsedRealtimeNanos()

        val t0Extra = JSONObject().put("recognized_text", recognizedText)
        if (params != null) {
            t0Extra.put("params", params)
        }
        LatencyTraceLogger.logMark(
            context = this,
            requestId = requestId,
            capability = capability,
            runIndex = runIndex,
            step = "T0",
            timestampNs = overallStartNs,
            extra = t0Extra
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apiKey =
                    assets.open("openai_key.txt").bufferedReader().use { it.readText().trim() }

                val t1Start = SystemClock.elapsedRealtimeNanos()
                val appList = JSONArray()
                for ((_, app) in mcpAppMap) {
                    val obj = JSONObject()
                    obj.put("package", app.packageName)
                    obj.put("name", app.appName)
                    obj.put("description", app.appDescription)
                    appList.put(obj)
                }
                val appSelectPrompt = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put(
                        "messages", JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put(
                                        "content",
                                        """
                                You are an MCP app selector.
                                Given user intent and a list of apps,
                                return only JSON:
                                { \"package\": \"xxx\" }
                                """.trimIndent()
                                    )
                                }
                            )
                            put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put(
                                        "content",
                                        """
                                User intent: "$recognizedText"
                                Available apps:
                                $appList
                                """.trimIndent()
                                    )
                                }
                            )
                        }
                    )
                }
                val t1End = SystemClock.elapsedRealtimeNanos()
                LatencyTraceLogger.logSpan(
                    this@MainActivity,
                    requestId,
                    capability,
                    runIndex,
                    "T1",
                    t1Start,
                    t1End
                )

                val t2Start = SystemClock.elapsedRealtimeNanos()
                val appSelectResult = callOpenAI(apiKey, appSelectPrompt)
                val t2End = SystemClock.elapsedRealtimeNanos()
                LatencyTraceLogger.logSpan(
                    this@MainActivity,
                    requestId,
                    capability,
                    runIndex,
                    "T2",
                    t2Start,
                    t2End,
                    extra = JSONObject()
                        .put("prompt_chars", appSelectResult.promptChars)
                        .put("prompt_bytes", appSelectResult.promptBytes)
                        .put("response_chars", appSelectResult.responseChars)
                        .put("response_bytes", appSelectResult.responseBytes)
                        .put("prompt_tokens", appSelectResult.promptTokens)
                        .put("completion_tokens", appSelectResult.completionTokens)
                        .put("total_tokens", appSelectResult.totalTokens)
                )

                val t3Start = SystemClock.elapsedRealtimeNanos()
                val selectedPackage = JSONObject(appSelectResult.content).getString("package")
                val selectedApp = mcpAppMap[selectedPackage]
                    ?: throw IllegalStateException("Package not found: $selectedPackage")
                val t3End = SystemClock.elapsedRealtimeNanos()
                LatencyTraceLogger.logSpan(
                    this@MainActivity,
                    requestId,
                    capability,
                    runIndex,
                    "T3",
                    t3Start,
                    t3End
                )

                onStatusUpdate("Select target app: ${selectedApp.appName} (${selectedApp.appDescription})")

                val t4Start = SystemClock.elapsedRealtimeNanos()
                val serviceList = JSONArray()
                for (cap in selectedApp.capabilities) {
                    val obj = JSONObject()
                    obj.put("service", cap.serviceName)
                    obj.put("capabilitiesXml", cap.capabilitiesXml)
                    serviceList.put(obj)
                }
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val capabilityPrompt = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put(
                        "messages", JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("role", "system")
                                    put(
                                        "content",
                                        """
                                You are an MCP command planner.
                                Given user intent and service capabilities,
                                output only JSON:
                                {
                                  "service": "xxx",
                                  "capability": "xxx",
                                  "args": { }
                                }
                                When the user refers to "today", use the provided current date.
                                """.trimIndent()
                                    )
                                }
                            )
                            put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put(
                                        "content",
                                        """
                                Today is: $today
                                User intent: "$recognizedText"
                                Services:
                                $serviceList
                                """.trimIndent()
                                    )
                                }
                            )
                        }
                    )
                }
                val t4End = SystemClock.elapsedRealtimeNanos()
                LatencyTraceLogger.logSpan(
                    this@MainActivity,
                    requestId,
                    capability,
                    runIndex,
                    "T4",
                    t4Start,
                    t4End
                )

                val t5Start = SystemClock.elapsedRealtimeNanos()
                val commandResult = callOpenAI(apiKey, capabilityPrompt)
                val t5End = SystemClock.elapsedRealtimeNanos()
                LatencyTraceLogger.logSpan(
                    this@MainActivity,
                    requestId,
                    capability,
                    runIndex,
                    "T5",
                    t5Start,
                    t5End,
                    extra = JSONObject()
                        .put("prompt_chars", commandResult.promptChars)
                        .put("prompt_bytes", commandResult.promptBytes)
                        .put("response_chars", commandResult.responseChars)
                        .put("response_bytes", commandResult.responseBytes)
                        .put("prompt_tokens", commandResult.promptTokens)
                        .put("completion_tokens", commandResult.completionTokens)
                        .put("total_tokens", commandResult.totalTokens)
                )

                val t6Start = SystemClock.elapsedRealtimeNanos()
                val commandObj = JSONObject(commandResult.content)
                val argsObj = commandObj.getJSONObject("args")
                capability = commandObj.getString("capability")

                val commandJson = JSONObject().apply {
                    put("capability", capability)
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, argsObj.get(key))
                    }
                }
                val finalCommand = JSONObject().apply {
                    put("package", selectedPackage)
                    put("service", commandObj.getString("service"))
                    put("commandJson", commandJson)
                }
                val t6End = SystemClock.elapsedRealtimeNanos()
                LatencyTraceLogger.logSpan(
                    this@MainActivity,
                    requestId,
                    capability,
                    runIndex,
                    "T6",
                    t6Start,
                    t6End
                )

                onStatusUpdate("Executing command: $capability")
                executeCommand(
                    command = finalCommand,
                    requestId = requestId,
                    capability = capability,
                    runIndex = runIndex
                ) { result, payload, t7bNs ->
                    if (payload != null) {
                        if (payload.serviceS0Ns > 0) {
                            LatencyTraceLogger.logMark(
                                this@MainActivity,
                                requestId,
                                capability,
                                runIndex,
                                "S0",
                                payload.serviceS0Ns
                            )
                        }
                        if (payload.serviceS1StartNs > 0 && payload.serviceS1EndNs > 0) {
                            LatencyTraceLogger.logSpan(
                                this@MainActivity,
                                requestId,
                                capability,
                                runIndex,
                                "S1",
                                payload.serviceS1StartNs,
                                payload.serviceS1EndNs
                            )
                        }
                        if (payload.serviceS2StartNs > 0 && payload.serviceS2EndNs > 0) {
                            LatencyTraceLogger.logSpan(
                                this@MainActivity,
                                requestId,
                                capability,
                                runIndex,
                                "S2",
                                payload.serviceS2StartNs,
                                payload.serviceS2EndNs
                            )
                        }
                        if (payload.serviceS3aNs > 0 && payload.serviceS3bNs > 0) {
                            LatencyTraceLogger.logSpan(
                                this@MainActivity,
                                requestId,
                                capability,
                                runIndex,
                                "S3",
                                payload.serviceS3aNs,
                                payload.serviceS3bNs
                            )
                        }
                        if (t7bNs > 0 && payload.serviceS0Ns > 0) {
                            LatencyTraceLogger.logSpan(
                                this@MainActivity,
                                requestId,
                                capability,
                                runIndex,
                                "T7",
                                t7bNs,
                                payload.serviceS0Ns
                            )
                        }
                        if (payload.serviceS3bNs > 0 && payload.receiveNs > 0) {
                            LatencyTraceLogger.logSpan(
                                this@MainActivity,
                                requestId,
                                capability,
                                runIndex,
                                "T8",
                                payload.serviceS3bNs,
                                payload.receiveNs
                            )
                        }
                    }

                    val t9Ns = SystemClock.elapsedRealtimeNanos()
                    LatencyTraceLogger.logSpan(
                        this@MainActivity,
                        requestId,
                        capability,
                        runIndex,
                        "T9",
                        overallStartNs,
                        t9Ns
                    )
                    runOnUiThread {
                        onStatusUpdate("result: $result")
                    }
                    onFinished?.invoke(result)
                }
            } catch (e: Exception) {
                val endNs = SystemClock.elapsedRealtimeNanos()
                LatencyTraceLogger.logSpan(
                    this@MainActivity,
                    requestId,
                    capability,
                    runIndex,
                    "ERROR",
                    overallStartNs,
                    endNs,
                    success = false,
                    error = e.message
                )
                onStatusUpdate("Error: ${e.message}")
                onFinished?.invoke("Error: ${e.message}")
            }
        }
    }

    private fun callOpenAI(apiKey: String, payload: JSONObject): OpenAiCallResult {
        val promptRaw = payload.toString()
        val promptChars = promptRaw.length
        val promptBytes = promptRaw.toByteArray(Charsets.UTF_8).size

        val conn =
            (URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
            }

        conn.outputStream.use {
            it.write(promptRaw.toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val responseRaw = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText().orEmpty()
        }
        if (code !in 200..299) {
            throw IllegalStateException("OpenAI HTTP $code: $responseRaw")
        }

        val responseChars = responseRaw.length
        val responseBytes = responseRaw.toByteArray(Charsets.UTF_8).size
        val root = JSONObject(responseRaw)

        val usage = root.optJSONObject("usage")
        val promptTokens = usage?.optInt("prompt_tokens", -1) ?: -1
        val completionTokens = usage?.optInt("completion_tokens", -1) ?: -1
        val totalTokens = usage?.optInt("total_tokens", -1) ?: -1

        var content = root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        if (content.startsWith("```")) {
            content = content.removePrefix("```json").removePrefix("```").trim()
            if (content.endsWith("```")) {
                content = content.removeSuffix("```").trim()
            }
        }

        val firstBrace = content.indexOf("{")
        val lastBrace = content.lastIndexOf("}")
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            content = content.substring(firstBrace, lastBrace + 1)
        }

        return OpenAiCallResult(
            content = content,
            promptChars = promptChars,
            promptBytes = promptBytes,
            responseChars = responseChars,
            responseBytes = responseBytes,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens
        )
    }

    private fun executeCommand(
        command: JSONObject,
        requestId: String,
        capability: String,
        runIndex: Int,
        onResult: (String, McpCallbackPayload?, Long) -> Unit
    ) {
        val pkg = command.getString("package")
        val serviceClass = command.getString("service")
        val commandJsonStr = command.getJSONObject("commandJson").toString()

        var t7bNs = -1L

        McpResultBus.register(requestId) { payload ->
            val msg = try {
                JSONObject(payload.resultJson).optString("message", payload.resultJson)
            } catch (_: Exception) {
                payload.resultJson
            }
            onResult(msg, payload, t7bNs)
        }

        val callbackIntent = Intent(this, McpResultReceiver::class.java).apply {
            putExtra("mcp_request_id", requestId)
        }

        val flags = when {
            Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else -> PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pending = PendingIntent.getBroadcast(
            this,
            requestId.hashCode(),
            callbackIntent,
            flags
        )

        val intent = Intent().apply {
            component = ComponentName(pkg, serviceClass)
            putExtra("mcp_command_json", commandJsonStr)
            putExtra("mcp_request_id", requestId)
            putExtra("mcp_callback", pending)
            putExtra("trace_capability", capability)
            putExtra("trace_run_index", runIndex)
        }

        val resolved = packageManager.resolveService(intent, 0)
        if (resolved == null) {
            onResult("Service not found: $pkg / $serviceClass", null, t7bNs)
            return
        }

        val t7aNs = SystemClock.elapsedRealtimeNanos()
        LatencyTraceLogger.logMark(this, requestId, capability, runIndex, "T7a", t7aNs)
        startService(intent)
        t7bNs = SystemClock.elapsedRealtimeNanos()
        LatencyTraceLogger.logMark(this, requestId, capability, runIndex, "T7b", t7bNs)
    }


    /* ===================== Compose UI ===================== */
    @Composable
    fun VoiceToCommandScreen() {
        var inputText by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Idle") }
        val messages = remember { mutableStateListOf<ChatMessage>() }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        // Run once when the composable first enters composition
        LaunchedEffect(Unit) {
            messages.add(ChatMessage("I'm a chat bot to help operate other apps.", isUser = false))
        }

        // Auto-scroll when new message added
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val results =
                    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val text = results?.firstOrNull() ?: return@rememberLauncherForActivityResult
                sendMessage(text, messages, scope) { status = it }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {

            // Chat history
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
//                reverseLayout = false
                state = listState
            ) {
                items(messages) {
                    ChatBubble(it)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f),
                    placeholder = { Text("Type a message…") },
                    keyboardOptions = KeyboardOptions.Default.copy(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) {
                                sendMessage(inputText, messages, scope) { status = it }
                                inputText = ""
                            }
                        }
                    ),
                    singleLine = true
                )

                Spacer(Modifier.width(8.dp))

                // Voice button
                IconButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    }
                    speechLauncher.launch(intent)
                }) {
                    Icon(Icons.Default.Mic, contentDescription = "Speak")
                }

                // Send button
                IconButton(onClick = {
                    if (inputText.isNotBlank()) {
                        sendMessage(inputText, messages, scope) { status = it }
                        inputText = ""
                    }
                }) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }

    @Composable
    fun ChatBubble(msg: ChatMessage) {
        val bgColor = if (msg.isUser) Color(0xFFDCF8C6) else Color(0xFFEDEDED)
        val alignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart

        Box(
            modifier = Modifier
                .fillMaxWidth(),
            contentAlignment = alignment
        ) {
            Text(
                text = msg.text,
                modifier = Modifier
                    .padding(6.dp)
                    .background(bgColor, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    private fun sendMessage(
        text: String,
        messages: MutableList<ChatMessage>,
        scope: CoroutineScope,
        onStatusUpdate: (String) -> Unit
    ) {
        messages.add(ChatMessage(text, isUser = true))

        scope.launch {
            callOpenAIAndExecute(
                recognizedText = text,
                onStatusUpdate = { status ->
                    onStatusUpdate(status)
                    messages.add(ChatMessage(status, isUser = false))
                }
            )
        }
    }
}
