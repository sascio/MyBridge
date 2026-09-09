package com.streambridge.app.addon.plugin.compat

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The sandbox's ONLY crypto engine: hashing, HMAC, AES (CBC/CTR/ECB/GCM)
 * and random bytes, implemented with the platform's javax.crypto and
 * exposed to provider JavaScript as a single JSON-in/JSON-out binding.
 *
 * This is what backs the node `crypto` adapter, the WebCrypto-shaped
 * `crypto.subtle` shim and the `node-forge` adapter — one audited
 * implementation, no arbitrary native modules.
 */
object CryptoCapability {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Thrown for anything the capability deliberately does not do. */
    class Unsupported(message: String) : Exception(message)

    private val HASH_ALGS = mapOf(
        "md5" to "MD5", "sha1" to "SHA-1", "sha384" to "SHA-384",
        "sha256" to "SHA-256", "sha512" to "SHA-512"
    )
    private val HMAC_ALGS = mapOf(
        "md5" to "HmacMD5", "sha1" to "HmacSHA1",
        "sha256" to "HmacSHA256", "sha512" to "HmacSHA512"
    )

    /** AES algorithm ids ("aes-128-cbc", …) to (keyBits, transform). */
    private fun aesTransform(algorithm: String): Pair<Int, String> {
        val parts = algorithm.lowercase().split("-")
        if (parts.size != 3 || parts[0] != "aes") throw Unsupported("Unknown cipher algorithm: $algorithm")
        val keyBits = parts[1].toIntOrNull()
            ?: throw Unsupported("Unknown key size in algorithm: $algorithm")
        val mode = parts[2]
        val transform = when (mode) {
            "cbc" -> "AES/CBC/PKCS5Padding"
            "ctr" -> "AES/CTR/NoPadding"
            "ecb" -> "AES/ECB/PKCS5Padding"
            "gcm" -> "AES/GCM/NoPadding"
            else -> throw Unsupported("Unsupported AES mode in algorithm: $algorithm")
        }
        if (keyBits !in listOf(128, 192, 256)) throw Unsupported("Invalid AES key size: $keyBits")
        return keyBits to transform
    }

    /**
     * Handles one capability call. [op] selects the operation; [argsJson]
     * carries base64-encoded byte payloads so the boundary stays
     * primitive-only (strings) — the established pattern for reliable
     * JS↔Kotlin conversion in the sandbox.
     */
    fun handle(op: String, argsJson: String): String {
        val args = try {
            json.parseToJsonElement(argsJson).jsonObject
        } catch (_: Exception) {
            throw Unsupported("Malformed crypto request")
        }
        val result = when (op) {
            "hash" -> hash(args)
            "hmac" -> hmac(args)
            "aesEncrypt" -> aes(args, encrypt = true)
            "aesDecrypt" -> aes(args, encrypt = false)
            "randomBytes" -> {
                val len = args["len"]?.jsonPrimitive?.intOrNull ?: 0
                if (len < 0 || len > 1 shl 20) throw Unsupported("Invalid random length")
                val bytes = ByteArray(len)
                SecureRandom().nextBytes(bytes)
                buildJsonObject { put("b64", java.util.Base64.getEncoder().encodeToString(bytes)) }
            }
            "pbkdf2" -> pbkdf2(args)
            else -> throw Unsupported("Unknown crypto operation: $op")
        }
        return result.toString()
    }

    private fun hash(args: JsonObject): JsonObject {
        val alg = args["alg"]?.jsonPrimitive?.content?.lowercase() ?: ""
        val md = MessageDigest.getInstance(
            HASH_ALGS[alg] ?: throw Unsupported("Unsupported hash algorithm: $alg")
        )
        val digest = md.digest(args.bytes("data"))
        return buildJsonObject {
            put("hex", digest.toHex())
            put("b64", java.util.Base64.getEncoder().encodeToString(digest))
        }
    }

    private fun hmac(args: JsonObject): JsonObject {
        val alg = args["alg"]?.jsonPrimitive?.content?.lowercase() ?: ""
        val mac = Mac.getInstance(
            HMAC_ALGS[alg] ?: throw Unsupported("Unsupported HMAC algorithm: $alg")
        )
        mac.init(SecretKeySpec(args.bytes("key"), HMAC_ALGS[alg]!!))
        val out = mac.doFinal(args.bytes("data"))
        return buildJsonObject {
            put("hex", out.toHex())
            put("b64", java.util.Base64.getEncoder().encodeToString(out))
        }
    }

