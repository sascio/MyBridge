package com.nuvio.app.features.cloudstream

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import java.util.zip.ZipException

class CloudStreamArchiveTest {
    @Test
    fun manifestReadIsBoundedAfterDecompression() {
        val file = File.createTempFile("cloudstream-archive-test", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() })
                zip.closeEntry()
            }
            ZipFile(file).use { zip ->
                assertNull(zip.readCloudStreamManifest(zip.getEntry("manifest.json")))
            }
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
            ZipFile(file).use { zip ->
                assertEquals("{}", zip.readCloudStreamManifest(zip.getEntry("manifest.json")))
            }
        } finally {
            file.delete()
        }
    }
    @Test
    fun archiveExpansionAndEntryCountAreBounded() {
        val file = File.createTempFile("cloudstream-limits-test", ".zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                for (name in listOf("one", "two")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(ByteArray(80) { 0 })
                    zip.closeEntry()
                }
            }
            ZipFile(file).use { zip ->
                zip.checkCloudStreamExpansion(maxBytes = 160, maxEntries = 2)
                assertFailsWith<ZipException> { zip.checkCloudStreamExpansion(maxBytes = 159, maxEntries = 2) }
                assertFailsWith<ZipException> { zip.checkCloudStreamExpansion(maxBytes = 160, maxEntries = 1) }
            }
        } finally {
            file.delete()
        }
    }

}
