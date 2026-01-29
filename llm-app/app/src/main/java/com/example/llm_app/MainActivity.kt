package com.example.llm_app

import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.os.IBinder
import android.os.Handler

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

import com.example.mcpdemo.ICommandGateway
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

    val mcpAppMap: MutableMap<String, McpAppInfo> = mutableMapOf()

    // Service binding
    private var commandGateway: ICommandGateway? = null
    private var boundPackage: String? = null

    private var pendingCommandJson: String? = null
    private var pendingResultCallback: ((String) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            commandGateway = ICommandGateway.Stub.asInterface(service)
            isBound = true
            Log.d("executeCommand", "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            commandGateway = null
        }
    }

    lateinit var gatewayIntent: Intent
    var isBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gatewayIntent = Intent().apply {
            component = ComponentName(
                "com.example.mcpdemo",
                "com.example.mcpdemo.CommandGatewayService"
            )
        }

        bindService(gatewayIntent, serviceConnection, BIND_AUTO_CREATE)

        // 1️⃣ Load MCP capabilities at startup
        retrieveMcpCapabilities()

        // 2️⃣ Set Compose UI
        setContent {
            VoiceToCommandScreen()
        }
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
        val content =
            root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

        return content.trim()
    }

    private fun executeCommand(command: JSONObject, onResult: (String) -> Unit) {
        val tStart = System.currentTimeMillis()
        
        // [T7] Service Bind Wait
        if (!isBound || commandGateway == null) {
            Log.w("executeCommand", "Service disconnected, attempting rebind...")
            try {
                bindService(gatewayIntent, serviceConnection, BIND_AUTO_CREATE)
            } catch (e: Exception) {
                Log.e("executeCommand", "Rebind failed", e)
            }

            // Simple blocking wait since we are on a background thread (Dispatchers.IO)
            for (i in 0 until 5) {
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
                if (isBound && commandGateway != null) {
                    Log.d("executeCommand", "Service reconnected successfully")
                    break
                }
            }
        }
        val tAfterBind = System.currentTimeMillis()
        Log.d("LatencyTest", "[T7] Service Bind Wait: ${tAfterBind - tStart}ms")

        if (!isBound || commandGateway == null) {
            onResult("Service not connected (rebind failed)")
            return
        }

        try {
            // [T8] IPC Round Trip
            val tIPCStart = System.currentTimeMillis()
            val resultJson = commandGateway!!.invoke(command.getJSONObject("commandJson").toString())
            val tIPCEnd = System.currentTimeMillis()
            Log.d("LatencyTest", "[T8] IPC Invoke Round Trip: ${tIPCEnd - tIPCStart}ms")
            
            val msg = JSONObject(resultJson).optString("message", resultJson)
            onResult(msg)
        } catch (e: Exception) {
            onResult("Error: ${e.message}")
        }
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
