package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.data.memory.MemoryDatabase
import com.example.data.memory.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ZoyaNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "ZoyaNotificationService"
        
        private val _incomingNotifications = MutableStateFlow<List<NotificationSummary>>(emptyList())
        val incomingNotifications: StateFlow<List<NotificationSummary>> = _incomingNotifications

        fun clearNotifications() {
            _incomingNotifications.value = emptyList()
        }
    }

    data class NotificationSummary(
        val packageName: String,
        val sender: String,
        val title: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isMissedCall: Boolean = false
    )

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var memoryRepository: MemoryRepository

    override fun onCreate() {
        super.onCreate()
        val db = MemoryDatabase.getDatabase(this)
        memoryRepository = MemoryRepository(db.memoryDao)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        
        if (title.isEmpty() && text.isEmpty()) return

        val isMissedCall = packageName.contains("dialer") || packageName.contains("telecom") || title.contains("Missed Call", ignoreCase = true) || text.contains("Missed call", ignoreCase = true)

        val summary = NotificationSummary(
            packageName = packageName,
            sender = title,
            title = title,
            text = text,
            isMissedCall = isMissedCall
        )

        Log.i(TAG, "Notification intercepted: ${summary.packageName} | Title: ${summary.title} | Text: ${summary.text}")

        // Add to active notifications
        val currentList = _incomingNotifications.value.toMutableList()
        currentList.add(0, summary)
        if (currentList.size > 20) {
            currentList.removeLast()
        }
        _incomingNotifications.value = currentList

        // Save critical notifications (SMS, missed calls, WhatsApp) into Zoya's Memory DB as intelligence events!
        serviceScope.launch {
            val shouldSave = isMissedCall || 
                    packageName.contains("sms", ignoreCase = true) || 
                    packageName.contains("whatsapp", ignoreCase = true) || 
                    packageName.contains("mms", ignoreCase = true)

            if (shouldSave) {
                val tag = if (isMissedCall) "MISSED_CALL" else "INCOMING_MSG"
                val reportContent = "Sender: $title | Msg: $text | App: $packageName"
                memoryRepository.saveMemory(
                    type = "notification_alert",
                    key = "notif_${sbn.postTime}",
                    value = "[$tag] $reportContent"
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
