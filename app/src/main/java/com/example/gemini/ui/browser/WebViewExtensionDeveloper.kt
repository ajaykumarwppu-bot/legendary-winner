package com.example.gemini.ui.browser

import android.content.Context
import android.net.Uri
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The developer MCP is separate from each installed extension's normal MCP server. */
object WebViewExtensionDeveloper {
    private const val DOCS = "extension-docs"
    private val draftsMutex = Mutex()
    private val manager get() = BrowserExtensionManager.instance

    fun tools(context: Context) = context.assets.open("$DOCS/developer-tools.json").bufferedReader().use { JSONArray(it.readText()) }

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        try {
            val value = when (name) {
                "webview_extension_docs" -> docs(context, args)
                "webview_extension_list" -> list(context, args)
                "webview_extension_create" -> draftsMutex.withLock { create(context, args) }
                "webview_extension_read_file" -> draftsMutex.withLock { readFile(context, args) }
                "webview_extension_write_files" -> draftsMutex.withLock { writeFiles(context, args) }
                "webview_extension_package" -> draftsMutex.withLock { packaged(context, args.getString("project_id")) }
                "webview_extension_install" -> draftsMutex.withLock { install(context, args) }
                "webview_extension_set_enabled" -> {
                    reloadMetadata(); manager.setEnabled(args.getString("extension_id"), args.getBoolean("enabled"))
                    metadata(args.getString("extension_id"))
                }
                "webview_extension_reload" -> {
                    reloadMetadata(); manager.reload(args.getString("extension_id")); metadata(args.getString("extension_id"))
                }
                "webview_extension_rename" -> {
                    reloadMetadata(); manager.renameFolder(args.getString("extension_id"), args.getString("folder_name")).toJsonObject()
                }
                "webview_extension_test" -> {
                    val id = args.getString("extension_id")
                    val tool = args.optString("tool_name").takeIf { it.isNotBlank() }
                    if (tool != null) return@withContext manager.callTool(id, tool, args.optJSONObject("arguments") ?: JSONObject())
                    JSONObject().put("extension_id", id).put("mcp_url", manager.mcpUrl(id)).put("tools", manager.tools(id))
                        .put("tested", "Worker loaded and tools discovered; no tool was invoked")
                }
                "webview_extension_logs" -> {
                    val limit = args.optInt("limit", 50)
                    require(limit in 1..200) { "limit must be 1–200" }
                    JSONObject(manager.request("/extension/logs", JSONObject().put("id", args.optString("extension_id")).put("limit", limit)))
                }
                else -> error("Unknown extension developer tool: $name")
            }
            response(value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            response(JSONObject().put("error", error.message ?: error.javaClass.simpleName), failed = true)
        }
    }

