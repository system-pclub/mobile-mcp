package com.example.llm_app

import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Handler

import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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

import com.example.commandgateway.ICommandGateway

class MainActivity : ComponentActivity() {

    // MCP Capabilities cache: packageName -> XML string
    private val mcpCapabilities = mutableMapOf<String, String>()

    // Service binding
    private var commandGateway: ICommandGateway? = null
    private var boundPackage: String? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            commandGateway = ICommandGateway.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            commandGateway = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1️⃣ Load MCP capabilities at startup
        retrieveMcpCapabilities()

        // 2️⃣ Set Compose UI
        setContent {
            VoiceToCommandScreen()
        }
    }

    private fun retrieveMcpCapabilities() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (app in packages) {
            val meta = app.metaData ?: continue
            if (meta.containsKey("mcp.capabilities")) {
                try {
                    val resId = meta.getInt("mcp.capabilities")
                    val xml = xmlToString(resId)
                    mcpCapabilities[app.packageName] = xml
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun xmlToString(resId: Int): String {
        return resources.openRawResource(resId).bufferedReader().use { it.readText() }
    }

    private fun callOpenAIAndExecute(recognizedText: String, onStatusUpdate: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                onStatusUpdate("Calling OpenAI...")

                val apiKey = "YOUR_OPENAI_API_KEY"

                val mcpArray = JSONArray()
                for ((pkg, xml) in mcpCapabilities) {
                    val obj = JSONObject()
                    obj.put("package", pkg)
                    obj.put("capabilities", xml)
                    mcpArray.put(obj)
                }

                val prompt = JSONObject().apply {
                    put(
                        "messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "system")
                                put(
                                    "content",
                                    "You are an MCP command planner. Given user intent and MCP capabilities, output a JSON command {package, service, commandJson}."
                                )
                            })
                            put(JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    """
                                    User says: "$recognizedText"
                                    MCP capabilities: $mcpArray
                                    """.trimIndent()
                                )
                            })
                        }
                    )
                }

                val url = URL("https://api.openai.com/v1/chat/completions")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true

                val body = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put("messages", prompt.getJSONArray("messages"))
                }

                conn.outputStream.use {
                    it.write(body.toString().toByteArray())
                }

                val response = conn.inputStream.bufferedReader().readText()

                // Parse JSON command from OpenAI
                val command = parseCommandFromResponse(response)

                onStatusUpdate("Executing command...")

                executeCommand(command)

                onStatusUpdate("Command executed!")

            } catch (e: Exception) {
                e.printStackTrace()
                onStatusUpdate("Error: ${e.message}")
            }
        }
    }

    private fun parseCommandFromResponse(response: String): JSONObject {
        val root = JSONObject(response)
        val content = root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        val jsonStart = content.indexOf('{')
        val jsonEnd = content.lastIndexOf('}') + 1
        val json = content.substring(jsonStart, jsonEnd)

        return JSONObject(json)
    }

    private fun executeCommand(command: JSONObject) {
        val pkg = command.getString("package")
        val serviceClass = command.getString("service")
        val commandJson = command.getString("commandJson")

        boundPackage = pkg
        val intent = Intent().apply {
            component = ComponentName(pkg, serviceClass)
        }

        // Optional: check service exists
        val resolved = packageManager.resolveService(intent, 0) ?: return

        bindService(intent, serviceConnection, BIND_AUTO_CREATE)

        // Small delay to ensure service binding
        Handler(mainLooper).postDelayed({
            try {
                commandGateway?.invoke(commandJson)
            } catch (_: Exception) {
            }
        }, 500)
    }

    /* ===================== Compose UI ===================== */
    @Composable
    fun VoiceToCommandScreen() {
        var recognizedText by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Idle") }
        val scope = rememberCoroutineScope()

        val speechLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val results =
                    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                recognizedText = results?.firstOrNull() ?: ""
                // Call OpenAI when speech is recognized
                scope.launch {
                    callOpenAIAndExecute(recognizedText) { s ->
                        status = s
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Button(onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                }
                speechLauncher.launch(intent)
            }) {
                Text("Speak")
            }

            Spacer(Modifier.height(16.dp))

//            Text("Recognized text:")
            Text(recognizedText, style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(16.dp))
//            Text("Status: $status", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
