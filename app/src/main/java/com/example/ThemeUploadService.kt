package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Play Policy compliant `dataSync` Foreground Service that publishes
 * Theme Hub previews, large (>25MB) GitHub Release assets, variant binaries,
 * and `themes.json` catalog updates uninterrupted even when the app is backgrounded.
 */
class ThemeUploadService : Service() {

    companion object {
        private const val TAG = "ThemeUploadService"
        private const val CHANNEL_ID = "themehub_uploads"
        private const val NOTIF_ID = 44091
        private const val FINISHED_NOTIF_ID = 44092

        const val ACTION_START_UPLOAD = "com.example.action.START_THEME_UPLOAD"
        const val ACTION_CANCEL_UPLOAD = "com.example.action.CANCEL_THEME_UPLOAD"
        const val EXTRA_JOB_JSON_PATH = "jobJsonPath"

        private val SAFE_REPO_SEGMENT = Regex("^[a-zA-Z0-9._-]+$")
        private val SAFE_UPLOAD_ID = Regex("^[a-zA-Z0-9_-]{4,64}$")
        private val TOKEN_REDACT_REGEX = Regex("(gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|Bearer\\s+[A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
        private const val STREAM_BUFFER_SIZE = 32 * 1024

        fun sanitizeNoTokens(input: String?): String {
            if (input.isNullOrBlank()) return ""
            return TOKEN_REDACT_REGEX.replace(input, "[REDACTED]").take(400)
        }

        @Volatile
        private var volatileGithubToken: String? = null

        fun setGithubToken(token: String?) {
            volatileGithubToken = token?.trim()?.takeIf { it.isNotEmpty() }
        }

        private val _statusFlow = MutableStateFlow("")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

        fun getLatestStatusJson(): String = _statusFlow.value

        fun stageDirFor(context: Context, uploadId: String): File? {
            if (!SAFE_UPLOAD_ID.matches(uploadId)) return null
            val root = File(context.cacheDir, "themehub_uploads")
            if (!root.exists()) root.mkdirs()
            val dir = File(root, uploadId)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun safeStageFile(context: Context, uploadId: String, fileKey: String): File? {
            val dir = stageDirFor(context, uploadId) ?: return null
            val cleanKey = fileKey.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
            if (cleanKey.isEmpty()) return null
            return File(dir, cleanKey)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    @Volatile
    private var cancelRequested = false

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_UPLOAD -> {
                cancelRequested = true
                activeJob?.cancel()
                emitStatus(
                    uploadId = "",
                    themeId = "",
                    themeName = "",
                    status = "cancelled",
                    stageKey = "cancelled",
                    percent = 0,
                    stepText = "تم إلغاء رفع الثيم",
                    error = "",
                    mediaStates = emptyList(),
                    themesUpdated = false
                )
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }

            ACTION_START_UPLOAD -> {
                val jobPath = intent.getStringExtra(EXTRA_JOB_JSON_PATH).orEmpty()
                val jobFile = if (jobPath.isNotEmpty()) File(jobPath) else null
                if (jobFile == null || !jobFile.exists()) {
                    Log.e(TAG, "Missing staged upload job file")
                    stopSelf()
                    return START_NOT_STICKY
                }

                cancelRequested = false
                val parsedMeta = runCatching { JSONObject(jobFile.readText(Charsets.UTF_8)) }.getOrNull()
                val initialTitle = parsedMeta?.optString("name", "ثيم جديد").orEmpty().ifBlank { "ثيم جديد" }
                val initialThemeId = parsedMeta?.optString("themeId", "").orEmpty()
                val initialMode = normalizePublishMode(
                    parsedMeta?.optString("publishMode", "").orEmpty(),
                    parsedMeta?.optBoolean("isEditMode", false) == true
                )
                val initialVersion = parsedMeta?.optString("version", "v1.0").orEmpty()

                startForegroundCompat(buildProgressNotification(initialTitle, "تجهيز ملفات الثيم…", 2))

                activeJob?.cancel()
                activeJob = serviceScope.launch {
                    var uploadDirToCleanup: File? = jobFile.parentFile
                    try {
                        val jobObj = JSONObject(jobFile.readText(Charsets.UTF_8))
                        uploadDirToCleanup = jobFile.parentFile
                        executeUploadJob(jobObj, jobFile.parentFile)
                    } catch (ce: CancellationException) {
                        Log.i(TAG, "Upload job cancelled")
                        if (!cancelRequested) {
                            emitStatus(
                                uploadId = "",
                                themeId = initialThemeId,
                                themeName = initialTitle,
                                status = "cancelled",
                                stageKey = "cancelled",
                                percent = 0,
                                stepText = "تم إلغاء رفع الثيم",
                                error = "",
                                mediaStates = emptyList(),
                                themesUpdated = false,
                                publishMode = initialMode
                            )
                        }
                        recordPublishLogToFirestore(
                            themeId = initialThemeId,
                            themeName = initialTitle,
                            mode = initialMode,
                            status = "cancelled",
                            version = initialVersion,
                            details = "تم إلغاء الرفع بواسطة المشرف"
                        )
                    } catch (t: Throwable) {
                        val safeErr = sanitizeNoTokens(formatUploadError(t))
                        Log.e(TAG, "Upload job failed: $safeErr")
                        emitStatus(
                            uploadId = "",
                            themeId = initialThemeId,
                            themeName = initialTitle,
                            status = "error",
                            stageKey = "error",
                            percent = 0,
                            stepText = "تعذر إكمال النشر",
                            error = safeErr,
                            mediaStates = emptyList(),
                            themesUpdated = false,
                            publishMode = initialMode
                        )
                        recordPublishLogToFirestore(
                            themeId = initialThemeId,
                            themeName = initialTitle,
                            mode = initialMode,
                            status = "failed",
                            version = initialVersion,
                            details = safeErr
                        )
                        postTerminalNotification(initialTitle, safeErr, success = false)
                    } finally {
                        runCatching {
                            if (uploadDirToCleanup != null &&
                                uploadDirToCleanup.parentFile?.name == "themehub_uploads"
                            ) {
                                uploadDirToCleanup.deleteRecursively()
                            }
                        }
                        stopForegroundAndSelf()
                    }
                }
                return START_NOT_STICKY
            }
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun stopForegroundAndSelf() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private suspend fun resolveGithubToken(): String {
        volatileGithubToken?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val user = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (user != null) {
            val db = Firebase.firestore
            val personal = runCatching {
                db.collection("adminTokens").document(user.uid).get().await().getString("token")?.trim()
            }.getOrNull()
            if (!personal.isNullOrEmpty()) {
                volatileGithubToken = personal
                return personal
            }
            val shared = runCatching {
                db.collection("config").document("github").get().await().getString("token")?.trim()
            }.getOrNull()
            if (!shared.isNullOrEmpty()) {
                volatileGithubToken = shared
                return shared
            }
        }
        throw IllegalStateException("انتهت جلسة توكن GitHub. يرجى إعادة المحاولة من داخل التطبيق.")
    }

    private fun normalizePublishMode(rawMode: String, isEditMode: Boolean): String {
        return when (rawMode.trim().lowercase()) {
            "variant" -> "variant"
            "update" -> "update"
            "new" -> "new"
            else -> if (isEditMode) "update" else "new"
        }
    }

    private suspend fun executeUploadJob(job: JSONObject, stageDir: File?) {
        val token = resolveGithubToken()
        val uploadId = job.optString("uploadId", "")
        val ghOwner = job.optString("ghOwner", "zaim9002").takeIf { SAFE_REPO_SEGMENT.matches(it) } ?: "zaim9002"
        val ghRepo = job.optString("ghRepo", "theme-hub-data").takeIf { SAFE_REPO_SEGMENT.matches(it) } ?: "theme-hub-data"
        val ghBranch = job.optString("ghBranch", "main").takeIf { SAFE_REPO_SEGMENT.matches(it) } ?: "main"
        val ghRawBase = job.optString("ghRawBase", "https://raw.githubusercontent.com/$ghOwner/$ghRepo/$ghBranch/")

        val themeId = job.getString("themeId")
        val assetPrefix = job.getString("assetPrefix")
        val name = job.getString("name")
        val category = job.optString("category", "داكن")
        val version = job.optString("version", "v1.0").ifBlank { "v1.0" }
        val customType = job.optString("customType", "").trim().take(60)
        val tagsArr = job.optJSONArray("tags") ?: JSONArray()
        val descriptionAr = job.optString("description_ar", "")
        val descriptionEn = job.optString("description_en", "")
        val isEditMode = job.optBoolean("isEditMode", false)
        val publishMode = normalizePublishMode(job.optString("publishMode", ""), isEditMode)
        val approvingSubmissionId = job.optString("approvingSubmissionId", "").trim()
        val publisherUid = job.optString("publisherUid", "")
        val publisherName = job.optString("publisherName", "")
        val publisherUsername = job.optString("publisherUsername", "")

        val previewsArr = job.optJSONArray("previews") ?: JSONArray()
        val variantsArr = job.optJSONArray("variants") ?: JSONArray()

        // Calculate total local staged bytes for real speed & ETA tracking
        var totalJobBytes = 0L
        for (i in 0 until previewsArr.length()) {
            val p = previewsArr.optJSONObject(i) ?: continue
            resolveStagedFile(stageDir, p.optString("fileKey", ""))?.takeIf { it.exists() }?.let { totalJobBytes += it.length() }
            resolveStagedFile(stageDir, p.optString("posterKey", ""))?.takeIf { it.exists() }?.let { totalJobBytes += it.length() }
            resolveStagedFile(stageDir, p.optString("thumbKey", ""))?.takeIf { it.exists() }?.let { totalJobBytes += it.length() }
        }
        for (i in 0 until variantsArr.length()) {
            val v = variantsArr.optJSONObject(i) ?: continue
            if (v.optString("uploadMode", "file") != "url") {
                resolveStagedFile(stageDir, v.optString("fileKey", ""))?.takeIf { it.exists() }?.let { totalJobBytes += it.length() }
            }
        }
        var completedBytes = 0L
        var activeFileBytes = 0L
        val transferStartMs = System.currentTimeMillis()
        var smoothedSpeedBps = 0L

        fun updateSpeedAndEta(): Pair<Long, Int> {
            val uploadedNow = (completedBytes + activeFileBytes).coerceAtMost(totalJobBytes.coerceAtLeast(1L))
            val elapsedMs = (System.currentTimeMillis() - transferStartMs).coerceAtLeast(350L)
            val avgSpeed = (uploadedNow * 1000L) / elapsedMs
            smoothedSpeedBps = if (smoothedSpeedBps <= 0L) avgSpeed else ((smoothedSpeedBps * 6L + avgSpeed * 4L) / 10L)
            val remaining = (totalJobBytes - uploadedNow).coerceAtLeast(0L)
            val etaSec = if (smoothedSpeedBps > 512L && remaining > 0L) (remaining / smoothedSpeedBps).toInt().coerceIn(1, 86400) else 0
            return smoothedSpeedBps to etaSec
        }

        val mediaTrackers = MutableList(previewsArr.length()) { idx ->
            val p = previewsArr.optJSONObject(idx)
            val label = p?.optString("label", "وسائط #${idx + 1}") ?: "وسائط #${idx + 1}"
            JSONObject().apply {
                put("index", idx)
                put("label", label)
                put("percent", 0)
                put("status", if (p?.optString("url", "")?.isNotEmpty() == true && p.optString("fileKey", "").isEmpty()) "✓ جاهز" else "في الانتظار")
                put("done", p?.optString("url", "")?.isNotEmpty() == true && p.optString("fileKey", "").isEmpty())
                put("failed", false)
            }
        }

        var lastProgressUpdateMs = 0L
        fun publishUiProgress(percent: Int, stepText: String, stageKey: String, forceNotif: Boolean = false) {
            val safePct = percent.coerceIn(0, 99)
            val (spd, eta) = updateSpeedAndEta()
            val uploadedNow = (completedBytes + activeFileBytes).coerceAtLeast(0L)
            emitStatus(
                uploadId = uploadId,
                themeId = themeId,
                themeName = name,
                status = "uploading",
                stageKey = stageKey,
                percent = safePct,
                stepText = stepText,
                error = "",
                mediaStates = mediaTrackers,
                themesUpdated = false,
                publishMode = publishMode,
                bytesUploaded = uploadedNow,
                totalBytes = totalJobBytes,
                speedBps = spd,
                etaSeconds = eta
            )
            val now = System.currentTimeMillis()
            if (forceNotif || now - lastProgressUpdateMs >= 450L) {
                lastProgressUpdateMs = now
                updateNotification(buildProgressNotification(name, "$stepText ($safePct%)", safePct))
            }
        }

        publishUiProgress(3, "تجهيز الرفع في الخلفية…", "preparing", forceNotif = true)

        var releaseId: Long? = null
        val uploadedPreviews = mutableListOf<JSONObject>()

        // 1. Previews
        for (i in 0 until previewsArr.length()) {
            checkNotCancelled()
            val p = previewsArr.optJSONObject(i) ?: continue
            val type = p.optString("type", "image")
            val existingUrl = p.optString("url", "").trim()
            val existingThumb = p.optString("thumb", existingUrl).trim()
            val existingPoster = p.optString("poster", "").trim()
            val fileKey = p.optString("fileKey", "").trim()
            val thumbKey = p.optString("thumbKey", "").trim()
            val posterKey = p.optString("posterKey", "").trim()
            val ext = p.optString("ext", if (type == "video") "mp4" else "webp").replace(".", "").ifBlank { if (type == "video") "mp4" else "webp" }
            val w = p.optInt("width", 0)
            val h = p.optInt("height", 0)

            if (fileKey.isEmpty() && existingUrl.isNotEmpty()) {
                uploadedPreviews.add(JSONObject().apply {
                    put("type", if (type == "video") "video" else "image")
                    put("url", existingUrl)
                    put("thumb", existingThumb.ifEmpty { existingPoster.ifEmpty { existingUrl } })
                    put("poster", existingPoster)
                    put("width", w)
                    put("height", h)
                })
                continue
            }

            val mainFile = resolveStagedFile(stageDir, fileKey)
            if (mainFile == null || !mainFile.exists()) continue

            val basePct = 5 + ((i * 28) / previewsArr.length().coerceAtLeast(1))
            val stepLabel = if (type == "video") "رفع فيديو المعاينة (${i + 1}/${previewsArr.length()})" else "رفع صورة المعاينة (${i + 1}/${previewsArr.length()})"
            publishUiProgress(basePct, stepLabel, "uploading_media", forceNotif = true)

            try {
                if (type == "video") {
                    var posterUrl = existingPoster
                    val posterFile = resolveStagedFile(stageDir, posterKey)
                    if (posterFile != null && posterFile.exists()) {
                        mediaTrackers[i].put("percent", 10).put("status", "صورة البداية 10%")
                        val posterPath = "previews/$assetPrefix-p${i + 1}-poster.jpg"
                        putGithubFileFromDisk(token, ghOwner, ghRepo, ghBranch, posterPath, "Upload video poster ${i + 1} for $name", posterFile) { _, loaded, _ ->
                            activeFileBytes = loaded
                        }
                        completedBytes += posterFile.length()
                        activeFileBytes = 0L
                        posterUrl = "$ghRawBase$posterPath"
                    }

                    var thumbUrl = posterUrl
                    val thumbFile = resolveStagedFile(stageDir, thumbKey)
                    if (thumbFile != null && thumbFile.exists()) {
                        val thumbPath = "previews/$assetPrefix-p${i + 1}-thumb.webp"
                        putGithubFileFromDisk(token, ghOwner, ghRepo, ghBranch, thumbPath, "Upload video thumb ${i + 1} for $name", thumbFile) { _, loaded, _ ->
                            activeFileBytes = loaded
                        }
                        completedBytes += thumbFile.length()
                        activeFileBytes = 0L
                        thumbUrl = "$ghRawBase$thumbPath"
                    }

                    val videoUrl: String
                    if (mainFile.length() < 25L * 1024L * 1024L) {
                        val vidPath = "themes/$assetPrefix-p${i + 1}.$ext"
                        putGithubFileFromDisk(token, ghOwner, ghRepo, ghBranch, vidPath, "Upload preview video ${i + 1} for $name", mainFile) { pct, loaded, _ ->
                            activeFileBytes = loaded
                            val adj = maxOf(15, pct)
                            mediaTrackers[i].put("percent", adj).put("status", "$adj%")
                            publishUiProgress(basePct + ((pct * 22) / (100 * previewsArr.length().coerceAtLeast(1))), stepLabel, "uploading_media")
                        }
                        completedBytes += mainFile.length()
                        activeFileBytes = 0L
                        videoUrl = "$ghRawBase$vidPath"
                    } else {
                        val rid = releaseId ?: getOrCreateLargeFilesRelease(token, ghOwner, ghRepo, ghBranch).also { releaseId = it }
                        val assetName = "$assetPrefix-p${i + 1}.$ext"
                        videoUrl = uploadBinaryReleaseAsset(token, ghOwner, ghRepo, rid, assetName, mainFile) { pct, loaded, _ ->
                            activeFileBytes = loaded
                            val adj = maxOf(15, pct)
                            mediaTrackers[i].put("percent", adj).put("status", "Release $adj%")
                            publishUiProgress(basePct + ((pct * 22) / (100 * previewsArr.length().coerceAtLeast(1))), "$stepLabel ($adj%)", "uploading_media")
                        }
                        completedBytes += mainFile.length()
                        activeFileBytes = 0L
                    }

                    mediaTrackers[i].put("percent", 100).put("status", "✓ اكتمل").put("done", true)
                    uploadedPreviews.add(JSONObject().apply {
                        put("type", "video")
                        put("url", videoUrl)
                        put("thumb", thumbUrl.ifEmpty { posterUrl })
                        put("poster", posterUrl.ifEmpty { thumbUrl })
                        put("width", w)
                        put("height", h)
                    })
                } else {
                    val fullUrl: String
                    if (mainFile.length() <= 25L * 1024L * 1024L) {
                        val imgPath = "previews/$assetPrefix-p${i + 1}.$ext"
                        putGithubFileFromDisk(token, ghOwner, ghRepo, ghBranch, imgPath, "Upload HD preview ${i + 1} for $name", mainFile) { pct, loaded, _ ->
                            activeFileBytes = loaded
                            mediaTrackers[i].put("percent", pct).put("status", "$pct%")
                            publishUiProgress(basePct + ((pct * 22) / (100 * previewsArr.length().coerceAtLeast(1))), stepLabel, "uploading_media")
                        }
                        completedBytes += mainFile.length()
                        activeFileBytes = 0L
                        fullUrl = "$ghRawBase$imgPath"
                    } else {
                        val rid = releaseId ?: getOrCreateLargeFilesRelease(token, ghOwner, ghRepo, ghBranch).also { releaseId = it }
                        val assetName = "$assetPrefix-p${i + 1}.$ext"
                        fullUrl = uploadBinaryReleaseAsset(token, ghOwner, ghRepo, rid, assetName, mainFile) { pct, loaded, _ ->
                            activeFileBytes = loaded
                            mediaTrackers[i].put("percent", pct).put("status", "Release $pct%")
                            publishUiProgress(basePct + ((pct * 22) / (100 * previewsArr.length().coerceAtLeast(1))), "$stepLabel ($pct%)", "uploading_media")
                        }
                        completedBytes += mainFile.length()
                        activeFileBytes = 0L
                    }

                    var thumbUrl = fullUrl
                    val thumbFile = resolveStagedFile(stageDir, thumbKey)
                    if (thumbFile != null && thumbFile.exists()) {
                        val thumbPath = "previews/$assetPrefix-p${i + 1}-thumb.webp"
                        putGithubFileFromDisk(token, ghOwner, ghRepo, ghBranch, thumbPath, "Upload preview thumb ${i + 1} for $name", thumbFile) { _, loaded, _ ->
                            activeFileBytes = loaded
                        }
                        completedBytes += thumbFile.length()
                        activeFileBytes = 0L
                        thumbUrl = "$ghRawBase$thumbPath"
                    }

                    mediaTrackers[i].put("percent", 100).put("status", "✓ اكتمل").put("done", true)
                    uploadedPreviews.add(JSONObject().apply {
                        put("type", "image")
                        put("url", fullUrl)
                        put("thumb", thumbUrl)
                        put("poster", "")
                        put("width", w)
                        put("height", h)
                    })
                }
            } catch (t: Throwable) {
                mediaTrackers[i].put("failed", true).put("status", "فشل الرفع")
                throw t
            }
        }

        // 2. Variants
        val uploadedVariants = mutableListOf<JSONObject>()
        val varCount = variantsArr.length().coerceAtLeast(1)
        for (i in 0 until variantsArr.length()) {
            checkNotCancelled()
            val v = variantsArr.optJSONObject(i) ?: continue
            val brand = v.optString("brand", "Honor")
            val format = v.optString("format", "hwt").replace(".", "").ifBlank { "hwt" }
            val compat = v.optString("compat", "$brand Custom UI")
            val uploadMode = v.optString("uploadMode", "file")
            val externalUrl = v.optString("externalUrl", "").trim()
            val sizeText = v.optString("sizeText", v.optString("size", ""))
            val fileKey = v.optString("fileKey", "").trim()

            val varBasePct = 34 + ((i * 50) / varCount)
            publishUiProgress(varBasePct, "معالجة نسخة $brand (${i + 1}/$varCount)", "uploading_files", forceNotif = true)

            val finalUrl: String
            val finalSize: String
            val localFile = resolveStagedFile(stageDir, fileKey)

            if (uploadMode == "url") {
                finalUrl = externalUrl
                finalSize = sizeText.ifBlank { "خارجي (>25MB)" }
            } else if (localFile == null || !localFile.exists()) {
                finalUrl = externalUrl
                finalSize = sizeText.ifBlank { "محفوظ مسبقاً" }
            } else {
                val targetFilename = "$assetPrefix-${brand.lowercase()}.$format"
                val sizeBytes = localFile.length()
                val sizeMB = sizeBytes / (1024.0 * 1024.0)
                finalSize = if (sizeMB >= 1.0) String.format("%.1f MB", sizeMB) else String.format("%.1f KB", sizeBytes / 1024.0)

                if (sizeBytes <= 25L * 1024L * 1024L) {
                    val themePath = "themes/$targetFilename"
                    publishUiProgress(varBasePct + 4, "رفع ملف نسخة $brand ($finalSize)…", "uploading_files", forceNotif = true)
                    putGithubFileFromDisk(
                        token, ghOwner, ghRepo, ghBranch, themePath,
                        "${if (isEditMode) "Update" else "Upload"} theme variant $name for $brand",
                        localFile
                    ) { pct, loaded, _ ->
                        activeFileBytes = loaded
                        publishUiProgress(varBasePct + ((pct * 42) / (100 * varCount)), "رفع نسخة $brand: $pct%", "uploading_files")
                    }
                    completedBytes += sizeBytes
                    activeFileBytes = 0L
                    finalUrl = "$ghRawBase$themePath"
                } else {
                    publishUiProgress(varBasePct + 4, "تجهيز Release للملف الكبير ($finalSize)…", "uploading_files", forceNotif = true)
                    val rid = releaseId ?: getOrCreateLargeFilesRelease(token, ghOwner, ghRepo, ghBranch).also { releaseId = it }
                    finalUrl = uploadBinaryReleaseAsset(token, ghOwner, ghRepo, rid, targetFilename, localFile) { pct, loaded, total ->
                        activeFileBytes = loaded
                        val loadedMB = String.format("%.1f", loaded / (1024.0 * 1024.0))
                        val totalMB = String.format("%.1f", total / (1024.0 * 1024.0))
                        publishUiProgress(
                            varBasePct + ((pct * 45) / (100 * varCount)),
                            "رفع نسخة $brand: $pct% ($loadedMB/$totalMB MB)",
                            "uploading_files"
                        )
                    }
                    completedBytes += sizeBytes
                    activeFileBytes = 0L
                }
            }

            uploadedVariants.add(JSONObject().apply {
                put("brand", brand)
                put("format", format)
                put("compat", compat)
                put("file_url", finalUrl)
                put("size", finalSize)
            })
        }

        // 3. Update themes.json atomically with SHA conflict retry
        publishUiProgress(92, "تحديث فهرس themes.json على GitHub…", "updating_index", forceNotif = true)
        val primaryBrand = if (uploadedVariants.size == 1) uploadedVariants[0].getString("brand") else "Universal"
        val primaryDownloadUrl = uploadedVariants.firstOrNull()?.optString("file_url", "").orEmpty()
        val compatList = uploadedVariants.mapNotNull { it.optString("compat").takeIf { c -> c.isNotBlank() } }.distinct()

        val brandsJoined = uploadedVariants.joinToString(", ") { it.optString("brand") }
        val fallbackAr = "ثيم $name الفاخر المخصص لأجهزة $brandsJoined."
        val fallbackEn = "$name premium custom theme for $brandsJoined devices."
        val finalAr = descriptionAr.ifBlank { if (descriptionEn.isBlank()) fallbackAr else "" }
        val finalEn = descriptionEn.ifBlank { if (descriptionAr.isBlank()) fallbackEn else "" }
        val combinedDesc = if (finalAr.isNotEmpty() && finalEn.isNotEmpty() && finalAr != finalEn) {
            "$finalAr\n\n$finalEn"
        } else {
            finalAr.ifEmpty { finalEn }
        }

        val cleanTagsJson = JSONArray()
        for (i in 0 until tagsArr.length()) {
            val tg = tagsArr.optString(i, "").trim().take(24)
            if (tg.isNotEmpty() && cleanTagsJson.length() < 10) cleanTagsJson.put(tg)
        }

        mutateThemesJson(token, ghOwner, ghRepo, ghBranch, "${if (isEditMode) "Update" else "Add"} theme $name (${uploadedVariants.size} variants) in themes.json") { existingList ->
            var oldIndex = -1
            var oldRecord: JSONObject? = null
            if (isEditMode) {
                for (idx in 0 until existingList.length()) {
                    val item = existingList.optJSONObject(idx)
                    if (item != null && item.optString("id") == themeId) {
                        oldIndex = idx
                        oldRecord = item
                        break
                    }
                }
            }

            val fallbackUrl = "https://images.unsplash.com/photo-1550745165-9bc0b252726f?w=800&auto=format&fit=crop&q=85"
            val finalPreviews = JSONArray()
            if (uploadedPreviews.isNotEmpty()) {
                uploadedPreviews.forEach { finalPreviews.put(it) }
            } else {
                val oldPrev = oldRecord?.optJSONArray("previews")
                if (oldPrev != null && oldPrev.length() > 0) {
                    for (k in 0 until oldPrev.length()) finalPreviews.put(oldPrev.get(k))
                } else {
                    finalPreviews.put(JSONObject().apply {
                        put("type", "image")
                        put("url", fallbackUrl)
                        put("thumb", fallbackUrl)
                        put("poster", "")
                        put("width", 800)
                        put("height", 1422)
                    })
                }
            }

            val previewImagesArr = JSONArray()
            for (k in 0 until finalPreviews.length()) {
                val pObj = finalPreviews.optJSONObject(k) ?: continue
                val previewImg = if (pObj.optString("type") == "video") {
                    pObj.optString("poster").ifBlank { pObj.optString("thumb").ifBlank { pObj.optString("url") } }
                } else {
                    pObj.optString("url")
                }
                previewImagesArr.put(previewImg)
            }

            val variantsJsonArr = JSONArray()
            uploadedVariants.forEach { variantsJsonArr.put(it) }

            val compatJsonArr = JSONArray()
            if (compatList.isNotEmpty()) compatList.forEach { compatJsonArr.put(it) }
            else compatJsonArr.put("$primaryBrand Custom UI")

            val effectivePubUid = publisherUid.ifBlank { oldRecord?.optString("publisherUid", "").orEmpty() }
            val effectivePubName = publisherName.ifBlank { oldRecord?.optString("publisherName", "").orEmpty() }
            val effectivePubUsername = publisherUsername.ifBlank { oldRecord?.optString("publisherUsername", "").orEmpty() }
            val effectiveTags = if (cleanTagsJson.length() > 0) cleanTagsJson else (oldRecord?.optJSONArray("tags") ?: JSONArray())
            val effectiveCustomType = customType.ifBlank { oldRecord?.optString("customType", "").orEmpty() }

            val newThemeObj = JSONObject().apply {
                put("id", themeId)
                put("name", name)
                put("brand", primaryBrand)
                put("category", category)
                put("type", uploadedVariants.firstOrNull()?.optString("format", "hwt") ?: "hwt")
                put("version", version)
                put("size", uploadedVariants.firstOrNull()?.optString("size", "") ?: "")
                put("previews", finalPreviews)
                put("previewImages", previewImagesArr)
                put("downloadUrl", primaryDownloadUrl)
                put("rating", if (oldRecord != null && oldRecord.has("rating")) oldRecord.optDouble("rating", 0.0) else 0)
                put("downloads", if (oldRecord != null && oldRecord.has("downloads")) oldRecord.optLong("downloads", 0L) else 0L)
                put("addedAt", if (oldRecord != null && oldRecord.optLong("addedAt", 0L) > 0L) oldRecord.optLong("addedAt") else System.currentTimeMillis())
                put("updatedAt", System.currentTimeMillis())
                put("compatibility", compatJsonArr)
                put("description", combinedDesc)
                put("description_ar", finalAr)
                put("description_en", finalEn)
                put("variants", variantsJsonArr)
                if (effectiveCustomType.isNotEmpty()) put("customType", effectiveCustomType)
                if (effectiveTags.length() > 0) put("tags", effectiveTags)
                if (effectivePubUid.isNotEmpty()) {
                    put("publisherUid", effectivePubUid)
                    put("publisherName", effectivePubName)
                    put("publisherUsername", effectivePubUsername)
                }
            }

            val nextList = JSONArray()
            if (oldIndex >= 0) {
                for (idx in 0 until existingList.length()) {
                    nextList.put(if (idx == oldIndex) newThemeObj else existingList.get(idx))
                }
            } else {
                nextList.put(newThemeObj)
                for (idx in 0 until existingList.length()) {
                    nextList.put(existingList.get(idx))
                }
            }
            nextList
        }

        // 4. Approve pending submission in Firestore if this publish fulfilled a user submission
        if (approvingSubmissionId.isNotEmpty()) {
            runCatching { approveSubmissionInFirestore(approvingSubmissionId, themeId) }
        }

        val doneMsg = if (publishMode == "variant") {
            "تمت إضافة النسخة الجديدة وتحديث الثيم على GitHub!"
        } else if (isEditMode) {
            "تم حفظ التعديلات على GitHub والتطبيق!"
        } else {
            "تم نشر الثيم بنجاح على GitHub والتطبيق!"
        }

        recordPublishLogToFirestore(
            themeId = themeId,
            themeName = name,
            mode = publishMode,
            status = "success",
            version = version,
            details = "${uploadedVariants.size} نسخة • ${uploadedPreviews.size} وسائط"
        )

        emitStatus(
            uploadId = uploadId,
            themeId = themeId,
            themeName = name,
            status = "success",
            stageKey = "done",
            percent = 100,
            stepText = doneMsg,
            error = "",
            mediaStates = mediaTrackers,
            themesUpdated = true,
            approvingSubmissionId = approvingSubmissionId,
            isEditMode = isEditMode,
            publishMode = publishMode,
            bytesUploaded = totalJobBytes,
            totalBytes = totalJobBytes,
            speedBps = smoothedSpeedBps,
            etaSeconds = 0
        )
        postTerminalNotification(name, doneMsg, success = true)
    }

    private suspend fun recordPublishLogToFirestore(
        themeId: String,
        themeName: String,
        mode: String,
        status: String,
        version: String,
        details: String
    ) {
        val user = runCatching { Firebase.auth.currentUser }.getOrNull() ?: return
        val uid = user.uid
        if (uid.isBlank()) return
        val safeMode = when (mode) {
            "new", "update", "variant", "delete", "version_bump", "hide", "show" -> mode
            else -> "new"
        }
        val safeStatus = when (status) {
            "success", "failed", "cancelled" -> status
            else -> "success"
        }
        val label = user.displayName?.takeIf { it.isNotBlank() } ?: user.email?.substringBefore("@") ?: "مشرف"
        val docData = hashMapOf<String, Any>(
            "actorUid" to uid,
            "actorLabel" to label.take(60),
            "themeId" to themeId.take(120),
            "themeName" to themeName.take(120),
            "mode" to safeMode,
            "status" to safeStatus,
            "version" to version.take(30),
            "details" to sanitizeNoTokens(details).take(240),
            "createdAt" to System.currentTimeMillis()
        )
        runCatching {
            Firebase.firestore.collection("publishLogs").add(docData).await()
        }
    }

    private suspend fun approveSubmissionInFirestore(submissionId: String, themeId: String) {
        val db = Firebase.firestore
        val reviewerUid = Firebase.auth.currentUser?.uid.orEmpty()
        val subRef = db.collection("submissions").document(submissionId)
        val now = System.currentTimeMillis()
        val info = db.runTransaction { tx ->
            val snap = tx.get(subRef)
            if (!snap.exists()) return@runTransaction null
            if (snap.getString("status") == "approved") return@runTransaction null
            tx.update(subRef, mapOf(
                "status" to "approved",
                "rejectReason" to "",
                "approvedThemeId" to themeId,
                "reviewedByUid" to reviewerUid,
                "reviewedAt" to now,
                "updatedAt" to now
            ))
            Triple(
                snap.getString("publisherUid") ?: "",
                snap.getString("publisherName") ?: "",
                (snap.getString("publisherUsername") ?: "") to (snap.getString("title") ?: "ثيم")
            )
        }.await() ?: return

        val pubUid = info.first
        val pubName = info.second
        val pubUsername = info.third.first
        val title = info.third.second

        if (themeId.isNotBlank() && pubUid.isNotBlank()) {
            runCatching {
                db.collection("themes").document(themeId).set(
                    hashMapOf<String, Any>(
                        "publisherUid" to pubUid,
                        "publisherName" to pubName,
                        "publisherUsername" to pubUsername,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                ).await()
            }
            runCatching {
                db.collection("notifications").document(pubUid).collection("items").add(
                    hashMapOf<String, Any>(
                        "type" to "submission_approved",
                        "title" to "🎉 تم قبول ونشر ثيمك",
                        "body" to "تهانينا! تمت الموافقة على ثيم «$title» ونشره في المتجر.",
                        "submissionId" to submissionId,
                        "themeId" to themeId,
                        "postId" to "",
                        "actorUid" to reviewerUid,
                        "actorName" to "",
                        "read" to false,
                        "createdAt" to now
                    )
                ).await()
            }
        }
        if (reviewerUid.isNotBlank()) {
            runCatching {
                db.collection("auditLog").add(
                    hashMapOf<String, Any>(
                        "actorUid" to reviewerUid,
                        "action" to "approve_submission",
                        "targetType" to "submission",
                        "targetId" to submissionId,
                        "details" to "ثيم: $title ($themeId)",
                        "createdAt" to now
                    )
                ).await()
            }
        }
    }

    private fun resolveStagedFile(stageDir: File?, fileKey: String): File? {
        if (stageDir == null || fileKey.isBlank()) return null
        val clean = fileKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        if (clean.isEmpty()) return null
        return File(stageDir, clean)
    }

    private fun checkNotCancelled() {
        if (cancelRequested) throw CancellationException("Cancelled by user")
    }

    private fun githubApiGetJson(token: String, owner: String, repo: String, path: String): JSONObject? {
        val url = "https://api.github.com/repos/$owner/$repo/$path"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (resp.code == 404) return null
            if (!resp.isSuccessful) {
                throw GitHubHttpException(resp.code, "GitHub GET ($path) -> HTTP ${resp.code}: ${extractGithubErr(raw)}")
            }
            return if (raw.isBlank()) null else JSONObject(raw)
        }
    }

    private fun githubApiGetArray(token: String, owner: String, repo: String, path: String): JSONArray {
        val url = "https://api.github.com/repos/$owner/$repo/$path"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GitHubHttpException(resp.code, "GitHub GET ($path) -> HTTP ${resp.code}: ${extractGithubErr(raw)}")
            }
            return if (raw.isBlank()) JSONArray() else JSONArray(raw)
        }
    }

    private fun putGithubFileFromDisk(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        repoPath: String,
        commitMessage: String,
        sourceFile: File,
        onProgress: ((Int, Long, Long) -> Unit)?
    ) {
        var sha = runCatching {
            githubApiGetJson(token, owner, repo, "contents/$repoPath?ref=$branch&t=${System.currentTimeMillis()}")?.optString("sha", "")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val rawLen = sourceFile.length().coerceAtLeast(1L)
        var attempt = 0
        while (true) {
            checkNotCancelled()
            val tempBodyFile = File.createTempFile("gh_put_", ".json", cacheDir)
            try {
                FileOutputStream(tempBodyFile).use { fos ->
                    val prefixObj = JSONObject().apply {
                        put("message", commitMessage)
                        put("branch", branch)
                        if (!sha.isNullOrBlank()) put("sha", sha)
                    }
                    val prefixStr = prefixObj.toString()
                    val head = prefixStr.substring(0, prefixStr.length - 1) + ",\"content\":\""
                    fos.write(head.toByteArray(Charsets.UTF_8))
                    val b64Stream = Base64OutputStream(fos, Base64.NO_WRAP)
                    FileInputStream(sourceFile).use { fis ->
                        val buf = ByteArray(STREAM_BUFFER_SIZE)
                        while (true) {
                            val r = fis.read(buf)
                            if (r <= 0) break
                            b64Stream.write(buf, 0, r)
                        }
                    }
                    b64Stream.flush()
                    // Write tail quote+brace without closing underlying fos early
                    fos.write("\"}".toByteArray(Charsets.UTF_8))
                }

                val url = "https://api.github.com/repos/$owner/$repo/contents/$repoPath"
                val body = CountingFileRequestBody(
                    file = tempBodyFile,
                    mediaType = "application/json; charset=utf-8",
                    onProgress = { pct, loaded, total ->
                        val approxRawLoaded = ((loaded * rawLen) / total.coerceAtLeast(1L)).coerceIn(0L, rawLen)
                        onProgress?.invoke(pct.coerceIn(0, 99), approxRawLoaded, rawLen)
                    },
                    isCancelled = { cancelRequested }
                )
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github.v3+json")
                    .put(body)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val respText = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        onProgress?.invoke(100, rawLen, rawLen)
                        return
                    }
                    if ((resp.code == 409 || resp.code == 422) && attempt < 2) {
                        attempt++
                        sha = runCatching {
                            githubApiGetJson(token, owner, repo, "contents/$repoPath?ref=$branch&t=${System.currentTimeMillis()}")?.optString("sha", "")
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                        return@use
                    }
                    throw GitHubHttpException(resp.code, explainGithubCode(resp.code, "رفع $repoPath", extractGithubErr(respText)))
                }
            } finally {
                runCatching { tempBodyFile.delete() }
            }
        }
    }

    private fun getOrCreateLargeFilesRelease(token: String, owner: String, repo: String, branch: String): Long {
        val existing = runCatching {
            githubApiGetJson(token, owner, repo, "releases/tags/themes-large")
        }.getOrNull()
        val existingId = existing?.optLong("id", 0L) ?: 0L
        if (existingId > 0L) return existingId

        val payload = JSONObject().apply {
            put("tag_name", "themes-large")
            put("target_commitish", branch)
            put("name", "Theme Hub Large Files (Storage)")
            put("body", "Automatic binary storage for Theme Hub theme packages exceeding 25MB.")
            put("draft", false)
            put("prerelease", false)
        }.toString()

        val reqBody = RequestBody.create("application/json; charset=utf-8".toMediaType(), payload)
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .post(reqBody)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GitHubHttpException(resp.code, "فشل إنشاء Release للملفات الكبيرة (HTTP ${resp.code}): ${extractGithubErr(raw)}")
            }
            return JSONObject(raw).getLong("id")
        }
    }