    private fun aes(args: JsonObject, encrypt: Boolean): JsonObject {
        val algorithm = args["alg"]?.jsonPrimitive?.content?.lowercase() ?: ""
        val (keyBits, transform) = aesTransform(algorithm)
        val keyBytes = args.bytes("key")
        if (keyBytes.size != keyBits / 8) {
            throw Unsupported("AES key must be ${keyBits / 8} bytes for $algorithm")
        }
        val cipher = Cipher.getInstance(transform)
        val key = SecretKeySpec(keyBytes, "AES")
        val mode = algorithm.substringAfterLast("-")

        val ivBytes = args.optionalBytes("iv")
        val aadBytes = args.optionalBytes("aad")
        if (mode == "gcm") {
            val iv = ivBytes ?: throw Unsupported("AES-GCM requires an iv")
            if (encrypt) {
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                if (aadBytes != null) cipher.updateAAD(aadBytes)
                val out = cipher.doFinal(args.bytes("data"))
                // Split the tag so forge-style callers can use it.
                val tag = out.copyOfRange(out.size - 16, out.size)
                val body = out.copyOfRange(0, out.size - 16)
                return buildJsonObject {
                    put("b64", java.util.Base64.getEncoder().encodeToString(body))
                    put("tagB64", java.util.Base64.getEncoder().encodeToString(tag))
                }
            } else {
                val tag = args.optionalBytes("tag")
                    ?: throw Unsupported("AES-GCM decryption requires a tag")
                val body = args.bytes("data")
                val combined = body + tag
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                if (aadBytes != null) cipher.updateAAD(aadBytes)
                val out = try {
                    cipher.doFinal(combined)
                } catch (e: Exception) {
                    throw Unsupported("AES-GCM authentication failed")
                }
                return buildJsonObject {
                    put("b64", java.util.Base64.getEncoder().encodeToString(out))
                }
            }
        }

        when {
            mode == "ecb" -> cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key)
            ivBytes != null -> cipher.init(
                if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                key,
                IvParameterSpec(ivBytes)
            )
            else -> throw Unsupported("$algorithm requires an iv")
        }
        val out = try {
            cipher.doFinal(args.bytes("data"))
        } catch (e: Exception) {
            throw Unsupported("Cipher failed: ${e.message?.take(80)}")
        }
        return buildJsonObject {
            put("b64", java.util.Base64.getEncoder().encodeToString(out))
        }
    }

    private fun pbkdf2(args: JsonObject): JsonObject {
        val iterations = args["iterations"]?.jsonPrimitive?.longOrNull ?: 0L
        if (iterations < 1 || iterations > 5_000_000L) throw Unsupported("Invalid pbkdf2 iterations")
        val keyLen = args["keyLen"]?.jsonPrimitive?.intOrNull ?: 0
        if (keyLen < 1 || keyLen > 512) throw Unsupported("Invalid pbkdf2 key length")
        val hashAlg = args["hash"]?.jsonPrimitive?.content?.lowercase() ?: "sha1"
        val factory = when (hashAlg) {
            "sha256" -> "PBKDF2WithHmacSHA256"
            "sha384" -> "PBKDF2WithHmacSHA384"
            "sha512" -> "PBKDF2WithHmacSHA512"
            "sha1" -> "PBKDF2WithHmacSHA1"
            else -> throw Unsupported("Unsupported pbkdf2 hash: $hashAlg")
        }
        val spec = PBEKeySpec(
            args["password"]?.jsonPrimitive?.content?.toCharArray() ?: CharArray(0),
            args.bytes("salt"),
            iterations.toInt(),
            keyLen
        )
        val key = try {
            SecretKeyFactory.getInstance(factory).generateSecret(spec).encoded
        } catch (e: Exception) {
            throw Unsupported("pbkdf2 failed: ${e.message?.take(80)}")
        }
        return buildJsonObject {
            put("b64", java.util.Base64.getEncoder().encodeToString(key))
        }
    }

    // -----------------------------------------------------------------
    // JSON helpers
    // -----------------------------------------------------------------

    private fun JsonObject.bytes(key: String): ByteArray = optionalBytes(key)
        ?: throw Unsupported("Missing byte field: $key")

    private fun JsonObject.optionalBytes(key: String): ByteArray? {
        val value = this[key] ?: return null
        val content = value.jsonPrimitive.content
        if (content.isEmpty()) return ByteArray(0)
        return try {
            java.util.Base64.getDecoder().decode(content)
        } catch (_: Exception) {
            throw Unsupported("Invalid base64 in field: $key")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
