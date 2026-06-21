package com.example.tool

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log

object ToolExecutor {
    private const val TAG = "ToolExecutor"

    /**
     * Resolves and searches a contact phone number by name from the device list.
     */
    fun findContactPhoneNumber(context: Context, contactName: String): String? {
        try {
            val resolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactName%")
            
            val cursor: Cursor? = resolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        return it.getString(numberIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contacts query failed: ${e.localizedMessage}")
        }
        return null
    }

    /**
     * Launch any installed application by searching matching name or package.
     */
    fun openApp(context: Context, packageOrAppName: String): Boolean {
        val pm = context.packageManager
        // If package name is provided directly
        try {
            val intent = pm.getLaunchIntentForPackage(packageOrAppName)
            if (intent != null) {
                context.startActivity(intent)
                return true
            }
        } catch (_: Exception) {}

        // Otherwise filter installed apps by display name
        try {
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in packages) {
                val name = pm.getApplicationLabel(appInfo).toString()
                if (name.contains(packageOrAppName, ignoreCase = true)) {
                    val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "App launching failed: ${e.localizedMessage}")
        }
        return false
    }

    /**
     * Triggers a direct call to a phone number.
     */
    fun callContact(context: Context, phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                context.startActivity(intent)
                true
            } else {
                Log.e(TAG, "CALL_PHONE permission is missing!")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call contact failed: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Opens SMS app initialized with custom message.
     */
    fun sendSMS(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send SMS failed: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Opens WhatsApp chat initialized with message.
     */
    fun sendWhatsAppMessage(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            // Normalize phone number (remove spacing and symbols)
            val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "WhatsApp integration failed: ${e.localizedMessage}")
            false
        }
    }

    /**
     * Sends email via intent.
     */
    fun sendEmail(context: Context, recipient: String, subject: String, body: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send email failed: ${e.localizedMessage}")
            false
        }
    }

    fun openCamera(context: Context) {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open camera failed", e)
        }
    }

    fun takePhoto(context: Context) {
        try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Take photo failed", e)
        }
    }

    fun recordVideo(context: Context) {
        try {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Record video failed", e)
        }
    }

    fun toggleFlashlight(context: Context, enable: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight toggle failed: ${e.localizedMessage}")
            false
        }
    }

    fun adjustVolume(context: Context, percentage: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVal = (maxVolume * (percentage.coerceIn(0, 100) / 100f)).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVal, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Volume adjust failed: ${e.localizedMessage}")
            false
        }
    }

    fun setAlarm(context: Context, hour: Int, minutes: Int, label: String = "Zoya Alarm"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Set alarm failed: ${e.localizedMessage}")
            false
        }
    }

    fun createReminder(context: Context, title: String, dateTime: String): Boolean {
        return try {
            // Launch calendar reminder setup
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = Uri.parse("content://com.android.calendar/events")
                putExtra("title", title)
                putExtra("description", "Created by Zoya Prime AI")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Create calendar reminder failed: ${e.localizedMessage}")
            false
        }
    }

    fun openMaps(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open maps failed", e)
        }
    }

    fun navigateTo(context: Context, destination: String): Boolean {
        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Navigation failed: ${e.localizedMessage}")
            false
        }
    }

    fun launchSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Launch settings failed", e)
        }
    }
}
