package com.example.llm_app

import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle

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

import android.os.*

private const val MSG_INVOKE = 1
private const val MSG_RESULT = 2

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

    val mcpAppMap: MutableMap<String, McpAppInfo> = mutableMapOf()

    private val mainHandler = Handler(Looper.getMainLooper())

    // requestId -> callback
    private val pendingCallbacks = mutableMapOf<String, (String) -> Unit>()

    // This Messenger receives replies from tool apps
    private val replyMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_RESULT) return

            val data = msg.data ?: return
            val requestId = data.getString("mcp_request_id") ?: return
            val resultJson = data.getString("result_json").orEmpty()

            val cb = pendingCallbacks.remove(requestId)
            cb?.invoke(resultJson)
        }
    })

    private data class ToolConn(
        val pkg: String,
        val serviceClass: String,
        var messenger: Messenger? = null,
        var isBound: Boolean = false,
        val conn: ServiceConnection
    )

    private val toolMap = mutableMapOf<String, ToolConn>() // key = "$pkg|$service"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1️⃣ Load MCP capabilities at startup
        retrieveMcpCapabilities()

        // 2️⃣ Set Compose UI
        setContent {
            VoiceToCommandScreen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 🔹 Unbind all MCP tool services
        toolMap.values.forEach { tool ->
            if (tool.isBound) {
                try {
                    unbindService(tool.conn)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        toolMap.clear()
        pendingCallbacks.clear()
    }

    private fun retrieveMcpCapabilities() {
        val pm = packageManager
        val intent = Intent("com.example.mcpdemo.COMMAND_GATEWAY")

        val services = pm.queryIntentServices(
            intent,
            PackageManager.GET_META_DATA
        )

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
                        sb.append(" ${parser.getAttributeName(i)}=\"${parser.getAttributeValue(i)}\"" )
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

    private fun callOpenAIAndExecute(
        recognizedText: String,
        onStatusUpdate: (String) -> Unit
    ) {
        val overallStartTime = System.currentTimeMillis()
        Log.d("LatencyTest", "=== Start processing: $overallStartTime ===")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apiKey = assets.open("openai_key.txt")
                    .bufferedReader()
                    .use { it.readText().trim() }

                /* ================= 1️⃣ APP SELECTION ================= */
//                onStatusUpdate("Selecting target app...")
                val step1Start = System.currentTimeMillis()
                
                // [T1] Prompt Prep
                val t1Start = System.currentTimeMillis()
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
                            put(JSONObject().apply {
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
                            })
                            put(JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    """
                                User intent: \"$recognizedText\"
                                Available apps:
                                $appList
                                """.trimIndent()
                                )
                            })
                        }
                    )
                }
                val t1End = System.currentTimeMillis()
                Log.d("LatencyTest", "[T1] App Select Prompt Prep: ${t1End - t1Start}ms")

                // [T2] LLM Network
                val t2Start = System.currentTimeMillis()
                val responseStr = callOpenAI(apiKey, appSelectPrompt)
                val t2End = System.currentTimeMillis()
                Log.d("LatencyTest", "[T2] App Select LLM Network: ${t2End - t2Start}ms")

                // [T3] Parse & Lookup
                val t3Start = System.currentTimeMillis()
                val selectedPackage = JSONObject(responseStr).getString("package")
                val selectedApp = mcpAppMap[selectedPackage]
                    ?: throw IllegalStateException("Package not found: $selectedPackage")
                val t3End = System.currentTimeMillis()
                Log.d("LatencyTest", "[T3] App Select Parse & Lookup: ${t3End - t3Start}ms")
                
                Log.d("openAI", "Selected package: $selectedPackage, app name: ${selectedApp.appName}")
                onStatusUpdate("Select target app: ${selectedApp.appName} (${selectedApp.appDescription})")


                /* ================= 2️⃣ SERVICE + CAPABILITY ================= */

