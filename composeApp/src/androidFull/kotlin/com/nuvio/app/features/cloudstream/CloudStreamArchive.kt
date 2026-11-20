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
