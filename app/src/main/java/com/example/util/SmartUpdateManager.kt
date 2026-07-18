package com.example.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.api.Firebase
import com.example.api.FirebaseConfig
import com.example.api.OutboxDrainer
import com.example.data.AppDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

sealed class SmartUpdateStatus {
    object Idle : SmartUpdateStatus()
    object Checking : SmartUpdateStatus()
    object SecuringData : SmartUpdateStatus()
    data class NewVersionAvailable(
        val versionNo: Int,
        val patchFileUrl: String?,
        val fullApkUrl: String,
        val patchMd5: String?,
        val isForceUpdate: Boolean
    ) : SmartUpdateStatus()
    object NoUpdateAvailable : SmartUpdateStatus()
    data class Downloading(val progress: Float, val isPatch: Boolean) : SmartUpdateStatus()
    object Merging : SmartUpdateStatus()
    data class ReadyToInstall(val apkFile: File, val isForceUpdate: Boolean) : SmartUpdateStatus()
    data class Error(val message: String) : SmartUpdateStatus()
}

object SmartUpdateManager {
    private const val TAG = "SmartUpdateManager"

    private val _updateStatus = MutableStateFlow<SmartUpdateStatus>(SmartUpdateStatus.Idle)
    val updateStatus: StateFlow<SmartUpdateStatus> = _updateStatus.asStateFlow()

    private val _isForceUpdateRequired = MutableStateFlow(false)
    val isForceUpdateRequired: StateFlow<Boolean> = _isForceUpdateRequired.asStateFlow()

    var activeForceUpdateConfig: SmartUpdateStatus.NewVersionAvailable? = null

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        // Lifecycle Storage Cleanup (App Launch Purge)
        updateScope.launch(Dispatchers.IO) {
            try {
                val otaDir = File(context.cacheDir, "ota_updates")
                if (otaDir.exists()) {
                    otaDir.listFiles()?.forEach { file ->
                        if (file.delete()) {
                            Log.i(TAG, "Lifecycle Cleanup: Deleted stale update file: ${file.name}")
                        }
                    }
                } else {
                    otaDir.mkdirs()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed during app launch storage cleanup", e)
            }
        }
    }