    private fun response(value: Any, failed: Boolean = false) = JSONObject().apply {
        if (failed) put("isError", true)
        put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.toString())))
    }

    private fun assetFiles(context: Context, path: String = ""): List<String> {
        val entries = context.assets.list(if (path.isEmpty()) DOCS else "$DOCS/$path").orEmpty()
        return entries.flatMap { child ->
            val name = if (path.isEmpty()) child else "$path/$child"
            if (context.assets.list("$DOCS/$name").orEmpty().isEmpty()) listOf(name) else assetFiles(context, name)
        }.sorted()
    }

    private fun page(text: String, args: JSONObject): JSONObject {
        val offset = args.optInt("offset", 0)
        val limit = args.optInt("limit", 16_000)
        require(offset >= 0 && limit in 1..40_000) { "offset must be non-negative and limit must be 1–40000 characters" }
        val end = minOf(text.length.toLong(), offset.toLong() + limit).toInt()
        return JSONObject().put("content", if (offset >= text.length) "" else text.substring(offset, end))
            .put("offset", offset).put("total_characters", text.length)
            .put("next_offset", if (end < text.length) end else JSONObject.NULL)
    }

    private fun docs(context: Context, args: JSONObject): JSONObject {
        val files = assetFiles(context)
        val result = JSONObject().put("files", JSONArray(files))
        if (args.optBoolean("list_only")) return result
        val path = args.optString("path", "README.md")
        require(path in files) { "Unknown SDK document. Use list_only=true to list available paths" }
        val text = context.assets.open("$DOCS/$path").bufferedReader().use { it.readText() }
        val content = page(text, args)
        content.keys().forEach { key -> result.put(key, content.get(key)) }
        return result.put("path", path)
    }

    private fun projectId(value: String): String {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(value)) { "project_id must contain 1–64 letters, numbers, underscores or hyphens" }
        return value
    }

    private fun projects(context: Context): File {
        val home = LocalEnvironmentManager.getHomeDir(context).canonicalFile
        val root = File(home, "extension-projects")
        require(root.canonicalFile == root.absoluteFile) { "Authoring workspace cannot be a symlink" }
        return root
    }

    private fun project(context: Context, id: String) = File(projects(context), projectId(id)).also {
        require(it.canonicalFile == it.absoluteFile) { "Draft project cannot be a symlink" }
    }

    private fun source(context: Context, id: String) = File(project(context, id), "src").also {
        require(it.canonicalFile == it.absoluteFile && it.isDirectory) { "Draft source not found; create the project first" }
    }

    private fun relativeFile(root: File, path: String): File {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !Regex("^[A-Za-z]:").containsMatchIn(path)) { "Use a relative file path" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Relative paths cannot contain empty, . or .. components" }
        val target = File(root, path)
        require(target.canonicalFile == target.absoluteFile && target.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Path escapes the draft or contains a symlink" }
        return target
    }

    private fun create(context: Context, args: JSONObject): JSONObject {
        val id = projectId(args.getString("project_id"))
        val name = args.getString("name").trim()
        require(name.isNotEmpty()) { "name is required" }
        val tool = projectId(args.optString("tool_name", "hello"))
        val project = project(context, id)
        val src = File(project, "src")
        require(!src.exists()) { "Draft already exists; use read_file and write_files to edit it" }
        require(project.mkdirs() || project.isDirectory) { "Cannot create draft workspace" }
        val stage = File(project, ".create-${UUID.randomUUID()}")
        try {
            stage.mkdirs()
            val manifest = context.assets.open("$DOCS/examples/hello-mcp/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            manifest.put("id", id).put("name", name).put("description", args.optString("description", "MCP extension for $name"))
            manifest.getJSONArray("mcp_tools").getJSONObject(0).put("name", tool)
            File(stage, "manifest.json").writeText(manifest.toString(2))
            val worker = context.assets.open("$DOCS/examples/hello-mcp/worker.js").bufferedReader().use { it.readText() }
            File(stage, "worker.js").writeText(worker.replace("message.tool !== 'hello'", "message.tool !== '$tool'"))
            check(stage.renameTo(src)) { "Cannot publish draft source" }
        } finally { stage.deleteRecursively() }
        return JSONObject().put("project_id", id).put("source_dir", src.absolutePath)
            .put("suggested_zip_path", File(project, "dist/$id.zip").absolutePath)
            .put("next_steps", "Edit with webview_extension_write_files, then webview_extension_install(project_id). Read developer-mcp.md for the complete loop.")
    }

    private fun readFile(context: Context, args: JSONObject): JSONObject {
        val root = source(context, args.getString("project_id"))
        val path = args.optString("path")
        if (path.isEmpty()) return JSONObject().put("source_dir", root.absolutePath).put("files", JSONArray().apply {
            WebViewExtensionArchive.files(root).forEach { put(JSONObject().put("path", it.relativeTo(root).invariantSeparatorsPath).put("size", it.length())) }
        })
        val file = relativeFile(root, path)
        require(file.isFile && file.length() <= 32L * 1024 * 1024) { "Draft text file is missing or exceeds 32 MiB" }
        return page(file.readText(), args).put("path", path)
    }

    private fun writeFiles(context: Context, args: JSONObject): JSONObject {
        val root = source(context, args.getString("project_id"))
        val entries = args.getJSONArray("files")
        require(entries.length() in 1..100) { "Provide 1–100 files" }
        val paths = mutableSetOf<String>()
        var bytes = 0L
        val values = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            val file = relativeFile(root, item.getString("path"))
            require(paths.add(file.path)) { "Duplicate file path" }
            require(!file.isDirectory) { "File path is a directory" }
            val text = item.getString("content").toByteArray(Charsets.UTF_8)
            bytes += text.size
            require(text.size <= 32 * 1024 * 1024 && bytes <= 128L * 1024 * 1024) { "Draft write exceeds package size limits" }
            file to text
        }
        for ((file, text) in values) {
            require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) { "Cannot create draft directory" }
            val temporary = File(file.parentFile, ".write-${UUID.randomUUID()}.tmp")
            try {
                temporary.writeBytes(text)
                check(temporary.renameTo(file)) { "Cannot write draft file" }
            } finally { temporary.delete() }
        }
        return JSONObject().put("written", JSONArray(values.map { it.first.relativeTo(root).invariantSeparatorsPath })).put("bytes", bytes)
            .put("installed_worker_changed", false)
    }

    private fun packaged(context: Context, id: String): JSONObject {
        val root = source(context, id)
        val manifest = manager.validatePackage(root)
        val destination = WebViewExtensionArchive.pack(root, relativeFile(project(context, id), "dist/$id.zip"))
        return JSONObject().put("project_id", id).put("zip_path", destination.absolutePath).put("bytes", destination.length())
            .put("manifest", manifest).put("validated", "Package structure; worker JavaScript is checked during enabled installation")
    }

    private suspend fun reloadMetadata() = withContext(Dispatchers.Main) { manager.loadExtensions() }
    private fun metadata(id: String) = manager.extensions.first { it.id == id }.toJsonObject().put("mcp_url", manager.mcpUrl(id))

    private suspend fun list(context: Context, args: JSONObject): JSONObject {
        if (args.optBoolean("refresh")) manager.refreshExtensions() else reloadMetadata()
        val installed = withContext(Dispatchers.Main) { manager.extensions.toList() }
        var hostError: String? = null
        val live = try { JSONArray(manager.request("/extensions")) } catch (e: Exception) { hostError = e.message; JSONArray() }
        val loaded = (0 until live.length()).associate { live.getJSONObject(it).getString("id") to live.getJSONObject(it) }
        return JSONObject().put("extensions", JSONArray().apply {
            installed.forEach { put(it.toJsonObject().put("loaded", loaded[it.id]?.optString("dir") == it.dirPath).put("mcp_url", manager.mcpUrl(it.id))) }
        }).put("projects", JSONArray(projects(context).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.map { it.name }))
            .put("host_error", hostError ?: JSONObject.NULL)
    }

    private suspend fun install(context: Context, args: JSONObject): JSONObject {
        val id = args.optString("project_id").takeIf { it.isNotBlank() }
        val path = args.optString("zip_path").takeIf { it.isNotBlank() }
        require((id == null) != (path == null)) { "Provide exactly one of project_id or zip_path" }
        val zip = if (id != null) File(packaged(context, id).getString("zip_path")) else {
            val home = LocalEnvironmentManager.getHomeDir(context).canonicalFile
            val expanded = if (path!!.startsWith("~/")) File(home, path.removePrefix("~/")) else File(path)
            val file = expanded.canonicalFile
            val shared = File("/sdcard").canonicalFile
            require(file.path.startsWith(home.path + File.separator) || file.path.startsWith(shared.path + File.separator)) { "ZIP must be inside terminal home or shared storage" }
            require(file.isFile) { "ZIP file not found" }
            file
        }
        reloadMetadata()
        val updateId = args.optString("extension_id").takeIf { it.isNotBlank() }
        val previous = updateId?.let { target -> manager.extensions.firstOrNull { it.id == target } ?: error("Installed extension not found: $target") }
        val folder = previous?.folderName ?: args.optString("folder_name").ifBlank { id ?: zip.nameWithoutExtension }
        val extension = manager.installArchive(Uri.fromFile(zip), folder, updateId)
        return extension.toJsonObject().put("mcp_url", manager.mcpUrl(extension.id)).put("updated", previous != null)
            .put("next_steps", "Use webview_extension_test(extension_id) to discover tools, then pass tool_name and arguments for a real test call. The model can test through this developer MCP without adding another server.")
    }
}
