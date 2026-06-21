package com.example.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "zoya_memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // "preference", "contact_pref", "routine", "conversation", "system_log"
    val memoryKey: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)