    /**
     * Compare local app version against the cloud configuration inside RTDB at `/UPDATE_CONFIG`.
     */
    fun checkForUpdates(context: Context, manualCheck: Boolean = false) {
        if (_updateStatus.value is SmartUpdateStatus.Downloading || _updateStatus.value is SmartUpdateStatus.Merging) {
            Log.i(TAG, "Update operation in progress, ignoring check.")
            return
        }

        _updateStatus.value = SmartUpdateStatus.Checking
        updateScope.launch {
            try {
                val localVersion = AppUpdateManager.getCurrentVersionCode(context)
                Firebase.ensureFirebaseInitialized(context)
                val dbUrl = FirebaseConfig.getDatabaseUrl(context)
                if (dbUrl.isEmpty()) {
                    _updateStatus.value = SmartUpdateStatus.Error("Firebase database URL not configured.")
                    return@launch
                }

                val database = FirebaseDatabase.getInstance(dbUrl)
                val ref = database.getReference("UPDATE_CONFIG")

                ref.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!snapshot.exists()) {
                            // Seed default config for reference if node is empty
                            ref.child("Version_no").setValue(localVersion)
                            ref.child("Full_Apk_Url").setValue("https://example.com/app-full.apk")
                            ref.child("Patch_File_Url").setValue("https://example.com/app-patch.bin")
                            ref.child("Patch_MD5").setValue("")
                            ref.child("Is_Force_Update").setValue(false)

                            _updateStatus.value = SmartUpdateStatus.NoUpdateAvailable
                            return
                        }

                        val cloudVersion = snapshot.child("Version_no").getValue(Int::class.java) ?: localVersion
                        val fullApkUrl = snapshot.child("Full_Apk_Url").getValue(String::class.java) ?: ""
                        val patchFileUrl = snapshot.child("Patch_File_Url").getValue(String::class.java)
                        val patchMd5 = snapshot.child("Patch_MD5").getValue(String::class.java)
                        val isForceUpdate = snapshot.child("Is_Force_Update").getValue(Boolean::class.java) ?: false

                        Log.d(TAG, "Cloud version: $cloudVersion, Local version: $localVersion")

                        if (cloudVersion > localVersion) {
                            val updateConfig = SmartUpdateStatus.NewVersionAvailable(
                                versionNo = cloudVersion,
                                patchFileUrl = if (patchFileUrl.isNullOrEmpty()) null else patchFileUrl,
                                fullApkUrl = fullApkUrl,
                                patchMd5 = patchMd5,
                                isForceUpdate = isForceUpdate
                            )
                            if (isForceUpdate) {
                                _isForceUpdateRequired.value = true
                                activeForceUpdateConfig = updateConfig
                            } else {
                                _isForceUpdateRequired.value = false
                                activeForceUpdateConfig = null
                            }
                            _updateStatus.value = updateConfig
                        } else {
                            _isForceUpdateRequired.value = false
                            activeForceUpdateConfig = null
                            _updateStatus.value = SmartUpdateStatus.NoUpdateAvailable
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        _updateStatus.value = SmartUpdateStatus.Error("Failed to fetch cloud config: ${error.message}")
                    }
                })

            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                _updateStatus.value = SmartUpdateStatus.Error("Error checking updates: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Start the download and upgrade flow with priority to Delta Patches.
     */
    fun triggerSmartUpdate(context: Context, newVersion: SmartUpdateStatus.NewVersionAvailable) {
        updateScope.launch {
            try {
                _updateStatus.value = SmartUpdateStatus.SecuringData

                // 1. Safety Lock Preconditions Check:
                // Do not initiate update download until Room Outbox is fully empty and focus timer status is "Relaxing".
                val db = AppDatabase.getInstance(context)
                var outboxItems = db.outboxQueueDao().getPendingQueueDirect()
                if (outboxItems.isNotEmpty()) {
                    Log.i(TAG, "Safety Lock: Outbox has ${outboxItems.size} items. Triggering active sync drain...")
                    withContext(Dispatchers.IO) {
                        OutboxDrainer.processQueue(context, outboxItems)
                        outboxItems = db.outboxQueueDao().getPendingQueueDirect()
                    }
                }

                val focusStatus = com.example.api.DynamicCommandManager.currentStatusFlow.value
                Log.d(TAG, "Safety Lock: Outbox remaining count: ${outboxItems.size}, Active focus status: $focusStatus")

                val isRelaxing = focusStatus.equals("Relaxing", ignoreCase = true) || 
                                 focusStatus.equals("IDLE", ignoreCase = true) || 
                                 focusStatus.isEmpty()

                if (outboxItems.isNotEmpty() || !isRelaxing) {
                    Log.w(TAG, "Safety Lock preconditions NOT met! Focus status must be 'Relaxing' or 'IDLE' (Current: $focusStatus) and local outbox must be empty (Current size: ${outboxItems.size}). Aborting download.")
                    _updateStatus.value = SmartUpdateStatus.Error("Preconditions failed: Ensure focus timer status is 'Relaxing' or 'IDLE' and all pending syncs are complete.")
                    return@launch
                }

                // Preconditions met, proceed with Update!
                val patchUrl = newVersion.patchFileUrl
                if (!patchUrl.isNullOrEmpty()) {
                    Log.i(TAG, "Smart Updater: Delta patch is available. Fetching patch file...")
                    downloadDeltaPatchAndMerge(context, patchUrl, newVersion)
                } else {
                    Log.i(TAG, "Smart Updater: No patch available. Falling back to Full APK download...")
                    downloadFullApk(context, newVersion.fullApkUrl, newVersion)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Smart update process failed", e)
                _updateStatus.value = SmartUpdateStatus.Error("Update process failed: ${e.localizedMessage}")
            }
        }
    }

    private suspend fun downloadDeltaPatchAndMerge(
        context: Context,
        patchUrl: String,
        newVersion: SmartUpdateStatus.NewVersionAvailable
    ) {
        _updateStatus.value = SmartUpdateStatus.Downloading(0f, isPatch = true)
        val otaDir = File(context.cacheDir, "ota_updates")
        if (!otaDir.exists()) otaDir.mkdirs()

        val patchFile = File(otaDir, "v${newVersion.versionNo}_patch.bin")
        val mergedApkFile = File(otaDir, "updated_app.apk")

        try {
            // Download patch using OkHttp inside a coroutine to support internal cache and progress updates
            val success = downloadFileWithProgress(patchUrl, patchFile) { progress ->
                _updateStatus.value = SmartUpdateStatus.Downloading(progress, isPatch = true)
            }

            if (!success) {
                throw IOException("Patch download failed.")
            }

            _updateStatus.value = SmartUpdateStatus.Merging

            // Delta Merge: Locate the currently installed base APK using context.applicationInfo.sourceDir
            val baseApkPath = context.applicationInfo.sourceDir
            val baseApk = File(baseApkPath)

            Log.i(TAG, "Delta Merge: base.apk location: $baseApkPath (${baseApk.length()} bytes)")
            Log.i(TAG, "Delta Merge: patch location: ${patchFile.absolutePath} (${patchFile.length()} bytes)")

            // Merge base.apk + patch.bin -> updated_app.apk
            withContext(Dispatchers.IO) {
                BSPatch.patch(baseApk, mergedApkFile, patchFile)
            }

            // Verify the new APK using Patch_MD5
            val expectedMd5 = newVersion.patchMd5 ?: ""
            val actualMd5 = calculateMD5(mergedApkFile)
            Log.d(TAG, "Delta Merge: Verification. Expected MD5: $expectedMd5, Actual MD5: $actualMd5")

            if (expectedMd5.isNotEmpty() && !expectedMd5.equals(actualMd5, ignoreCase = true)) {
                throw IOException("MD5 checksum mismatch! Patched APK is corrupted. Expected: $expectedMd5, Got: $actualMd5")
            }

            // Post-Merge Patch Deletion (Immediate Cleanup)
            if (patchFile.exists()) {
                if (patchFile.delete()) {
                    Log.i(TAG, "Post-Merge Cleanup: Deleted patch file: ${patchFile.name}")
                }
            }

            // Trigger install
            _updateStatus.value = SmartUpdateStatus.ReadyToInstall(mergedApkFile, newVersion.isForceUpdate)

        } catch (e: Exception) {
            Log.e(TAG, "Delta Patch update failed, performing Full APK fallback...", e)
            if (patchFile.exists()) patchFile.delete()
            if (mergedApkFile.exists()) mergedApkFile.delete()

            // FALLBACK: Download Full APK URL
            downloadFullApk(context, newVersion.fullApkUrl, newVersion)
        }
    }

    private suspend fun downloadFullApk(
        context: Context,
        fullApkUrl: String,
        newVersion: SmartUpdateStatus.NewVersionAvailable
    ) {
        _updateStatus.value = SmartUpdateStatus.Downloading(0f, isPatch = false)
        val otaDir = File(context.cacheDir, "ota_updates")
        if (!otaDir.exists()) otaDir.mkdirs()

        val fullApkFile = File(otaDir, "v${newVersion.versionNo}_full.apk")

        try {
            val success = downloadFileWithProgress(fullApkUrl, fullApkFile) { progress ->
                _updateStatus.value = SmartUpdateStatus.Downloading(progress, isPatch = false)
            }

            if (!success || !AppUpdateManager.isValidApk(fullApkFile)) {
                throw IOException("Full APK download failed or file is not a valid APK.")
            }

            _updateStatus.value = SmartUpdateStatus.ReadyToInstall(fullApkFile, newVersion.isForceUpdate)

        } catch (e: Exception) {
            Log.e(TAG, "Full APK download fallback failed", e)
            _updateStatus.value = SmartUpdateStatus.Error("Failed to update: ${e.localizedMessage}")
        }
    }

    private suspend fun downloadFileWithProgress(
        url: String,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val contentLength = body.contentLength()

                body.byteStream().use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val progress = totalBytesRead.toFloat() / contentLength
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
            }
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "File download failed: $url", e)
            return@withContext false
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L || !AppUpdateManager.isValidApk(apkFile)) {
                _updateStatus.value = SmartUpdateStatus.Error("APK file is missing, empty, or corrupted.")
                return
            }

