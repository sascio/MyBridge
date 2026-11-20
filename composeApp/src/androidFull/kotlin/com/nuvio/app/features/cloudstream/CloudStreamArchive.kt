package com.nuvio.app.features.cloudstream

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

// A compressed manifest can expand far beyond the download limit. Bound actual
// bytes read rather than trusting the size declared in the archive directory.
internal fun ZipFile.readCloudStreamManifest(entry: ZipEntry): String? {
    val limit = 1024 * 1024
    if (entry.size > limit) return null
    return getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) return@use null
            output.write(buffer, 0, count)
        }
        output.toByteArray().decodeToString()
    }
}

/** Bound actual decompressed bytes and entry count before an archive reaches a class loader. */
internal fun ZipFile.checkCloudStreamExpansion(
    maxBytes: Long = 128L * 1024 * 1024,
    maxEntries: Int = 4096,
) {
    val entries = entries()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    var count = 0
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (++count > maxEntries) throw java.util.zip.ZipException("CloudStream archive has too many entries")
        if (entry.size > maxBytes - total) throw java.util.zip.ZipException("CloudStream archive expands beyond the limit")
        if (entry.isDirectory) continue
        getInputStream(entry).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw java.util.zip.ZipException("CloudStream archive expands beyond the limit")
            }
        }
    }
}
