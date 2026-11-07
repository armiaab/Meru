package com.example.meru

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UsbCameraService : Service() {

    private val CHANNEL_ID = "UsbSyncChannel"
    private val NOTIFICATION_ID = 1
    
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val wakeLockTag = "Meru::UsbSyncWakeLock"
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Starting Sync...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        
        if (device != null) {
            startSync(device)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startSync(device: UsbDevice) {
        serviceScope.launch {
            wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
            try {
                syncPhotosFromDevice(device)
            } catch (e: Exception) {
                Log.e("UsbCameraService", "Error during sync", e)
                updateNotification("Sync Failed: ${e.message}")
            } finally {
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                    }
                }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private suspend fun syncPhotosFromDevice(device: UsbDevice) = withContext(Dispatchers.IO) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        
        if (!usbManager.hasPermission(device)) {
            updateNotification("No USB permission.")
            return@withContext
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            updateNotification("Failed to open USB connection.")
            return@withContext
        }

        val mtpDevice = MtpDevice(device)
        if (!mtpDevice.open(connection)) {
            updateNotification("Failed to open MTP device.")
            connection.close()
            return@withContext
        }

        try {
            val syncDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val destDir = File(syncDir, "sync")
            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            val storageIds = mtpDevice.storageIds
            if (storageIds == null || storageIds.isEmpty()) {
                updateNotification("No storage found on camera.")
                return@withContext
            }

            var totalCopied = 0
            val allPhotos = mutableListOf<Int>()

            for (storageId in storageIds) {
                allPhotos.addAll(getPhotoHandles(mtpDevice, storageId, 0)) // 0 means root
            }

            updateNotification("Found ${allPhotos.size} photos. Starting sync...")

            for ((index, handle) in allPhotos.withIndex()) {
                val objectInfo = mtpDevice.getObjectInfo(handle)
                if (objectInfo != null) {
                    val destFile = File(destDir, objectInfo.name)
                    // Simple check if file exists and size matches to avoid redownloading
                    if (!destFile.exists() || destFile.length() == 0L) {
                        updateNotification("Syncing photo ${index + 1} of ${allPhotos.size}...")
                        mtpDevice.importFile(handle, destFile.absolutePath)
                        totalCopied++
                    }
                }
            }

            updateNotification("Sync complete! Copied $totalCopied new photos.")
            
            // Broadcast intent to update UI
            val broadcastIntent = Intent("com.example.meru.SYNC_UPDATE")
            broadcastIntent.putExtra("status", "Sync complete! Copied $totalCopied new photos.")
            sendBroadcast(broadcastIntent)

        } finally {
            mtpDevice.close()
        }
    }

    private fun getPhotoHandles(mtpDevice: MtpDevice, storageId: Int, parentHandle: Int): List<Int> {
        val handles = mutableListOf<Int>()
        val currentHandles = mtpDevice.getObjectHandles(storageId, 0, parentHandle)
        
        if (currentHandles != null) {
            for (handle in currentHandles) {
                val info = mtpDevice.getObjectInfo(handle)
                if (info != null) {
                    if (info.format == MtpConstants.FORMAT_ASSOCIATION) { // Folder
                        handles.addAll(getPhotoHandles(mtpDevice, storageId, handle))
                    } else if (info.format == MtpConstants.FORMAT_EXIF_JPEG || 
                               info.format == MtpConstants.FORMAT_JFIF ||
                               info.name.endsWith(".jpg", true) ||
                               info.name.endsWith(".jpeg", true)) {
                        handles.add(handle)
                    }
                }
            }
        }
        return handles
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "USB Sync Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Sync")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        
        // Also send broadcast for UI
        val intent = Intent("com.example.meru.SYNC_UPDATE")
        intent.putExtra("status", content)
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