            // Secure user data asynchronously prior to installation
            updateScope.launch(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getInstance(context)
                    DatabaseBackupHelper.autoBackup(context, db)
                } catch (e: Exception) {
                    Log.e(TAG, "Pre-install backup failed", e)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    _updateStatus.value = SmartUpdateStatus.Error("Please enable 'Install unknown apps' permission and try again.")
                    return
                }
            }

            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            _updateStatus.value = SmartUpdateStatus.ReadyToInstall(apkFile, false)

        } catch (e: Exception) {
            Log.e(TAG, "Installation intent failed", e)
            _updateStatus.value = SmartUpdateStatus.Error("Installation failed: ${e.localizedMessage}")
        }
    }

    fun calculateMD5(file: File): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val md5sum = digest.digest()
        val bigInt = java.math.BigInteger(1, md5sum)
        var output = bigInt.toString(16)
        while (output.length < 32) {
            output = "0$output"
        }
        return output
    }
}

/**
 * Standard pure-Kotlin BSPatch implementation supporting sign bit negative longs and standard BSPatch layout.
 */
object BSPatch {
    fun patch(oldFile: File, newFile: File, patchFile: File) {
        val oldBytes = oldFile.readBytes()
        val patchBytes = patchFile.readBytes()

        val bis = ByteArrayInputStream(patchBytes)
        val dis = DataInputStream(bis)

        // Read header
        val magicBytes = ByteArray(8)
        dis.readFully(magicBytes)
        val magic = String(magicBytes)

        val ctrlBlockLen = readLong(dis)
        val diffBlockLen = readLong(dis)
        val newSize = readLong(dis).toInt()

        if (ctrlBlockLen < 0 || diffBlockLen < 0 || newSize < 0) {
            throw IOException("Invalid patch header: negative lengths")
        }

        // Decompress blocks using GZIPInputStream
        val ctrlBytes = ByteArray(ctrlBlockLen.toInt())
        dis.readFully(ctrlBytes)

        val diffBytes = ByteArray(diffBlockLen.toInt())
        dis.readFully(diffBytes)

        val extraBlockLen = patchBytes.size - 32 - ctrlBlockLen.toInt() - diffBlockLen.toInt()
        val extraBytes = ByteArray(extraBlockLen)
        dis.readFully(extraBytes)

        val ctrlIn = DataInputStream(GZIPInputStream(ByteArrayInputStream(ctrlBytes)))
        val diffIn = GZIPInputStream(ByteArrayInputStream(diffBytes))
        val extraIn = GZIPInputStream(ByteArrayInputStream(extraBytes))

        val newBytes = ByteArray(newSize)
        var oldPtr = 0
        var newPtr = 0

        while (newPtr < newSize) {
            val diffLen = readLong(ctrlIn).toInt()
            val extraLen = readLong(ctrlIn).toInt()
            val offsetAdjust = readLong(ctrlIn).toInt()

            if (newPtr + diffLen > newSize) {
                throw IOException("Corrupt patch: diffLen exceeds output size")
            }

            var i = 0
            while (i < diffLen) {
                val b = diffIn.read()
                if (b == -1) throw EOFException("Unexpected EOF in diff stream")
                val oldVal = if (oldPtr >= 0 && oldPtr < oldBytes.size) oldBytes[oldPtr] else 0
                newBytes[newPtr] = (oldVal + b).toByte()
                newPtr++
                oldPtr++
                i++
            }

            if (newPtr + extraLen > newSize) {
                throw IOException("Corrupt patch: extraLen exceeds output size")
            }

            var j = 0
            while (j < extraLen) {
                val b = extraIn.read()
                if (b == -1) throw EOFException("Unexpected EOF in extra stream")
                newBytes[newPtr] = b.toByte()
                newPtr++
                j++
            }

            oldPtr += offsetAdjust
        }

        ctrlIn.close()
        diffIn.close()
        extraIn.close()

        FileOutputStream(newFile).use { fos ->
            fos.write(newBytes)
        }
    }

    private fun readLong(dis: DataInputStream): Long {
        var valLong = 0L
        for (i in 0..7) {
            val b = dis.read()
            if (b == -1) throw EOFException("Unexpected EOF reading long")
            valLong = valLong or (b.toLong() shl (i * 8))
        }
        val isNegative = (valLong and 0x8000000000000000UL.toLong()) != 0L
        if (isNegative) {
            valLong = valLong and 0x7FFFFFFFFFFFFFFFL
            valLong = -valLong
        }
        return valLong
    }
}
