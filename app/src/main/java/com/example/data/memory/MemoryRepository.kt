package com.example.data.memory

import android.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MemoryRepository(private val dao: MemoryDao) {
    
    // Simple XOR cipher key for lightweight secure storage
    private val keyXor = 0xAA.toByte()

    private fun encrypt(normalText: String): String {
        return try {
            val bytes = normalText.toByteArray(Charsets.UTF_8)
            val encryptedBytes = ByteArray(bytes.size)
            for (i in bytes.indices) {
                encryptedBytes[i] = (bytes[i].toInt() xor keyXor.toInt()).toByte()
            }
            Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            normalText
        }
    }

    private fun decrypt(encryptedBase64: String): String {
        return try {
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val decryptedBytes = ByteArray(encryptedBytes.size)
            for (i in encryptedBytes.indices) {
                decryptedBytes[i] = (encryptedBytes[i].toInt() xor keyXor.toInt()).toByte()
            }
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            encryptedBase64
        }
    }

    val allMemoriesFlow: Flow<List<MemoryEntity>> = dao.getAllMemoriesFlow().map { list ->
        list.map { memory ->
            memory.copy(value = decrypt(memory.value))
        }
    }

    suspend fun saveMemory(type: String, key: String, value: String) {
        val encrypted = encrypt(value)
        val memory = MemoryEntity(
            type = type,
            memoryKey = key,
            value = encrypted
        )
        // Check if already exists to keep single ID or replace
        val existing = dao.getMemoryByKey(key)
        val finalMemory = if (existing != null) {
            existing.copy(type = type, value = encrypted, timestamp = System.currentTimeMillis())
        } else {
            memory
        }
        dao.saveMemory(finalMemory)
    }

    suspend fun getMemory(key: String): String? {
        val entity = dao.getMemoryByKey(key) ?: return null
        return decrypt(entity.value)
    }

    suspend fun getMemoriesByType(type: String): List<MemoryEntity> {
        return dao.getMemoriesByType(type).map { memory ->
            memory.copy(value = decrypt(memory.value))
        }
    }

    suspend fun deleteMemoryByKey(key: String) {
        dao.deleteMemoryByKey(key)
    }

    suspend fun clear() {
        dao.clearAll()
    }
}
