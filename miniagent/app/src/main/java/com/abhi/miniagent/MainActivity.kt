package com.abhi.miniagent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.abhi.miniagent.databinding.ActivityMainBinding
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var treeUri: Uri? = null
    private lateinit var providerStore: ProviderStore
    private lateinit var shellEnv: ShellEnvironment
    private lateinit var chatAdapter: ChatAdapter

    // Per-provider conversation history, kept in memory so a model's follow-up
    // question can actually be replied to with context (lost on activity kill -
    // that's a known limit, see README).
    private val conversations = mutableMapOf<String, JSONArray>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val pickFolderLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                treeUri = uri
                getSharedPreferences("miniagent", MODE_PRIVATE).edit()
                    .putString("tree_uri", uri.toString()).apply()
                b.tvFolder.text = uri.path ?: uri.toString()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        providerStore = ProviderStore(this)
        shellEnv = ShellEnvironment(this)

        chatAdapter = ChatAdapter()
        b.rvChat.layoutManager = LinearLayoutManager(this)
        b.rvChat.adapter = chatAdapter

        getSharedPreferences("miniagent", MODE_PRIVATE).getString("tree_uri", null)?.let {
            treeUri = Uri.parse(it)
            b.tvFolder.text = treeUri?.path ?: it
        }

        b.btnPickFolder.setOnClickListener { pickFolderLauncher.launch(null) }
        b.btnProviders.setOnClickListener { startActivity(Intent(this, ProvidersActivity::class.java)) }
        b.btnRun.setOnClickListener { runAgent() }
        b.btnSetupShell.setOnClickListener {
            addSystem("[shell] Setting up (this happens once, ~a few MB download)...")
            Thread { shellEnv.setup { msg -> addSystem(msg) } }.start()
        }
        // Attachments (images/files) aren't wired up yet - this is a placeholder so the
        // button is visible and honest about its state rather than silently doing nothing.
        b.btnAttach.setOnClickListener {
            Toast.makeText(this, "Attachments coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val activeCount = providerStore.activeOnes().size
        b.tvActiveCount.text = "$activeCount active model(s)"
    }

    // ---- Chat bubble helpers (replace the old single tvLog.append log) ----

    private fun scrollToBottom() {
        if (chatAdapter.itemCount > 0) b.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun addUser(text: String) {
        runOnUiThread {
            chatAdapter.addMessage(ChatMessage(text, ChatRole.USER))
            scrollToBottom()
        }
    }

    private fun addAssistant(label: String, text: String) {
        runOnUiThread {
            chatAdapter.addMessage(ChatMessage(text, ChatRole.ASSISTANT, label = label))
            scrollToBottom()
        }
    }

    /** Status/tool/error lines - visually dimmed + monospace so real replies stand out. */
    private fun addSystem(text: String) {
        runOnUiThread {
            chatAdapter.addMessage(ChatMessage(text, ChatRole.SYSTEM))
            scrollToBottom()
        }
    }

    private var typingPos: Int = -1
    private var typingHandler: android.os.Handler? = null

    private fun showTyping(label: String) {
        runOnUiThread {
            typingPos = chatAdapter.addMessage(ChatMessage("•", ChatRole.ASSISTANT, label = label))
            scrollToBottom()
            var dots = 1
            typingHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    if (typingPos in 0 until chatAdapter.itemCount) {
                        chatAdapter.setText(typingPos, "•".repeat(dots))
                        dots = if (dots >= 3) 1 else dots + 1
                        typingHandler?.postDelayed(this, 400)
                    }
                }
            }
            typingHandler?.post(runnable)
        }
    }

    private fun hideTyping() {
        runOnUiThread {
            typingHandler?.removeCallbacksAndMessages(null)
            typingHandler = null
            if (typingPos in 0 until chatAdapter.itemCount) chatAdapter.removeAt(typingPos)
            typingPos = -1
        }
    }



    private fun rootDoc(): DocumentFile? {
        val uri = treeUri ?: return null
        return DocumentFile.fromTreeUri(this, uri)
    }

    private fun listFilesRecursive(dir: DocumentFile, prefix: String, depth: Int, out: MutableList<String>) {
        if (depth > 4) return
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) listFilesRecursive(child, path, depth + 1, out) else out.add(path)
        }
    }

    private fun findChild(path: String, createDirs: Boolean = false): DocumentFile? {
        var current = rootDoc() ?: return null
        val parts = path.split("/").filter { it.isNotBlank() }
        for ((i, part) in parts.withIndex()) {
            val isLast = i == parts.size - 1
            val existing = current.findFile(part)
            current = if (existing != null) existing
            else if (isLast) current.createFile("text/plain", part) ?: return null
            else if (createDirs) current.createDirectory(part) ?: return null
            else return null
        }
        return current
    }

    private fun readFile(path: String): String {
        val doc = findChild(path) ?: return "ERROR: file not found: $path"
        return try {
            contentResolver.openInputStream(doc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: "ERROR: could not open $path"
        } catch (e: Exception) { "ERROR reading $path: ${e.message}" }
    }

    private fun writeFile(path: String, content: String): String {
        return try {
            val doc = findChild(path, createDirs = true) ?: return "ERROR: could not create $path"
            contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            if (path.endsWith(".html", ignoreCase = true)) {
                runOnUiThread { renderHtmlPreview(content) }
            }
            "OK: wrote ${content.length} chars to $path"
        } catch (e: Exception) { "ERROR writing $path: ${e.message}" }
    }

    private fun listFiles(): String {
        val root = rootDoc() ?: return "ERROR: no folder selected"
        val out = mutableListOf<String>()
        listFilesRecursive(root, "", 0, out)
        return out.joinToString("\n")
    }

    private fun openInBrowser(path: String): String {
        return try {
            val content = readFile(path)
            if (content.startsWith("ERROR")) return content
            val cacheFile = File(cacheDir, "preview_${System.currentTimeMillis()}.html")
            cacheFile.writeText(content)
            val uri = FileProvider.getUriForFile(this, "com.abhi.miniagent.fileprovider", cacheFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
            "OK: opened $path in browser chooser"
        } catch (e: Exception) { "ERROR opening $path: ${e.message}" }
    }

    // ---- HTML screenshot preview (WebView, off-screen) ----

    private fun renderHtmlPreview(html: String) {
        val wv = b.webViewHidden
        wv.settings.javaScriptEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.postDelayed({
                    val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    view.draw(canvas)
                    // Preview shows up as its own small system bubble, right after whatever
                    // triggered it - avoids needing to track which exact tool-result bubble
                    // to attach to, since write_file's render is fire-and-forget/async.
                    chatAdapter.addMessage(ChatMessage("", ChatRole.SYSTEM, label = "preview", bitmap = bmp))
                    scrollToBottom()
                }, 300)
            }
        }
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun confirmRisky(cmd: String): Boolean {
        val queue = ArrayBlockingQueue<Boolean>(1)
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Confirm shell command")
                .setMessage("This looks like it could be destructive:\n\n$cmd\n\nRun it inside the sandboxed Alpine environment?")
                .setCancelable(false)
                .setPositiveButton("Run") { _, _ -> queue.put(true) }
                .setNegativeButton("Skip") { _, _ -> queue.put(false) }
                .show()
        }
        return queue.take()
    }

    // ---- Tool spec (OpenAI function-calling shape - works for OpenRouter + NIM) ----

    private fun toolsSpec(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject, required: JSONArray): JSONObject =
            JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", desc)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        put("required", required)
                    })
                })
            }
        return JSONArray().apply {
            put(fn("list_files", "List all files in the project folder (relative paths).", JSONObject(), JSONArray()))
            put(fn("read_file", "Read the full text content of a file by relative path.",
                JSONObject().put("path", JSONObject().put("type", "string")), JSONArray().put("path")))
            put(fn("write_file", "Overwrite (or create) a file with the given full text content.",
                JSONObject().apply {
                    put("path", JSONObject().put("type", "string"))
                    put("content", JSONObject().put("type", "string"))
                }, JSONArray().put("path").put("content")))
            put(fn("open_in_browser", "Open a written HTML file in the phone's browser chooser so it can be visually checked. Only for .html files.",
                JSONObject().put("path", JSONObject().put("type", "string")), JSONArray().put("path")))
            put(fn("run_shell", "Run a shell command inside a sandboxed Alpine Linux environment (fake root via proot, isolated from the real device). Use for git, python, node, compilers, package installs, running/testing non-HTML code, etc. Requires one-time setup by the user first.",
                JSONObject().put("command", JSONObject().put("type", "string")), JSONArray().put("command")))
        }
    }

    private fun runTool(name: String, input: JSONObject): String = when (name) {
        "list_files" -> listFiles()
        "read_file" -> readFile(input.getString("path"))
        "write_file" -> writeFile(input.getString("path"), input.getString("content"))
        "open_in_browser" -> {
            runOnUiThread { openInBrowser(input.getString("path")) }
            "requested browser open for ${input.optString("path")}"
        }
        "run_shell" -> {
            val cmd = input.getString("command")
            val proceed = if (ShellEnvironment.isRisky(cmd)) confirmRisky(cmd) else true
            if (!proceed) "SKIPPED: user declined to run this command" else shellEnv.runCommand(cmd)
        }
        else -> "ERROR: unknown tool $name"
    }

    // ---- Agent loop, one run per active provider ----

    private fun runAgent() {
        val task = b.etTask.text.toString().trim()
        val providers = providerStore.activeOnes()
        val continueConv = b.cbContinue.isChecked

        if (treeUri == null) { addSystem("Pick a project folder first."); return }
        if (providers.isEmpty()) { addSystem("No active providers. Tap 'Models' and activate at least one."); return }
        if (task.isEmpty()) { addSystem("Enter a task."); return }

        if (!continueConv) { runOnUiThread { chatAdapter.clear() } }
        addUser(task)
        b.etTask.setText("")
        if (providers.size > 1) {
            addSystem("Running on ${providers.size} model(s): ${providers.joinToString { it.label }}")
        }

        Thread {
            for (provider in providers) {
                if (providers.size > 1) addSystem("========== ${provider.label} (${provider.model}) ==========")
                runOneProvider(provider, task, continueConv)
            }
        }.start()
    }

    private fun runOneProvider(provider: ProviderConfig, task: String, continueConv: Boolean) {
        try {
            val messages: JSONArray
            if (continueConv && conversations.containsKey(provider.id)) {
                messages = conversations[provider.id]!!
                messages.put(JSONObject().apply { put("role", "user"); put("content", task) })
            } else {
                messages = JSONArray()
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a coding agent working directly on the user's Android device. " +
                            "Use the tools to explore, read, and edit files in their project folder, and run_shell " +
                            "for anything needing a real environment (git, python, node, compilers, tests). " +
                            "For HTML output, writing the file auto-generates a screenshot preview; open_in_browser " +
                            "additionally opens it for real. If you genuinely need clarification from the user, " +
                            "ask a plain-text question instead of calling a tool - don't guess on ambiguous requirements. " +
                            "Otherwise proceed without asking. When the task is complete, summarize what changed.")
                })
                messages.put(JSONObject().apply { put("role", "user"); put("content", task) })
                conversations[provider.id] = messages
            }

            var iterations = 0
            while (iterations < 12) {
                iterations++
                val body = JSONObject().apply {
                    put("model", provider.model)
                    put("max_tokens", 4096)
                    put("tools", toolsSpec())
                    put("tool_choice", "auto")
                    put("messages", messages)
                }

                val req = Request.Builder()
                    .url(provider.baseUrl)
                    .addHeader("Authorization", "Bearer ${provider.apiKey}")
                    .addHeader("content-type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                showTyping(provider.label)
                try {
                    client.newCall(req).execute().use { resp ->
                        hideTyping()
                        val respStr = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        addSystem("[${provider.label}] API error ${resp.code}: ${respStr.take(500)}")
                        return
                    }
                    val respJson = JSONObject(respStr)
                    val choice = respJson.getJSONArray("choices").getJSONObject(0)
                    val message = choice.getJSONObject("message")
                    val finishReason = choice.optString("finish_reason", "")

                    messages.put(message)

                    if (message.has("content") && !message.isNull("content")) {
                        val text = message.optString("content", "")
                        if (text.isNotBlank()) addAssistant(provider.label, text)
                    }

                    val toolCalls = message.optJSONArray("tool_calls")
                    if (finishReason == "tool_calls" && toolCalls != null && toolCalls.length() > 0) {
                        for (i in 0 until toolCalls.length()) {
                            val call = toolCalls.getJSONObject(i)
                            val callId = call.getString("id")
                            val fn = call.getJSONObject("function")
                            val fnName = fn.getString("name")
                            val argsStr = fn.optString("arguments", "{}")
                            val args = try { JSONObject(argsStr) } catch (e: Exception) { JSONObject() }
                            addSystem("-> tool: $fnName($args)")
                            val result = runTool(fnName, args)
                            addSystem("<- result: ${result.take(300)}")
                            messages.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", callId)
                                put("content", result)
                            })
                        }
                    } else {
                        addSystem("[${provider.label}] done (or waiting on you - check 'Continue previous conversation' and reply above if it asked something).")
                        return
                    }
                    }
                } finally {
                    hideTyping()
                }
            }
            addSystem("[${provider.label}] stopped after max iterations (12).")
        } catch (e: Exception) {
            addSystem("[${provider.label}] error: ${e.message}")
        }
    }
}

                                
