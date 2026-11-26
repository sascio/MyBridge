package com.nuvio.app.features.media.service

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.SecItemUpdate

internal actual object MediaCredentialVault {
    @OptIn(ExperimentalForeignApi::class)
    actual fun read(key: String): String? = query(key) { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) throw MediaServiceException(MediaFailure.Authentication)
            val data: CFDataRef = result.value?.reinterpret() ?: throw MediaServiceException(MediaFailure.InvalidResponse)
            try {
                val size = CFDataGetLength(data).toInt()
                if (size == 0) return@memScoped ""
                val bytes = CFDataGetBytePtr(data) ?: throw MediaServiceException(MediaFailure.InvalidResponse)
                ByteArray(size) { bytes[it].toByte() }.decodeToString()
            } finally { CFRelease(data) }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun write(key: String, value: String?) {
        query(key) { query ->
            if (value == null) {
                val status = SecItemDelete(query)
                if (status != errSecSuccess && status != errSecItemNotFound) throw MediaServiceException(MediaFailure.Unavailable)
                return@query
            }
            require(value.isNotEmpty())
            val bytes = value.encodeToByteArray().toUByteArray()
            val data = CFDataCreate(null, bytes.refTo(0), bytes.size.toLong())
                ?: throw MediaServiceException(MediaFailure.Unavailable)
            val attributes = CFDictionaryCreateMutable(null, 0L, null, null)
                ?: run { CFRelease(data); throw MediaServiceException(MediaFailure.Unavailable) }
            try {
                CFDictionarySetValue(attributes, kSecValueData, data)
                CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                val updated = SecItemUpdate(query, attributes)
                if (updated == errSecItemNotFound) {
                    CFDictionarySetValue(query, kSecValueData, data)
                    CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    if (SecItemAdd(query, null) != errSecSuccess) throw MediaServiceException(MediaFailure.Unavailable)
                } else if (updated != errSecSuccess) throw MediaServiceException(MediaFailure.Unavailable)
            } finally {
                CFRelease(attributes)
                CFRelease(data)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private inline fun <T> query(key: String, block: (CFMutableDictionaryRef) -> T): T {
        val service = CFStringCreateWithCString(null, "com.streambridge.media.credentials.v1", kCFStringEncodingUTF8)
            ?: throw MediaServiceException(MediaFailure.Unavailable)
        val account = CFStringCreateWithCString(null, key, kCFStringEncodingUTF8)
            ?: run { CFRelease(service); throw MediaServiceException(MediaFailure.Unavailable) }
        val query = CFDictionaryCreateMutable(null, 0L, null, null)
            ?: run { CFRelease(account); CFRelease(service); throw MediaServiceException(MediaFailure.Unavailable) }
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            return block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(service)
        }
    }
}
