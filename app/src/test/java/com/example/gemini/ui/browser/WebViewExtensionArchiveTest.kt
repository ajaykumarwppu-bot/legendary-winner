package com.example.gemini.ui.browser

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebViewExtensionArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun archive(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name, value) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
    private fun extract(vararg entries: Pair<String, String>) = WebViewExtensionArchive.extract(
        ByteArrayInputStream(archive(*entries)), File(temporary.newFolder(), "extracted")
    )
    @Test fun rootAndWrappedPackages() {
        val root = extract("manifest.json" to "{}", "worker.js" to "ok")
        assertEquals("ok", File(root, "worker.js").readText())
        val wrapped = extract("sample/manifest.json" to "{}", "sample/lib/worker.js" to "ok")
        assertEquals("sample", wrapped.name)
        assertEquals("ok", File(wrapped, "lib/worker.js").readText())
    }
    @Test fun rejectsEscapesAndAbsoluteNames() {
        for (name in listOf("../outside", "nested/../../outside", "..\\outside", "/outside", "C:/outside")) {
            assertThrows(IllegalArgumentException::class.java) { extract(name to "bad", "manifest.json" to "{}") }
        }
    }
    @Test fun rejectsCanonicalDuplicatesAndAmbiguousPackages() {
        assertThrows(IllegalArgumentException::class.java) { extract("manifest.json" to "{}", "./manifest.json" to "different") }
        assertThrows(IllegalArgumentException::class.java) { extract("one/manifest.json" to "{}", "two/manifest.json" to "{}") }
        assertThrows(IllegalArgumentException::class.java) { extract("worker.js" to "no manifest") }
    }
    @Test fun rejectsAnArchiveWithTooManyEntries() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip -> repeat(5_001) { zip.putNextEntry(ZipEntry("file$it")); zip.closeEntry() } }
        assertThrows(IllegalArgumentException::class.java) {
            WebViewExtensionArchive.extract(ByteArrayInputStream(bytes.toByteArray()), temporary.newFolder())
        }
    }
    @Test fun packRoundTripsEveryRelativeDependency() {
        val source = temporary.newFolder("source")
        File(source, "manifest.json").writeText("{}")
        File(source, "lib").mkdirs()
        File(source, "lib/worker.js").writeText("export const result=42;")
        val image = byteArrayOf(0, 1, 2, 3)
        File(source, "image.png").writeBytes(image)
        val zip = WebViewExtensionArchive.pack(source, File(temporary.newFolder(), "extension.zip"))
        val unpacked = zip.inputStream().use { WebViewExtensionArchive.extract(it, temporary.newFolder()) }
        assertEquals("export const result=42;", File(unpacked, "lib/worker.js").readText())
        assertArrayEquals(image, File(unpacked, "image.png").readBytes())
    }
    @Test fun packRejectsOutputInsideSourceAndSymlinkDependencies() {
        val source = temporary.newFolder("source")
        File(source, "manifest.json").writeText("{}")
        assertThrows(IllegalArgumentException::class.java) { WebViewExtensionArchive.pack(source, File(source, "output.zip")) }
        val outside = temporary.newFile("outside.js")
        java.nio.file.Files.createSymbolicLink(File(source, "escape.js").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { WebViewExtensionArchive.pack(source, File(temporary.newFolder(), "output.zip")) }
    }
    @Test fun failedPackPreservesPreviousZipAndCleansTemporaryOutput() {
        val source = temporary.newFolder("source")
        File(source, "manifest.json").writeText("{}")
        java.io.RandomAccessFile(File(source, "oversized.bin"), "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        val directory = temporary.newFolder("output")
        val target = File(directory, "extension.zip").apply { writeText("previous zip") }
        assertThrows(IllegalArgumentException::class.java) { WebViewExtensionArchive.pack(source, target) }
        assertEquals("previous zip", target.readText())
        assertEquals(listOf("extension.zip"), directory.list()!!.toList())
    }

}
