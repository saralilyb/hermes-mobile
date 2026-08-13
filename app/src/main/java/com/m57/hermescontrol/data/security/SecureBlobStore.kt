package com.m57.hermescontrol.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A failure to authenticate or parse a secure blob. Callers must not fall back on this error. */
class SecureBlobException(cause: Throwable? = null) : Exception("Secure storage authentication failed", cause)

internal interface BlobCipher {
    fun encrypt(
        logicalKey: String,
        plaintext: ByteArray,
    ): ByteArray

    fun decrypt(
        logicalKey: String,
        blob: ByteArray,
    ): ByteArray
}

/** Android Keystore AES-256-GCM. Every encryption receives a fresh provider-generated 96-bit nonce. */
internal class AndroidKeystoreBlobCipher : BlobCipher {
    private val key: SecretKey
        get() {
            synchronized(KEY_LOCK) {
                val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                generator.init(
                    KeyGenParameterSpec
                        .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                return generator.generateKey()
            }
        }

    override fun encrypt(
        logicalKey: String,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == NONCE_BYTES)
        cipher.updateAAD(aad(logicalKey))
        val ciphertext = cipher.doFinal(plaintext)
        return encode(cipher.iv, ciphertext)
    }

    override fun decrypt(
        logicalKey: String,
        blob: ByteArray,
    ): ByteArray =
        try {
            val (nonce, ciphertext) = decode(blob)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(logicalKey))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SecureBlobException(e)
        }

    private fun encode(
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray =
        java.io.ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(VERSION)
                output.write(nonce)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }

    private fun decode(blob: ByteArray): Pair<ByteArray, ByteArray> {
        if (blob.size < HEADER_BYTES + TAG_BITS / 8) throw SecureBlobException()
        return DataInputStream(blob.inputStream()).use { input ->
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) throw SecureBlobException()
            val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
            nonce to ByteArray(input.available()).also(input::readFully)
        }
    }

    private fun aad(logicalKey: String): ByteArray =
        byteArrayOf(VERSION.toByte()) + logicalKey.toByteArray(Charsets.UTF_8)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hermes_secure_blobs_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAGIC = 0x48534D31
        const val VERSION = 1
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val HEADER_BYTES = 4 + 1 + NONCE_BYTES
        val KEY_LOCK = Any()
    }
}

/** Independent AtomicFile blobs. A tombstone is encrypted like any other value. */
internal class SecureBlobStore(
    context: Context,
    private val cipher: BlobCipher = AndroidKeystoreBlobCipher(),
) {
    private val directory =
        context.noBackupFilesDir.resolve(DIRECTORY).also { directory ->
            if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
                throw SecureBlobException()
            }
        }

    fun read(logicalKey: String): ReadResult =
        synchronized(lockFor(logicalKey)) {
            val file = fileFor(logicalKey)
            val backupFile = file.baseFile.resolveSibling(file.baseFile.name + ".bak")
            if (!file.baseFile.exists() && !backupFile.exists()) return ReadResult.Missing
            val plaintext =
                try {
                    cipher.decrypt(logicalKey, file.readFully())
                } catch (e: SecureBlobException) {
                    throw e
                } catch (e: Exception) {
                    throw SecureBlobException(e)
                }
            if (plaintext.isEmpty()) throw SecureBlobException()
            when (plaintext[0]) {
                VALUE -> ReadResult.Value(plaintext.copyOfRange(1, plaintext.size))
                TOMBSTONE -> if (plaintext.size == 1) ReadResult.Deleted else throw SecureBlobException()
                else -> throw SecureBlobException()
            }
        }

    fun write(
        logicalKey: String,
        value: ByteArray,
    ) = writePayload(logicalKey, byteArrayOf(VALUE) + value)

    fun delete(logicalKey: String) = writePayload(logicalKey, byteArrayOf(TOMBSTONE))

    private fun writePayload(
        logicalKey: String,
        plaintext: ByteArray,
    ) = synchronized(lockFor(logicalKey)) {
        val file = fileFor(logicalKey)
        val stream = file.startWrite()
        try {
            stream.write(cipher.encrypt(logicalKey, plaintext))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun lockFor(key: String): Any = synchronized(LOCKS) { LOCKS.getOrPut(key) { Any() } }

    private fun fileFor(logicalKey: String): AtomicFile {
        val digest = MessageDigest.getInstance("SHA-256").digest(logicalKey.toByteArray(Charsets.UTF_8))
        return AtomicFile(directory.resolve(digest.joinToString("") { "%02x".format(it) } + ".blob"))
    }

    sealed interface ReadResult {
        data object Missing : ReadResult

        data object Deleted : ReadResult

        data class Value(val bytes: ByteArray) : ReadResult
    }

    private companion object {
        const val DIRECTORY = "secure_blobs_v1"
        const val VALUE: Byte = 1
        const val TOMBSTONE: Byte = 2
        val LOCKS = mutableMapOf<String, Any>()
    }
}