//                onStatusUpdate("Selecting service and capability...")
                val step2Start = System.currentTimeMillis()

                // [T4] Capability Prompt Prep
                val t4Start = System.currentTimeMillis()
                val serviceList = JSONArray()
                for (cap in selectedApp.capabilities) {
                    val obj = JSONObject()
                    obj.put("service", cap.serviceName)
                    obj.put("capabilitiesXml", cap.capabilitiesXml)
                    serviceList.put(obj)
                }

                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date())

                val capabilityPrompt = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put(
                        "messages", JSONArray().apply {
                            put(JSONObject().apply {
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
                            })
                            put(JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    """
                                Today is: $today
                                User intent: \"$recognizedText\"
                                Services:
                                $serviceList
                                """.trimIndent()
                                )
                            })
                        }
                    )
                }
                val t4End = System.currentTimeMillis()
                Log.d("LatencyTest", "[T4] Capability Prompt Prep: ${t4End - t4Start}ms")

                // [T5] LLM Network (Capability)
                val t5Start = System.currentTimeMillis()
                val commandJsonStr = callOpenAI(apiKey, capabilityPrompt)
                val t5End = System.currentTimeMillis()
                Log.d("LatencyTest", "[T5] Capability LLM Network: ${t5End - t5Start}ms")
                
                // [T6] Parse & Reconstruct
                val t6Start = System.currentTimeMillis()
                val commandObj = JSONObject(commandJsonStr)
                val argsObj = commandObj.getJSONObject("args")

                val commandJson = JSONObject().apply {
                    // still keep capability at top level
                    put("capability", commandObj.getString("capability"))

                    // copy all fields from args directly into commandJson
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
                val t6End = System.currentTimeMillis()
                Log.d("LatencyTest", "[T6] Capability Parse & Reconstruct: ${t6End - t6Start}ms")

                Log.d("openAI", "Final command: $finalCommand")
                onStatusUpdate("Executing command: ${commandObj.getString("capability")}")
                
                
                /* ================= 3️⃣ EXECUTION ================= */
                // T7 & T8 handled inside executeCommand
                
                executeCommand(finalCommand) { result ->
                    val endTime = System.currentTimeMillis()
                    Log.d("LatencyTest", "=== Total Duration: ${endTime - overallStartTime}ms ===")
                    
                    runOnUiThread {
                        Log.d("openAI", "execution result: $result")
                        onStatusUpdate("result: $result")
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                onStatusUpdate("Error: ${e.message}")
            }
        }
    }

    private fun callOpenAI(apiKey: String, payload: JSONObject): String {
        val startNet = System.currentTimeMillis()
        val url = URL("https://api.openai.com/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true

        conn.outputStream.use {
            it.write(payload.toString().toByteArray())
        }
        Log.d("openAI", "Prompt: $payload")

        val response = conn.inputStream.bufferedReader().readText()
        val endNet = System.currentTimeMillis()
        // Note: This log is inside the method to catch pure network time including read
        // The calling method also measures it as T2/T5, which includes function call overhead (negligible)
        
        Log.d("openAI", "Raw response: $response")

        val root = JSONObject(response)
        var content =
            root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

        // ---------- Strip Markdown code fences ----------
        if (content.startsWith("```")) {

            // Remove opening ```json or ```
            content = content
                .removePrefix("```json")
                .removePrefix("```")
                .trim()

            // Remove closing ```
            if (content.endsWith("```")) {
                content = content.removeSuffix("```").trim()
            }
        }

        // ---------- Extract first JSON block (safety) ----------
        val firstBrace = content.indexOf("{")
        val lastBrace = content.lastIndexOf("}")

        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            content = content.substring(firstBrace, lastBrace + 1)
        }

        return content
    }

    private fun executeCommand(command: JSONObject, onResult: (String) -> Unit) {
        val pkg = command.getString("package")
        val serviceClass = command.getString("service")
        val commandJsonStr = command.getJSONObject("commandJson").toString()

        val key = "$pkg|$serviceClass"

        val tool = toolMap.getOrPut(key) {
            val sc = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val entry = toolMap[key] ?: return
                    entry.messenger = Messenger(service)
                    entry.isBound = true
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    val entry = toolMap[key] ?: return
                    entry.messenger = null
                    entry.isBound = false
                }
            }
            ToolConn(pkg, serviceClass, conn = sc)
        }

        fun sendNow() {
            val requestId = UUID.randomUUID().toString()
            pendingCallbacks[requestId] = { resultJson ->
                // Extract "message" for UI
                val msgText = try {
                    Log.d("executeCommand", "resultJson: $resultJson")
                    JSONObject(resultJson).optString("message", resultJson)
                } catch (_: Exception) {
                    resultJson
                }
                onResult(msgText)
            }

            val m = Message.obtain(null, MSG_INVOKE)
            m.replyTo = replyMessenger
            m.data = Bundle().apply {
                putString("mcp_request_id", requestId)
                putString("mcp_command_json", commandJsonStr)
            }

            try {
                tool.messenger?.send(m)
            } catch (e: Exception) {
                pendingCallbacks.remove(requestId)
                onResult("Error sending to tool: ${e.message}")
            }
        }

        if (tool.isBound && tool.messenger != null) {
            sendNow()
            return
        }

        // Bind first, then send
        val intent = Intent().apply {
            component = ComponentName(pkg, serviceClass)
        }

        val resolved = packageManager.resolveService(intent, 0)
        if (resolved == null) {
            onResult("Service not found: $pkg / $serviceClass")
            return
        }

        val ok = bindService(intent, tool.conn, BIND_AUTO_CREATE)
        if (!ok) {
            onResult("bindService failed: $pkg / $serviceClass")
            return
        }

        // Wait until bound, then send (poll a few times quickly)
        var tries = 0
        val r = object : Runnable {
            override fun run() {
                if (tool.isBound && tool.messenger != null) {
                    sendNow()
                    return
                }
                tries++
                if (tries >= 20) { // ~2s max
                    onResult("Timeout binding to tool service")
                    return
                }
                mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(r)
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

            // Status
//            Text(
//                text = status,
//                style = MaterialTheme.typography.bodySmall,
//                color = Color.Gray
//            )

            // Input row (like ChatGPT)
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
            callOpenAIAndExecute(text) { status ->
                onStatusUpdate(status)
                messages.add(ChatMessage(status, isUser = false))
            }
        }
    }
}
