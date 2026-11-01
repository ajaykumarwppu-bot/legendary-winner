package com.example.gemini.ui.browser

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** Extracts an unpacked WebExtension, optionally wrapped in one top-level folder. */
object WebViewExtensionArchive {
    /** Root-relative regular files only; do not follow symlinks or archive the output ZIP itself. */
    fun files(source: File): List<File> {
        val root = source.canonicalFile
        require(root.isDirectory) { "Extension source directory not found" }
        val result = mutableListOf<File>()
        root.walkTopDown().onFail { _, error -> throw error }.onEnter { directory ->
            require(directory.absoluteFile == directory.canonicalFile) { "Symlink directories are not supported: $directory" }
            true
        }.forEach { file ->
            require(file.absoluteFile == file.canonicalFile) { "Symlink files are not supported: $file" }
            if (file.isFile) {
                require(result.size < 5_000) { "Extension contains too many files" }
                result.add(file)
            } else require(file.isDirectory) { "Only files and directories can be packaged" }
        }
        return result.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    }

    fun pack(source: File, destination: File): File {
        val root = source.canonicalFile
        val target = destination.canonicalFile
        require(!target.path.startsWith(root.path + File.separator)) { "ZIP output must be outside the source directory" }
        require(File(root, "manifest.json").isFile) { "Extension manifest.json is missing" }
        val entries = files(root)
        require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) { "Cannot create ZIP directory" }
        val temporary = File(target.parentFile, ".extension-${java.util.UUID.randomUUID()}.zip")
        var total = 0L
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                for (file in entries) {
                    require(file.canonicalFile == file.absoluteFile) { "Symlink files cannot be packaged" }
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                    var size = 0L
                    file.inputStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            size += n; total += n
                            require(size <= 32L * 1024 * 1024 && total <= 128L * 1024 * 1024) { "Unpacked extension exceeds size limit" }
                            zip.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(temporary.renameTo(target)) { "Cannot publish extension ZIP" }
            return target
        } finally { temporary.delete() }
    }

    fun extract(input: InputStream, destination: File): File {
        require(destination.mkdirs() || destination.isDirectory) { "Cannot create extraction directory" }
        val root = destination.canonicalFile
        val paths = mutableSetOf<String>()
        var total = 0L
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 5_000) { "ZIP contains too many entries" }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name) && name.split('/').none { it == ".." }) { "Unsafe ZIP entry: $name" }
                val target = File(root, name).canonicalFile
                require(target.path.startsWith(root.path + File.separator) && paths.add(target.path)) { "Unsafe or duplicate ZIP entry: $name" }
                if (entry.isDirectory) {
                    require(target.mkdirs() || target.isDirectory) { "Cannot create ZIP directory" }
                } else {
                    require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) { "Cannot create ZIP parent" }
                    var size = 0L
                    target.outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            size += n; total += n
                            require(size <= 32L * 1024 * 1024 && total <= 128L * 1024 * 1024) { "Unpacked extension exceeds size limit" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (File(root, "manifest.json").isFile) return root
        val candidates = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') && it.name != "__MACOSX" && File(it, "manifest.json").isFile }
        require(candidates.size == 1) { "ZIP must contain one extension with manifest.json at its root or inside one folder" }
        return candidates.single()
    }
}