    private fun deleteDuplicateReleaseAssetIfPresent(token: String, owner: String, repo: String, releaseId: Long, assetName: String): Boolean {
        val assets = runCatching { githubApiGetArray(token, owner, repo, "releases/$releaseId/assets?per_page=100") }.getOrNull()
            ?: return false
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == assetName) {
                val id = a.optLong("id", 0L)
                if (id > 0L) {
                    val req = Request.Builder()
                        .url("https://api.github.com/repos/$owner/$repo/releases/assets/$id")
                        .header("Authorization", "Bearer $token")
                        .header("Accept", "application/vnd.github.v3+json")
                        .delete()
                        .build()
                    runCatching { httpClient.newCall(req).execute().close() }
                    return true
                }
            }
        }
        return false
    }

    private fun uploadBinaryReleaseAsset(
        token: String,
        owner: String,
        repo: String,
        releaseId: Long,
        assetName: String,
        sourceFile: File,
        onProgress: (Int, Long, Long) -> Unit
    ): String {
        var attempt = 0
        while (true) {
            checkNotCancelled()
            val encodedName = URLEncoder.encode(assetName, "UTF-8").replace("+", "%20")
            val uploadUrl = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=$encodedName"
            val body = CountingFileRequestBody(
                file = sourceFile,
                mediaType = "application/octet-stream",
                onProgress = onProgress,
                isCancelled = { cancelRequested }
            )
            val req = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github.v3+json")
                .post(body)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.code == 201) {
                    return JSONObject(raw).getString("browser_download_url")
                }
                if (resp.code == 422 && attempt == 0) {
                    attempt++
                    val deleted = deleteDuplicateReleaseAssetIfPresent(token, owner, repo, releaseId, assetName)
                    if (deleted) return@use
                }
                throw GitHubHttpException(resp.code, "فشل رفع الملف الكبير [$assetName] (HTTP ${resp.code}): ${extractGithubErr(raw)}")
            }
        }
    }

    private fun mutateThemesJson(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        commitMessage: String,
        mutator: (JSONArray) -> JSONArray
    ) {
        var attempt = 0
        while (true) {
            checkNotCancelled()
            var currentSha: String? = null
            var existingList = JSONArray()
            val meta = githubApiGetJson(token, owner, repo, "contents/themes.json?ref=$branch&t=${System.currentTimeMillis()}")
            if (meta != null) {
                currentSha = meta.optString("sha", "").takeIf { it.isNotBlank() }
                var rawText = ""
                val b64 = meta.optString("content", "")
                if (b64.isNotBlank()) {
                    rawText = String(Base64.decode(b64.replace("\\s+".toRegex(), ""), Base64.DEFAULT), Charsets.UTF_8)
                } else if (currentSha != null) {
                    val blob = githubApiGetJson(token, owner, repo, "git/blobs/$currentSha")
                    val blobB64 = blob?.optString("content", "").orEmpty()
                    if (blobB64.isNotBlank()) {
                        rawText = String(Base64.decode(blobB64.replace("\\s+".toRegex(), ""), Base64.DEFAULT), Charsets.UTF_8)
                    }
                }
                if (rawText.isNotBlank()) {
                    existingList = runCatching { JSONArray(rawText) }.getOrDefault(JSONArray())
                }
            }

            val updatedList = mutator(existingList)
            val updatedJsonBytes = updatedList.toString(2).toByteArray(Charsets.UTF_8)
            val encodedContent = Base64.encodeToString(updatedJsonBytes, Base64.NO_WRAP)
            val putBodyObj = JSONObject().apply {
                put("message", commitMessage)
                put("content", encodedContent)
                put("branch", branch)
                if (currentSha != null) put("sha", currentSha)
            }

            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/themes.json")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github.v3+json")
                .put(RequestBody.create("application/json; charset=utf-8".toMediaType(), putBodyObj.toString()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return
                if ((resp.code == 409 || resp.code == 422) && attempt < 3) {
                    attempt++
                    return@use
                }
                throw GitHubHttpException(resp.code, explainGithubCode(resp.code, "تحديث themes.json", extractGithubErr(raw)))
            }
        }
    }

    private fun explainGithubCode(code: Int, action: String, apiMsg: String): String {
        val cleanMsg = sanitizeNoTokens(apiMsg)
        return when (code) {
            401 -> "رمز GitHub غير صالح أو منتهي الصلاحية (401) أثناء $action. تأكد من تجديد التوكن."
            403 -> "صلاحيات التوكن غير كافية أو تجاوز حد الطلبات (403) أثناء $action. فعّل صلاحية repo / Contents Write."
            404 -> "المستودع أو المسار غير موجود أو التوكن لا يملك صلاحية الوصول (404) أثناء $action."
            409 -> "تعارض SHA أثناء التحديث المتزامن (409) أثناء $action. أعد المحاولة الآن."
            422 -> "بيانات أو ملف مكرر/مرفوض من GitHub (422) أثناء $action${if (cleanMsg.isNotEmpty()) ": $cleanMsg" else "."}"
            else -> "فشل $action (HTTP $code)${if (cleanMsg.isNotEmpty()) ": $cleanMsg" else ""}"
        }
    }

    private fun formatUploadError(t: Throwable): String {
        if (t is GitHubHttpException) return sanitizeNoTokens(t.message)
        val raw = t.message.orEmpty()
        return if (raw.contains("timeout", ignoreCase = true)) {
            "انتهت مهلة الاتصال بالشبكة أثناء الرفع. تحقق من الإنترنت واستخدم إعادة المحاولة."
        } else if (raw.contains("Unable to resolve host", ignoreCase = true)) {
            "لا يوجد اتصال بالإنترنت أو تعذر الوصول لخادم GitHub."
        } else {
            sanitizeNoTokens(raw.ifBlank { "فشل الرفع في الخلفية" })
        }
    }

    private fun extractGithubErr(raw: String): String {
        if (raw.isBlank()) return ""
        return sanitizeNoTokens(runCatching { JSONObject(raw).optString("message", raw.take(150)) }.getOrDefault(raw.take(150)))
    }

    private fun emitStatus(
        uploadId: String,
        themeId: String,
        themeName: String,
        status: String,
        stageKey: String = "",
        percent: Int,
        stepText: String,
        error: String,
        mediaStates: List<JSONObject>,
        themesUpdated: Boolean,
        approvingSubmissionId: String = "",
        isEditMode: Boolean = false,
        publishMode: String = "new",
        bytesUploaded: Long = 0L,
        totalBytes: Long = 0L,
        speedBps: Long = 0L,
        etaSeconds: Int = 0
    ) {
        val payload = JSONObject().apply {
            put("uploadId", uploadId)
            put("themeId", themeId)
            put("themeName", themeName)
            put("status", status)
            put("stageKey", stageKey.ifBlank { status })
            put("percent", percent)
            put("stepText", stepText)
            put("error", sanitizeNoTokens(error))
            put("themesUpdated", themesUpdated)
            put("approvingSubmissionId", approvingSubmissionId)
            put("isEditMode", isEditMode)
            put("publishMode", publishMode)
            put("bytesUploaded", bytesUploaded)
            put("totalBytes", totalBytes)
            put("speedBps", speedBps)
            put("etaSeconds", etaSeconds)
            val mArr = JSONArray()
            mediaStates.forEach { mArr.put(it) }
            put("media", mArr)
            put("updatedAt", System.currentTimeMillis())
        }.toString()
        _statusFlow.value = payload
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "رفع ونشر الثيمات",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "تقدم رفع الثيمات والوسائط في الخلفية"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildProgressNotification(title: String, text: String, percent: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this, 101, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ThemeUploadService::class.java).apply {
            action = ACTION_CANCEL_UPLOAD
        }
        val cancelPending = PendingIntent.getService(
            this, 102, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("رفع: $title")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إلغاء", cancelPending)
            .build()
    }

    private fun updateNotification(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIF_ID, notif)
    }

    private fun postTerminalNotification(title: String, message: String, success: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this, 103, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "✓ اكتمل نشر: $title" else "فشل رفع: $title")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentPending)
            .build()
        nm.notify(FINISHED_NOTIF_ID, notif)
    }

    private class GitHubHttpException(val statusCode: Int, message: String) : IOException(message)

    private class CountingFileRequestBody(
        private val file: File,
        private val mediaType: String,
        private val onProgress: (Int, Long, Long) -> Unit,
        private val isCancelled: () -> Boolean
    ) : RequestBody() {
        override fun contentType() = mediaType.toMediaType()
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1L)
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var uploaded = 0L
            var lastReportMs = 0L
            FileInputStream(file).use { fis ->
                while (true) {
                    if (isCancelled()) throw CancellationException("Cancelled")
                    val read = fis.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    uploaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastReportMs >= 160L || uploaded >= total) {
                        lastReportMs = now
                        val pct = ((uploaded * 100L) / total).toInt().coerceIn(0, 100)
                        onProgress(pct, uploaded, total)
                    }
                }
            }
            sink.flush()
        }
    }
}
