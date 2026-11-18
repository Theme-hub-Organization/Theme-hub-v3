package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import java.util.Locale
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Core owner accounts (cannot be removed by other admins). Every approved admin also gets owner tools. Keep in sync with firestore.rules and index.html (OWNER_EMAILS). */
private val OWNER_EMAILS: Set<String> = setOf("konami123mw@gmail.com", "zaim9002@gmail.com")
private fun isOwnerEmail(email: String?): Boolean =
    email != null && OWNER_EMAILS.contains(email.trim().lowercase())

class MainActivity : ComponentActivity() {

    private var inMemoryGithubToken: String? = null
    private var webViewReference: WebView? = null
    private var pendingAuthJs: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var isInteractiveSignInRunning = false
    private var activeSignInMode = "restore" // "restore" | "user" | "admin"
    private var lastVerifiedUid: String? = null
    internal var currentUserIsAdmin: Boolean = false
    internal var currentUserIsPublisher: Boolean = false
    internal var currentUserBlocked: Boolean = false
    internal var currentUserMuted: Boolean = false

    internal val communityManager: CommunityFirestoreManager by lazy {
        CommunityFirestoreManager(this, lifecycleScope)
    }

    internal val clubManager: ClubFirestoreManager by lazy {
        ClubFirestoreManager(this, lifecycleScope, communityManager)
    }

    internal val appThemeManager: AppThemeDesignerManager by lazy {
        AppThemeDesignerManager(this, lifecycleScope)
    }

    private var pendingSafKey: String = ""
    private var pendingSafBrand: String = "Honor"
    private var pendingSafFilename: String = ""

    internal val downloadHelper: ThemeDownloadHelper by lazy { ThemeDownloadHelper(this) }

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        if (isInteractiveSignInRunning) return@AuthStateListener
        if (user == null) {
            lastVerifiedUid = null
            inMemoryGithubToken = null
            ThemeUploadService.setGithubToken(null)
            sendAuthIdleToJs()
        } else if (lastVerifiedUid != user.uid) {
            verifyAndRestoreUserSession(user)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Download continues cleanly with in-app progress even if denied */ }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@registerForActivityResult

        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = result.data!!
            val clipData = intent.clipData
            when {
                clipData != null && clipData.itemCount > 0 ->
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                intent.data != null -> arrayOf(intent.data!!)
                else -> null
            }
        } else {
            null
        }
        callback.onReceiveValue(uris)
    }

    private val safFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) {
            sendSafCopyResultToJs(
                success = false,
                actualPath = "",
                message = "تم إلغاء اختيار مجلد الثيمات"
            )
            return@registerForActivityResult
        }
        downloadHelper.saveTreeUri(pendingSafBrand, treeUri)
        if (pendingSafKey.isNotBlank() && pendingSafFilename.isNotBlank()) {
            performCopyToSafTree(pendingSafKey, treeUri, pendingSafFilename)
        } else {
            sendSafCopyResultToJs(
                success = true,
                actualPath = treeUri.toString(),
                message = "تم حفظ مجلد الثيمات بنجاح"
            )
        }
    }

    private var initialThemeDeepLink: String? = null
    private var initialPostDeepLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (application as? ThemeHubApplication)?.ensureFirebaseInitialized()
        initialThemeDeepLink = extractThemeIdFromIntent(intent)
        initialPostDeepLink = extractPostIdFromIntent(intent)
        setContent {
            ThemeHubApp(
                onWebViewReady = { wv ->
                    webViewReference = wv
                    flushPendingAuthJs(wv)
                    initialThemeDeepLink?.let { tid ->
                        val q = JSONObject.quote(tid)
                        wv.post { wv.evaluateJavascript("window.openThemeById?.($q);", null) }
                        initialThemeDeepLink = null
                    }
                    initialPostDeepLink?.let { pid ->
                        val q = JSONObject.quote(pid)
                        wv.post { wv.evaluateJavascript("window.openClubPostById?.($q);", null) }
                        initialPostDeepLink = null
                    }
                },
                onExitApp = { finish() }
            )
        }
        observeBackgroundPublishStatus()
    }

    private fun observeBackgroundPublishStatus() {
        lifecycleScope.launch {
            var lastEmitted = ""
            ThemeUploadService.statusFlow.collect { rawJson ->
                if (rawJson.isBlank() || rawJson == lastEmitted) return@collect
                lastEmitted = rawJson
                val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return@collect
                val status = obj.optString("status")
                evaluateJs("window.onBackgroundPublishProgress?.($rawJson);")
                if (status == "success" || status == "error" || status == "cancelled") {
                    evaluateJs("window.onBackgroundPublishFinished?.($rawJson);")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tid = extractThemeIdFromIntent(intent)
        if (!tid.isNullOrBlank()) {
            val q = JSONObject.quote(tid)
            evaluateJs("window.openThemeById?.($q);")
        }
        val pid = extractPostIdFromIntent(intent)
        if (!pid.isNullOrBlank()) {
            val q = JSONObject.quote(pid)
            evaluateJs("window.openClubPostById?.($q);")
        }
    }

    private fun extractThemeIdFromIntent(intent: Intent?): String? {
        val raw = intent?.data?.getQueryParameter("theme") ?: intent?.getStringExtra("themeId")
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractPostIdFromIntent(intent: Intent?): String? {
        val data = intent?.data
        val qParam = data?.getQueryParameter("post")
        val schemeHostParam = if (data?.scheme == "themehub" && data.host == "post") {
            data.lastPathSegment ?: data.getQueryParameter("id")
        } else null
        val raw = qParam ?: schemeHostParam ?: intent?.getStringExtra("postId")
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun onStart() {
        super.onStart()
        runCatching { Firebase.auth.addAuthStateListener(authStateListener) }
    }

    override fun onStop() {
        super.onStop()
        runCatching { Firebase.auth.removeAuthStateListener(authStateListener) }
    }

    override fun onDestroy() {
        super.onDestroy()
        inMemoryGithubToken = null
        ThemeUploadService.setGithubToken(null)
        detachCommunityListeners()
    }

    private fun detachCommunityListeners() {
        communityManager.detachAllListeners()
        clubManager.detachAllListeners()
        appThemeManager.detachListener()
    }

    internal fun onWebViewLoaded(wv: WebView?) {
        if (wv != null) {
            webViewReference = wv
            flushPendingAuthJs(wv)
            val pendingLink = initialThemeDeepLink
            if (!pendingLink.isNullOrBlank()) {
                val q = JSONObject.quote(pendingLink)
                wv.post { wv.evaluateJavascript("window.openThemeById?.($q);", null) }
                initialThemeDeepLink = null
            }
            val pendingPost = initialPostDeepLink
            if (!pendingPost.isNullOrBlank()) {
                val q = JSONObject.quote(pendingPost)
                wv.post { wv.evaluateJavascript("window.openClubPostById?.($q);", null) }
                initialPostDeepLink = null
            }
            appThemeManager.startAppThemeListener()
            appThemeManager.fetchFrames()
            restoreAdminSessionOnStartup()
        }
    }

    internal fun isOwnerSession(): Boolean = isOwnerEmail(Firebase.auth.currentUser?.email)

    private fun flushPendingAuthJs(wv: WebView) {
        val pending = pendingAuthJs ?: return
        pendingAuthJs = null
        wv.post {
            wv.evaluateJavascript(pending, null)
        }
    }

    internal fun evaluateJs(script: String) {
        runOnUiThread {
            val wv = webViewReference
            if (wv != null) {
                wv.post { wv.evaluateJavascript(script, null) }
            } else {
                pendingAuthJs = script
            }
        }
    }

    fun openFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?
    ): Boolean {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = filePathCallback

        return try {
            val acceptTypes = fileChooserParams?.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
            val wantsImage = acceptTypes.contains("image") || acceptTypes.contains(".png") || acceptTypes.contains(".jpg") || acceptTypes.contains(".webp") || acceptTypes.contains(".gif")
            val wantsVideo = acceptTypes.contains("video") || acceptTypes.contains(".mp4") || acceptTypes.contains(".webm")
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                when {
                    wantsImage && wantsVideo -> {
                        type = "*/*"
                        putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            arrayOf("image/png", "image/jpeg", "image/webp", "image/gif", "video/mp4", "video/webm")
                        )
                    }
                    wantsImage -> {
                        type = "image/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp", "image/gif"))
                    }
                    wantsVideo -> {
                        type = "video/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4", "video/webm"))
                    }
                    else -> {
                        type = "*/*"
                        putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            arrayOf(
                                "application/octet-stream",
                                "application/zip",
                                "application/vnd.android.package-archive",
                                "application/x-zip-compressed",
                                "*/*"
                            )
                        )
                    }
                }
                if (fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            val title = when {
                wantsImage && wantsVideo -> "اختر صور وفيديوهات المعاينة"
                wantsImage -> "اختر صور المعاينة"
                wantsVideo -> "اختر فيديو المعاينة"
                else -> "اختر ملف الثيم"
            }
            fileChooserLauncher.launch(Intent.createChooser(intent, title))
            true
        } catch (e: Exception) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            false
        }
    }

    fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    internal fun resolveWebClientId(): String {
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val fromRes = if (resId != 0) runCatching { getString(resId).trim() }.getOrDefault("") else ""
        if (fromRes.isNotEmpty()) return fromRes
        return BuildConfig.DEFAULT_WEB_CLIENT_ID.trim()
    }

    fun restoreAdminSessionOnStartup() {
        val currentUser = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (currentUser == null) {
            lastVerifiedUid = null
            inMemoryGithubToken = null
            currentUserIsAdmin = false
            currentUserIsPublisher = false
            currentUserBlocked = false
            currentUserMuted = false
            detachCommunityListeners()
            sendAuthIdleToJs()
            sendUserSessionSignedOutToJs()
            evaluateJs("window.onNotificationsUpdated?.({ok:true,unreadCount:0,items:[]});")
            return
        }
        verifyAndRestoreUserSession(currentUser)
    }

    private fun verifyAndRestoreUserSession(user: FirebaseUser) {
        val email = user.email?.trim()?.lowercase().orEmpty()
        sendAuthRestoringToJs(email)

        lifecycleScope.launch {
            try {
                // Force-refresh ID token if online, fallback to cached token if offline
                runCatching { user.getIdToken(isOnline()).await() }
                verifyAdminAndLoadToken(user, allowInteractivePromptError = false, mode = "restore")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("ThemeHubAuth", "Restore session failed", e)
                // Do NOT call signOut() on error; keep session and surface real reason
                sendAuthErrorToJs(AuthErrorFormatter.format(e))
            }
        }
    }

    fun startGoogleSignInFlow(mode: String = "admin") {
        if (!isOnline()) {
            val netErr = "خطأ اتصال [network-unavailable]: لا يوجد اتصال بالإنترنت، يرجى التحقق من الشبكة."
            if (mode == "admin") sendAuthErrorToJs(netErr)
            else sendUserAuthErrorToJs(netErr)
            return
        }

        activeSignInMode = mode
        isInteractiveSignInRunning = true
        lifecycleScope.launch {
            var authCallbackDispatched = false
            try {
                if (FirebaseApp.getApps(this@MainActivity).isEmpty()) {
                    (application as? ThemeHubApplication)?.ensureFirebaseInitialized()
                        ?: FirebaseApp.initializeApp(this@MainActivity)
                }

                val serverClientId = resolveWebClientId()
                if (serverClientId.isEmpty()) {
                    authCallbackDispatched = true
                    val err = "خطأ في الإعداد [missing-web-client-id]: لم يتم العثور على default_web_client_id من google-services.json"
                    if (mode == "admin") sendAuthErrorToJs(err) else sendUserAuthErrorToJs(err)
                    return@launch
                }

                val credentialManager = CredentialManager.create(this@MainActivity)
                val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = serverClientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

                val result = credentialManager.getCredential(this@MainActivity, request)
                val credential = result.credential

                if (credential !is CustomCredential || credential.type != TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    authCallbackDispatched = true
                    val err = "فشل تسجيل الدخول [unsupported-credential]: نوع الاعتماد غير مدعوم (${credential.type})"
                    if (mode == "admin") sendAuthErrorToJs(err) else sendUserAuthErrorToJs(err)
                    return@launch
                }

                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)

                // STEP 1: Execute Firebase signInWithCredential FIRST (persisted locally by Firebase Auth)
                val authResult = Firebase.auth.signInWithCredential(authCredential).await()
                val user = authResult.user
                if (user == null) {
                    authCallbackDispatched = true
                    val err = "فشل تسجيل الدخول [auth/null-user]: اكتمل التحقق دون إرجاع حساب مستخدم."
                    if (mode == "admin") sendAuthErrorToJs(err) else sendUserAuthErrorToJs(err)
                    return@launch
                }

                runCatching { user.getIdToken(true).await() }
                authCallbackDispatched = true
                verifyAdminAndLoadToken(user, allowInteractivePromptError = (mode == "admin"), mode = mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("ThemeHubAuth", "Google Sign-In failed", e)
                // NEVER call signOut() on error; only signOutAdmin() calls signOut()
                authCallbackDispatched = true
                val formatted = AuthErrorFormatter.format(e)
                if (mode == "admin") sendAuthErrorToJs(formatted) else sendUserAuthErrorToJs(formatted)
            } finally {
                isInteractiveSignInRunning = false
                if (!authCallbackDispatched) {
                    val err = "انتهت عملية تسجيل الدخول بشكل غير متوقع دون استجابة (unknown-auth-termination)"
                    if (mode == "admin") sendAuthErrorToJs(err) else sendUserAuthErrorToJs(err)
                }
            }
        }
    }

    private suspend fun verifyAdminAndLoadToken(
        user: FirebaseUser,
        allowInteractivePromptError: Boolean,
        mode: String = "restore"
    ) {
        val rawEmail = user.email?.trim() ?: ""
        val userEmail = rawEmail.lowercase()
        val isOwner = isOwnerEmail(userEmail)
        val fallbackName = if (isOwner) "المالك (${userEmail.substringBefore("@")})" else (userEmail.ifBlank { "مستخدم Theme Hub" })
        val name = user.displayName?.takeIf { it.isNotBlank() } ?: fallbackName
        val photo = user.photoUrl?.toString() ?: ""

        val firestore = Firebase.firestore

        // 1. Check whether user is in admins/{email} allowlist (strictly email-based, NO role field)
        var isAllowlistedAdmin = isOwner
        var adminCheckError: Throwable? = null
        if (!isOwner && userEmail.isNotEmpty() && user.isEmailVerified) {
            try {
                val lowerDoc = firestore.collection("admins").document(userEmail).get().await()
                isAllowlistedAdmin = when {
                    lowerDoc.exists() -> true
                    rawEmail != userEmail -> firestore.collection("admins").document(rawEmail).get().await().exists()
                    else -> false
                }
            } catch (e: Throwable) {
                adminCheckError = e
            }
        }

        // 2. Load or initialize community user profile in users/{uid} (also auto-claims @username)
        val profile = communityManager.ensureUserProfileInitialized(user, isOwner, isAllowlistedAdmin)
        currentUserBlocked = profile.blocked
        currentUserMuted = profile.muted
        currentUserIsPublisher = profile.publisher
        currentUserIsAdmin = profile.isAdmin

        // Always publish community user session so browsing/rating/commenting/messaging work
        lastVerifiedUid = user.uid
        sendUserSessionToJs(uid = user.uid, profile = profile)
        communityManager.startUnreadMessagesListener(user.uid)
        communityManager.startNotificationsListener(user.uid)
        communityManager.startPendingSubmissionsBadgeListener(profile.isAdmin)

        // 3. Handle Admin Workspace authorization cleanly
        if (!profile.isAdmin && !isAllowlistedAdmin) {
            inMemoryGithubToken = null
            if (mode == "admin" || allowInteractivePromptError) {
                if (userEmail.isEmpty()) {
                    sendAuthErrorToJs("هذا الحساب غير معتمد، تواصل مع المالك [auth/missing-email]: لا يحتوي الحساب على بريد إلكتروني.")
                } else if (!user.isEmailVerified) {
                    sendAuthErrorToJs(
                        errorMessage = "هذا الحساب غير معتمد، تواصل مع المالك [auth/email-not-verified]: يجب توثيق البريد الإلكتروني ($userEmail) أولاً.",
                        errorCode = "auth/email-not-verified"
                    )
                } else if (adminCheckError != null) {
                    sendAuthErrorToJs(AuthErrorFormatter.format(adminCheckError))
                } else {
                    sendAuthErrorToJs(
                        errorMessage = "هذا الحساب غير معتمد، تواصل مع المالك ($userEmail غير موجود في admins/$userEmail)",
                        errorCode = "not-approved-admin"
                    )
                }
            } else {
                sendAuthIdleToJs()
            }
            return
        }

        if (profile.blocked) {
            inMemoryGithubToken = null
            if (mode == "admin" || allowInteractivePromptError) {
                sendAuthErrorToJs("تم حظر هذا الحساب في المجتمع", errorCode = "account-blocked")
            } else {
                sendAuthIdleToJs()
            }
            return
        }

        try {
            // Personal token (per admin) overrides the shared one when present
            var personalToken = ""
            try {
                val personalDoc = firestore.collection("adminTokens").document(user.uid).get().await()
                if (personalDoc.exists()) personalToken = personalDoc.getString("token")?.trim() ?: ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                personalToken = ""
            }
            val githubToken = if (personalToken.isNotBlank()) personalToken else {
                val configDoc = firestore.collection("config").document("github").get().await()
                if (configDoc.exists()) (configDoc.getString("token")?.trim() ?: "") else ""
            }
            inMemoryGithubToken = githubToken
            ThemeUploadService.setGithubToken(githubToken)
            evaluateJs("window.__personalTokenActive = ${personalToken.isNotBlank()};")
            sendAuthSuccessToJs(
                token = githubToken,
                name = profile.displayName,
                email = userEmail,
                photo = profile.photoURL,
                isOwner = isOwner || isAllowlistedAdmin,
                hasToken = githubToken.isNotBlank()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            inMemoryGithubToken = null
            val formatted = AuthErrorFormatter.format(e)
            if (mode == "admin" || allowInteractivePromptError) {
                sendAuthErrorToJs(formatted)
            } else {
                sendToastToJs(formatted)
                sendAuthIdleToJs()
            }
        }
    }

    fun saveGithubTokenToFirestore(token: String) {
        val user = Firebase.auth.currentUser
        val userEmail = user?.email?.trim()?.lowercase() ?: ""
        if (!(isOwnerEmail(userEmail) || currentUserIsAdmin)) {
            sendToastToJs("غير مصرح: حفظ التوكن متاح للمشرفين المعتمدين فقط")
            return
        }

        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            sendToastToJs("يرجى إدخال رمز توكن صالح")
            return
        }

        val data = hashMapOf<String, Any>(
            "token" to cleanToken,
            "updatedAt" to System.currentTimeMillis(),
            "updatedBy" to userEmail
        )

        Firebase.firestore.collection("config").document("github")
            .set(data)
            .addOnSuccessListener {
                inMemoryGithubToken = cleanToken
                ThemeUploadService.setGithubToken(cleanToken)
                sendToastToJs("تم حفظ توكن GitHub بنجاح في Firebase")
                val jsToken = JSONObject.quote(cleanToken)
                evaluateJs("window.onGithubTokenSaved?.(true, $jsToken);")
            }
            .addOnFailureListener { e ->
                val detailed = AuthErrorFormatter.format(e)
                sendToastToJs("فشل حفظ التوكن: $detailed")
                val jsErr = JSONObject.quote(detailed)
                evaluateJs("window.onGithubTokenSaved?.(false, $jsErr);")
            }
    }

    fun savePersonalGithubToken(token: String) {
        val user = Firebase.auth.currentUser
        if (user == null || !currentUserIsAdmin) {
            sendToastToJs("غير مصرح: التوكن الشخصي متاح للمشرفين المعتمدين فقط")
            return
        }
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) {
            sendToastToJs("يرجى إدخال رمز توكن صالح")
            return
        }
        val data = hashMapOf<String, Any>(
            "token" to cleanToken,
            "updatedAt" to System.currentTimeMillis(),
            "email" to (user.email?.trim()?.lowercase() ?: "")
        )
        Firebase.firestore.collection("adminTokens").document(user.uid)
            .set(data)
            .addOnSuccessListener {
                inMemoryGithubToken = cleanToken
                ThemeUploadService.setGithubToken(cleanToken)
                sendToastToJs("تم حفظ توكنك الشخصي")
                val jsToken = JSONObject.quote(cleanToken)
                evaluateJs("window.onPersonalTokenSaved?.(true, $jsToken);")
            }
            .addOnFailureListener { e ->
                val detailed = AuthErrorFormatter.format(e)
                sendToastToJs("فشل حفظ التوكن الشخصي: $detailed")
                val jsErr = JSONObject.quote(detailed)
                evaluateJs("window.onPersonalTokenSaved?.(false, $jsErr);")
            }
    }

    fun clearPersonalGithubToken() {
        val user = Firebase.auth.currentUser
        if (user == null || !currentUserIsAdmin) {
            sendToastToJs("غير مصرح: التوكن الشخصي متاح للمشرفين المعتمدين فقط")
            return
        }
        Firebase.firestore.collection("adminTokens").document(user.uid)
            .delete()
            .addOnSuccessListener {
                inMemoryGithubToken = null
                ThemeUploadService.setGithubToken(null)
                sendToastToJs("تم حذف توكنك الشخصي. سجّل الخروج ثم ادخل من جديد ليُستخدم التوكن المشترك")
                evaluateJs("window.onPersonalTokenCleared?.(true, \"\");")
            }
            .addOnFailureListener { e ->
                val detailed = AuthErrorFormatter.format(e)
                sendToastToJs("فشل حذف التوكن الشخصي: $detailed")
                val jsErr = JSONObject.quote(detailed)
                evaluateJs("window.onPersonalTokenCleared?.(false, $jsErr);")
            }
    }

    fun fetchApprovedAdminsList() {
        val currentEmail = Firebase.auth.currentUser?.email?.trim()?.lowercase() ?: ""
        if (!(isOwnerEmail(currentEmail) || currentUserIsAdmin)) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("error", "غير مصرح [owner-only]: إدارة المشرفين متاحة للمشرفين المعتمدين فقط")
            }.toString()
            evaluateJs("window.onAdminsListLoaded?.($errJson);")
            return
        }

        lifecycleScope.launch {
            try {
                val snap = Firebase.firestore.collection("admins").get().await()
                val arr = org.json.JSONArray()
                snap.documents
                    .sortedBy { it.id.lowercase() }
                    .forEach { doc ->
                        val docEmail = (doc.getString("email") ?: doc.id).trim().lowercase()
                        arr.put(JSONObject().apply {
                            put("docId", doc.id)
                            put("email", docEmail)
                            put("addedBy", doc.getString("addedBy") ?: "")
                            put("addedAt", doc.getLong("addedAt") ?: 0L)
                        })
                    }
                val okJson = JSONObject().apply {
                    put("ok", true)
                    put("admins", arr)
                }.toString()
                evaluateJs("window.onAdminsListLoaded?.($okJson);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                evaluateJs("window.onAdminsListLoaded?.($errJson);")
            }
        }
    }

    fun addApprovedAdmin(rawEmail: String) {
        val ownerEmail = Firebase.auth.currentUser?.email?.trim()?.lowercase() ?: ""
        if (!(isOwnerEmail(ownerEmail) || currentUserIsAdmin)) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("action", "add")
                put("error", "غير مصرح [owner-only]: إضافة المشرفين متاحة للمشرفين المعتمدين فقط")
            }.toString()
            evaluateJs("window.onAdminMemberMutation?.($errJson);")
            return
        }

        val cleanEmail = rawEmail.replace("\\s+".toRegex(), "").lowercase()
        if (cleanEmail.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("action", "add")
                put("error", "صيغة البريد الإلكتروني غير صالحة [invalid-email]: يرجى إدخال بريد إلكتروني صحيح")
            }.toString()
            evaluateJs("window.onAdminMemberMutation?.($errJson);")
            return
        }

        lifecycleScope.launch {
            try {
                val payload = hashMapOf<String, Any>(
                    "email" to cleanEmail,
                    "addedBy" to ownerEmail,
                    "addedAt" to System.currentTimeMillis()
                )
                Firebase.firestore.collection("admins").document(cleanEmail).set(payload).await()
                communityManager.writeAuditLog("add_admin", "admin", cleanEmail, "تمت إضافة مشرف")
                val okJson = JSONObject().apply {
                    put("ok", true)
                    put("action", "add")
                    put("email", cleanEmail)
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($okJson);")
                fetchApprovedAdminsList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("action", "add")
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($errJson);")
            }
        }
    }

    fun removeApprovedAdmin(rawEmail: String) {
        val ownerEmail = Firebase.auth.currentUser?.email?.trim()?.lowercase() ?: ""
        if (!(isOwnerEmail(ownerEmail) || currentUserIsAdmin)) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("action", "remove")
                put("error", "غير مصرح [owner-only]: حذف المشرفين متاح للمشرفين المعتمدين فقط")
            }.toString()
            evaluateJs("window.onAdminMemberMutation?.($errJson);")
            return
        }

        val cleanEmail = rawEmail.replace("\\s+".toRegex(), "").lowercase()
        if (isOwnerEmail(cleanEmail)) {
            val errJson = JSONObject().apply {
                put("ok", false)
                put("action", "remove")
                put("error", "لا يمكن حذف حساب المالك الأساسي [owner-protected]")
            }.toString()
            evaluateJs("window.onAdminMemberMutation?.($errJson);")
            return
        }

        lifecycleScope.launch {
            try {
                Firebase.firestore.collection("admins").document(cleanEmail).delete().await()
                communityManager.writeAuditLog("remove_admin", "admin", cleanEmail, "تم حذف مشرف")
                val okJson = JSONObject().apply {
                    put("ok", true)
                    put("action", "remove")
                    put("email", cleanEmail)
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($okJson);")
                fetchApprovedAdminsList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errJson = JSONObject().apply {
                    put("ok", false)
                    put("action", "remove")
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                evaluateJs("window.onAdminMemberMutation?.($errJson);")
            }
        }
    }

    // ONLY place where Firebase.auth.signOut() is ever called
    fun signOutAdmin() {
        inMemoryGithubToken = null
        ThemeUploadService.setGithubToken(null)
        lastVerifiedUid = null
        currentUserIsAdmin = false
        currentUserIsPublisher = false
        currentUserBlocked = false
        currentUserMuted = false
        detachCommunityListeners()
        lifecycleScope.launch(NonCancellable) {
            try {
                val credentialManager = CredentialManager.create(this@MainActivity)
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
            }
            Firebase.auth.signOut()
            sendAuthIdleToJs()
            sendUserSessionSignedOutToJs()
            evaluateJs("window.onNotificationsUpdated?.({ok:true,unreadCount:0,items:[]});")
        }
    }

    private fun sendUserSessionToJs(uid: String, profile: ProfileInitResult) {
        val payload = JSONObject().apply {
            put("authenticated", true)
            put("uid", uid)
            put("displayName", profile.displayName)
            put("username", profile.username)
            put("usernameChosen", profile.usernameChosen)
            put("photoURL", profile.photoURL)
            put("bannerURL", profile.bannerURL)
            put("bio", profile.bio)
            put("links", JSONArray(profile.links))
            put("profileAccent", profile.profileAccent)
            put("profileGradient", profile.profileGradient)
            put("hideCommentsCount", profile.hideCommentsCount)
            put("badges", JSONArray(profile.badges))
            put("frame", profile.frame)
            put("frameId", profile.frameId)
            put("frameAssigned", JSONArray(profile.frameAssigned))
            put("customTitle", profile.customTitle)
            put("title", profile.title)
            put("verified", profile.verified)
            put("badgeType", profile.badgeType)
            put("nameColor", profile.nameColor)
            put("nameGradient", profile.nameGradient)
            put("nameFont", profile.nameFont)
            put("createdAt", profile.createdAt)
            put("blocked", profile.blocked)
            put("blockedUntil", profile.blockedUntil)
            put("muted", profile.muted)
            put("mutedUntil", profile.mutedUntil)
            put("clubRulesAccepted", profile.clubRulesAccepted)
            put("publisher", profile.publisher)
            put("isAdmin", profile.isAdmin)
            put("isOwner", profile.isOwner)
            put("profileError", profile.error)
        }.toString()
        evaluateJs("window.onUserSessionChanged?.($payload);")
    }

    private fun sendUserSessionSignedOutToJs() {
        val payload = JSONObject().apply {
            put("authenticated", false)
            put("uid", "")
            put("displayName", "")
            put("username", "")
            put("usernameChosen", true)
            put("photoURL", "")
            put("bannerURL", "")
            put("bio", "")
            put("links", JSONArray())
            put("profileAccent", "")
            put("profileGradient", "")
            put("hideCommentsCount", false)
            put("badges", JSONArray())
            put("frame", "")
            put("frameId", "")
            put("frameAssigned", JSONArray())
            put("customTitle", "")
            put("title", "")
            put("verified", false)
            put("badgeType", "")
            put("nameColor", "")
            put("nameGradient", "")
            put("nameFont", "")
            put("blocked", false)
            put("blockedUntil", 0L)
            put("muted", false)
            put("mutedUntil", 0L)
            put("clubRulesAccepted", false)
            put("publisher", false)
            put("isAdmin", false)
            put("isOwner", false)
            put("profileError", "")
        }.toString()
        evaluateJs("window.onUserSessionChanged?.($payload);")
    }

    private fun sendUserAuthErrorToJs(errorMessage: String) {
        val payload = JSONObject().apply {
            put("ok", false)
            put("error", errorMessage)
        }.toString()
        evaluateJs("window.onUserSignInFailed?.($payload);")
    }

    internal fun fetchAllThemeRatingsSummary() {
        lifecycleScope.launch {
            try {
                val snap = Firebase.firestore.collection("themes").limit(200).get().await()
                val mapObj = JSONObject()
                val metaObj = JSONObject()
                val publisherUids = snap.documents.mapNotNull { it.getString("publisherUid")?.takeIf { u -> u.isNotBlank() } }.distinct()
                val publisherSummaries = if (publisherUids.isNotEmpty()) communityManager.fetchUserSummaries(publisherUids) else emptyMap()
                snap.documents.forEach { doc ->
                    val avg = doc.getDouble("ratingAvg")
                    val count = doc.getLong("ratingCount")?.toInt() ?: 0
                    if (avg != null) {
                        mapObj.put(doc.id, JSONObject().apply {
                            put("ratingAvg", avg)
                            put("ratingCount", count)
                        })
                    }
                    val pinned = doc.getBoolean("pinned") ?: false
                    val hidden = doc.getBoolean("hidden") ?: false
                    val titleOverride = doc.getString("titleOverride") ?: ""
                    val categoryOverride = doc.getString("categoryOverride") ?: ""
                    val publisherUid = doc.getString("publisherUid") ?: ""
                    val publisherName = doc.getString("publisherName") ?: ""
                    val publisherUsername = doc.getString("publisherUsername") ?: ""
                    val pubSummary = publisherSummaries[publisherUid]
                    if (pinned || hidden || titleOverride.isNotEmpty() || categoryOverride.isNotEmpty() || publisherUid.isNotEmpty()) {
                        metaObj.put(doc.id, JSONObject().apply {
                            put("pinned", pinned)
                            put("hidden", hidden)
                            put("titleOverride", titleOverride)
                            put("categoryOverride", categoryOverride)
                            put("publisherUid", publisherUid)
                            put("publisherName", pubSummary?.optString("displayName")?.ifBlank { null } ?: publisherName)
                            put("publisherUsername", pubSummary?.optString("username")?.ifBlank { null } ?: publisherUsername)
                            put("publisherPhoto", pubSummary?.optString("photoURL") ?: "")
                            put("publisherFrameId", pubSummary?.optString("frameId") ?: "")
                            put("publisherVerified", pubSummary?.optBoolean("verified") ?: false)
                            put("publisherBadgeType", pubSummary?.optString("badgeType") ?: "")
                            put("publisherIsAdmin", pubSummary?.optBoolean("isAdmin") ?: false)
                            put("publisherIsOwner", pubSummary?.optBoolean("isOwner") ?: false)
                            put("publisherIsPublisher", pubSummary?.optBoolean("publisher") ?: false)
                            put("publisherTitle", pubSummary?.optString("title") ?: "")
                            put("publisherNameColor", pubSummary?.optString("nameColor") ?: "")
                            put("publisherNameGradient", pubSummary?.optString("nameGradient") ?: "")
                            put("publisherNameFont", pubSummary?.optString("nameFont") ?: "")
                        })
                    }
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("ratings", mapObj)
                    put("themesMeta", metaObj)
                }.toString()
                evaluateJs("window.onAllThemeRatingsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val formatted = AuthErrorFormatter.format(e)
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("error", formatted)
                }.toString()
                evaluateJs("window.onAllThemeRatingsLoaded?.($payload);")
            }
        }
    }

    internal fun fetchThemeRatingDetails(themeId: String) {
        lifecycleScope.launch {
            try {
                val db = Firebase.firestore
                val themeSnap = db.collection("themes").document(themeId).get().await()
                val uid = Firebase.auth.currentUser?.uid
                var myStars = 0
                if (!uid.isNullOrBlank()) {
                    val rSnap = db.collection("themes").document(themeId).collection("ratings").document(uid).get().await()
                    if (rSnap.exists()) myStars = rSnap.getLong("stars")?.toInt() ?: 0
                }
                val avg = if (themeSnap.exists()) (themeSnap.getDouble("ratingAvg") ?: 0.0) else 0.0
                val count = if (themeSnap.exists()) (themeSnap.getLong("ratingCount")?.toInt() ?: 0) else 0
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("themeId", themeId)
                    put("ratingAvg", avg)
                    put("ratingCount", count)
                    put("myStars", myStars)
                }.toString()
                evaluateJs("window.onThemeRatingInfoLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("themeId", themeId)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                evaluateJs("window.onThemeRatingInfoLoaded?.($payload);")
            }
        }
    }

    fun startResumableDownload(
        key: String,
        url: String,
        filename: String,
        themeTitle: String,
        brand: String
    ) {
        if (url.isBlank()) {
            sendToastToJs("ملف الثيم غير متوفر حالياً")
            return
        }
        if (!isOnline()) {
            val offlineJson = JSONObject().apply {
                put("key", key)
                put("status", "error")
                put("error", "لا يوجد اتصال بالإنترنت، يرجى التحقق من الشبكة")
            }.toString()
            evaluateJs("window.onThemeDownloadProgress?.($offlineJson);")
            if (key == "app_update_apk") {
                evaluateJs("window.onAppUpdateDownloadProgress?.($offlineJson);")
            }
            sendToastToJs("لا يوجد اتصال بالإنترنت، يرجى التحقق من الشبكة")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val job = lifecycleScope.launch {
            try {
                downloadHelper.downloadThemeResumable(
                    key = key,
                    url = url,
                    filename = filename,
                    themeTitle = themeTitle,
                    brand = brand
                ) { progressJson ->
                    evaluateJs("window.onThemeDownloadProgress?.($progressJson);")
                    if (key == "app_update_apk") {
                        evaluateJs("window.onAppUpdateDownloadProgress?.($progressJson);")
                    }
                }
            } finally {
                downloadHelper.clearJob(key)
            }
        }
        downloadHelper.registerJob(key, job)
    }

    fun openDownloadsFolder() {
        val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(downloadsIntent) }
            .onFailure {
                val filesIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(Intent.createChooser(filesIntent, "مجلد التنزيلات")) }
                    .onFailure {
                        sendToastToJs("افتح تطبيق الملفات ← التنزيلات ← ThemeHub لتثبيت ملف التحديث")
                    }
            }
    }

    fun openExternalBrowserUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) {
            sendToastToJs("رابط غير صالح")
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            sendToastToJs("تعذر فتح المتصفح على هذا الجهاز")
        }
    }

    fun requestCopyToThemesFolder(key: String, brand: String, filename: String, forcePickFolder: Boolean) {
        val (savedUri, _) = downloadHelper.getLastSavedInfo(key)
        if (savedUri == null) {
            sendSafCopyResultToJs(
                success = false,
                actualPath = "",
                message = "يرجى تحميل الثيم إلى Download/ThemeHub أولاً قبل النسخ"
            )
            return
        }

        val existingTree = if (forcePickFolder) null else downloadHelper.getSavedTreeUri(brand)
        if (existingTree == null) {
            pendingSafKey = key
            pendingSafBrand = brand
            pendingSafFilename = filename
            safFolderPickerLauncher.launch(downloadHelper.buildInitialTreeHintUri(brand))
            return
        }

        performCopyToSafTree(key, existingTree, filename)
    }

    private fun performCopyToSafTree(key: String, treeUri: Uri, filename: String) {
        lifecycleScope.launch {
            val res = downloadHelper.copyDownloadedToThemesTree(key, treeUri, filename)
            res.onSuccess { actualPath ->
                sendSafCopyResultToJs(
                    success = true,
                    actualPath = actualPath,
                    message = "تم نسخ الملف بنجاح إلى المسار الفعلي: $actualPath"
                )
            }.onFailure { err ->
                sendSafCopyResultToJs(
                    success = false,
                    actualPath = "",
                    message = "فشل النسخ إلى مجلد الثيمات: ${err.localizedMessage ?: err.message}"
                )
            }
        }
    }

    fun openBrandThemeManager(brand: String): Boolean {
        val pkg = downloadHelper.findThemeManagerPackage(brand)
        if (pkg != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
                return true
            }
        }
        val brandTitle = when (brand.lowercase()) {
            "honor" -> "تطبيق الثيمات (Honor Themes)"
            "huawei" -> "Huawei Themes"
            "xiaomi" -> "تطبيق الثيمات (MIUI/HyperOS)"
            else -> "مدير الثيمات"
        }
        sendToastToJs("تطبيق $brandTitle غير مثبت أو يتعذر فتحه تلقائياً على هذا الجهاز")
        return false
    }

    private fun sendAuthRestoringToJs(email: String) {
        val payload = JSONObject().apply {
            put("status", "restoring")
            put("authenticated", false)
            put("email", email)
        }.toString()
        evaluateJs("window.onAndroidAuthResult?.($payload);")
    }

    private fun sendAuthIdleToJs() {
        val payload = JSONObject().apply {
            put("status", "idle")
            put("authenticated", false)
        }.toString()
        evaluateJs("window.onAndroidAuthResult?.($payload);")
    }

    fun sendAuthSuccessToJs(
        token: String,
        name: String,
        email: String,
        photo: String,
        isOwner: Boolean = false,
        hasToken: Boolean = true
    ) {
        val payload = JSONObject().apply {
            put("status", "success")
            put("authenticated", true)
            put("step", "verified")
            put("token", token)
            put("name", name)
            put("email", email)
            put("photo", photo)
            put("isOwner", isOwner)
            put("hasToken", hasToken)
        }.toString()
        evaluateJs("window.onAndroidAuthResult?.($payload);")
    }

    fun sendAuthErrorToJs(errorMessage: String, errorCode: String = "") {
        val payload = JSONObject().apply {
            put("status", "error")
            put("authenticated", false)
            put("errorCode", errorCode)
            put("errorMessage", errorMessage)
        }.toString()
        evaluateJs("window.onAndroidAuthResult?.($payload);")
    }

    private fun sendSafCopyResultToJs(success: Boolean, actualPath: String, message: String) {
        val payload = JSONObject().apply {
            put("success", success)
            put("actualPath", actualPath)
            put("message", message)
        }.toString()
        evaluateJs("window.onSafFolderCopyResult?.($payload);")
        if (message.isNotBlank()) sendToastToJs(message)
    }

    fun sendToastToJs(message: String) {
        val jsMsg = JSONObject.quote(message)
        webViewReference?.post {
            webViewReference?.evaluateJavascript("window.showToast?.($jsMsg);", null)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    internal fun beginUploadTempFile(uploadId: String, fileKey: String): Boolean {
        val file = ThemeUploadService.safeStageFile(this, uploadId, fileKey) ?: return false
        return runCatching {
            if (file.exists()) file.delete()
            file.createNewFile()
            true
        }.getOrDefault(false)
    }

    internal fun appendUploadTempChunk(uploadId: String, fileKey: String, base64Chunk: String): Boolean {
        val file = ThemeUploadService.safeStageFile(this, uploadId, fileKey) ?: return false
        return runCatching {
            val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
            FileOutputStream(file, true).use { it.write(bytes) }
            true
        }.getOrDefault(false)
    }

    internal fun startBackgroundThemePublish(jobJson: String): Boolean {
        if (!currentUserIsAdmin || inMemoryGithubToken.isNullOrBlank()) {
            sendToastToJs("يجب تسجيل الدخول كمشرف معتمد مع توفر توكن GitHub أولاً")
            return false
        }
        val parsed = runCatching { JSONObject(jobJson) }.getOrNull() ?: return false
        val uploadId = parsed.optString("uploadId", "").trim()
        val stageDir = ThemeUploadService.stageDirFor(this, uploadId) ?: return false
        val jobFile = File(stageDir, "job.json")
        runCatching { jobFile.writeText(jobJson, Charsets.UTF_8) }.getOrElse { return false }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        ThemeUploadService.setGithubToken(inMemoryGithubToken)
        val serviceIntent = Intent(this, ThemeUploadService::class.java).apply {
            action = ThemeUploadService.ACTION_START_UPLOAD
            putExtra(ThemeUploadService.EXTRA_JOB_JSON_PATH, jobFile.absolutePath)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        return true
    }

    internal fun cancelBackgroundThemePublish() {
        val cancelIntent = Intent(this, ThemeUploadService::class.java).apply {
            action = ThemeUploadService.ACTION_CANCEL_UPLOAD
        }
        startService(cancelIntent)
    }

    private val studioTranslateHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    internal fun translateStudioText(text: String, fromLang: String, toLang: String, requestId: String) {
        val trimmed = text.trim().take(1200)
        val src = if (fromLang == "en") "en" else "ar"
        val dst = if (toLang == "ar") "ar" else "en"
        if (trimmed.isEmpty()) {
            val emptyRes = JSONObject().apply {
                put("ok", false)
                put("requestId", requestId)
                put("error", "النص فارغ")
            }.toString()
            evaluateJs("window.onStudioTranslationResult?.($emptyRes);")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val q = URLEncoder.encode(trimmed.take(500), "UTF-8")
                val url = "https://api.mymemory.translated.net/get?q=$q&langpair=$src%7C$dst"
                val req = Request.Builder().url(url).get().build()
                studioTranslateHttpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || raw.isBlank()) {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val obj = JSONObject(raw)
                    val translated = obj.optJSONObject("responseData")?.optString("translatedText", "").orEmpty().trim()
                    if (translated.isBlank() || translated.startsWith("MYMEMORY WARNING", ignoreCase = true)) {
                        throw IllegalStateException("خدمة الترجمة مشغولة")
                    }
                    val payload = JSONObject().apply {
                        put("ok", true)
                        put("requestId", requestId)
                        put("fromLang", src)
                        put("toLang", dst)
                        put("translatedText", translated)
                    }.toString()
                    evaluateJs("window.onStudioTranslationResult?.($payload);")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("requestId", requestId)
                    put("fromLang", src)
                    put("toLang", dst)
                    put("error", e.message ?: "تعذرت الترجمة عبر الشبكة")
                }.toString()
                evaluateJs("window.onStudioTranslationResult?.($payload);")
            }
        }
    }
}

class AndroidWebAppInterface(private val activity: MainActivity) {

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        activity.runOnUiThread {
            runCatching {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("ThemeHub", text))
            }
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun openThemeManager(brand: String): Boolean {
        val pkg = activity.downloadHelper.findThemeManagerPackage(brand)
        activity.runOnUiThread {
            activity.openBrandThemeManager(brand)
        }
        return pkg != null
    }

    @JavascriptInterface
    fun launchThemeManager(brand: String) {
        openThemeManager(brand)
    }

    @JavascriptInterface
    fun restoreAdminSession() {
        activity.runOnUiThread { activity.restoreAdminSessionOnStartup() }
    }

    @JavascriptInterface
    fun startUserGoogleSignIn() {
        activity.runOnUiThread { activity.startGoogleSignInFlow("user") }
    }

    @JavascriptInterface
    fun startGoogleSignIn() {
        activity.runOnUiThread { activity.startGoogleSignInFlow("admin") }
    }

    @JavascriptInterface
    fun adminSignOut() {
        activity.runOnUiThread { activity.signOutAdmin() }
    }

    @JavascriptInterface
    fun fetchUserProfile(uid: String) {
        activity.runOnUiThread { activity.communityManager.fetchUserProfile(uid) }
    }

    @JavascriptInterface
    fun togglePersonalBlockUser(targetUid: String, blocked: Boolean) {
        activity.runOnUiThread { activity.communityManager.togglePersonalBlockUser(targetUid, blocked) }
    }

    @JavascriptInterface
    fun toggleBlockUser(uid: String, blocked: Boolean) {
        activity.runOnUiThread { activity.communityManager.adminSetUserBlocked(uid, blocked) }
    }

    @JavascriptInterface
    fun adminSetUserPublisher(uid: String, publisher: Boolean) {
        activity.runOnUiThread { activity.communityManager.adminSetUserPublisher(uid, publisher) }
    }

    @JavascriptInterface
    fun saveGithubToken(token: String) {
        activity.runOnUiThread { activity.saveGithubTokenToFirestore(token) }
    }

    @JavascriptInterface
    fun savePersonalGithubToken(token: String) {
        activity.runOnUiThread { activity.savePersonalGithubToken(token) }
    }

    @JavascriptInterface
    fun clearPersonalGithubToken() {
        activity.runOnUiThread { activity.clearPersonalGithubToken() }
    }

    @JavascriptInterface
    fun fetchApprovedAdmins() {
        activity.runOnUiThread { activity.fetchApprovedAdminsList() }
    }

    @JavascriptInterface
    fun addApprovedAdmin(email: String) {
        activity.runOnUiThread { activity.addApprovedAdmin(email) }
    }

    @JavascriptInterface
    fun removeApprovedAdmin(email: String) {
        activity.runOnUiThread { activity.removeApprovedAdmin(email) }
    }

    @JavascriptInterface
    fun downloadThemeFile(key: String, url: String, filename: String, themeTitle: String, brand: String) {
        activity.runOnUiThread {
            activity.startResumableDownload(key, url, filename, themeTitle, brand)
        }
    }

    @JavascriptInterface
    fun pauseThemeDownload(key: String) {
        activity.downloadHelper.pauseDownload(key)
    }

    @JavascriptInterface
    fun copyThemeToFolder(key: String, brand: String, filename: String, forcePickFolder: Boolean) {
        activity.runOnUiThread {
            activity.requestCopyToThemesFolder(key, brand, filename, forcePickFolder)
        }
    }

    @JavascriptInterface
    fun getThemeEnvInfo(brand: String, key: String): String {
        return activity.downloadHelper.getThemeEnvironmentInfo(brand, key).toString()
    }

    @JavascriptInterface
    fun getAppVersionInfo(): String {
        return JSONObject().apply {
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("applicationId", BuildConfig.APPLICATION_ID)
        }.toString()
    }

    @JavascriptInterface
    fun getDeviceLocaleInfo(): String {
        val locale = ConfigurationCompat.getLocales(activity.resources.configuration)[0]
            ?: Locale.getDefault()
        return JSONObject().apply {
            put("languageTag", locale.toLanguageTag())
            put("language", locale.language)
            put("country", locale.country)
        }.toString()
    }

    @JavascriptInterface
    fun downloadAppUpdateApk(url: String, filename: String, releaseTitle: String) {
        activity.runOnUiThread {
            activity.startResumableDownload(
                key = "app_update_apk",
                url = url,
                filename = filename,
                themeTitle = releaseTitle,
                brand = "app_update"
            )
        }
    }

    @JavascriptInterface
    fun openDownloadsFolder() {
        activity.runOnUiThread { activity.openDownloadsFolder() }
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        activity.runOnUiThread { activity.openExternalBrowserUrl(url) }
    }

    @JavascriptInterface
    fun shareTheme(title: String, description: String, url: String) {
        activity.runOnUiThread {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, "$title\n$description\n$url")
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "مشاركة الثيم")
            runCatching { activity.startActivity(shareIntent) }
                .onFailure { Toast.makeText(activity, "تعذر فتح قائمة المشاركة", Toast.LENGTH_SHORT).show() }
        }
    }

    @JavascriptInterface
    fun claimInitialUsername(username: String, displayName: String) {
        activity.runOnUiThread {
            activity.communityManager.claimInitialUsername(username, displayName)
        }
    }

    @JavascriptInterface
    fun searchMembers(query: String) {
        activity.runOnUiThread {
            activity.communityManager.searchMembers(query)
        }
    }

    @JavascriptInterface
    fun markNotificationRead(notifId: String) {
        activity.runOnUiThread {
            activity.communityManager.markNotificationRead(notifId)
        }
    }

    @JavascriptInterface
    fun markAllNotificationsRead() {
        activity.runOnUiThread {
            activity.communityManager.markAllNotificationsRead()
        }
    }

    @JavascriptInterface
    fun updateProfileExtended(
        uid: String,
        displayName: String,
        username: String,
        bio: String,
        photoDataUrl: String,
        bannerDataUrl: String,
        extraJson: String
    ) {
        activity.runOnUiThread {
            activity.communityManager.updateMyProfileExtended(
                uid, displayName, username, bio, photoDataUrl, bannerDataUrl, extraJson
            )
        }
    }

    @JavascriptInterface
    fun updateProfileFull(
        uid: String,
        displayName: String,
        username: String,
        bio: String,
        photoDataUrl: String,
        bannerDataUrl: String
    ) {
        activity.runOnUiThread {
            activity.communityManager.updateMyProfile(uid, displayName, username, bio, photoDataUrl, bannerDataUrl)
        }
    }

    @JavascriptInterface
    fun updateProfile(uid: String, name: String, bio: String, photo: String) {
        activity.runOnUiThread {
            val fallbackUname = "user_${uid.take(10).lowercase()}"
            activity.communityManager.updateMyProfile(uid, name, fallbackUname, bio, photo, "")
        }
    }

    @JavascriptInterface
    fun fetchAllThemeRatings() {
        activity.runOnUiThread { activity.fetchAllThemeRatingsSummary() }
    }

    @JavascriptInterface
    fun fetchThemeRatingInfo(themeId: String) {
        activity.runOnUiThread { activity.fetchThemeRatingDetails(themeId) }
    }

    @JavascriptInterface
    fun rateTheme(themeId: String, stars: Int) {
        val user = Firebase.auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onThemeRatedError?.({ok:false,needAuth:true,error:'يجب تسجيل الدخول للتقييم'});")
            return
        }
        if (activity.currentUserBlocked) {
            activity.evaluateJs("window.onThemeRatedError?.({ok:false,error:'حسابك محظور من التقييم'});")
            return
        }
        val uid = user.uid
        if (stars !in 1..5) return
        val firestore = Firebase.firestore
        val ratingRef = firestore.collection("themes").document(themeId).collection("ratings").document(uid)
        val themeRef = firestore.collection("themes").document(themeId)

        firestore.runTransaction { transaction ->
            val ratingSnap = transaction.get(ratingRef)
            val themeSnap = transaction.get(themeRef)
            val oldStars = if (ratingSnap.exists()) (ratingSnap.getLong("stars")?.toInt() ?: 0) else 0

            val currentAvg = if (themeSnap.exists()) (themeSnap.getDouble("ratingAvg") ?: 0.0) else 0.0
            val currentCount = if (themeSnap.exists()) (themeSnap.getLong("ratingCount")?.toInt() ?: 0) else 0

            val newCount: Int
            val newAvg: Double
            if (oldStars == 0) {
                newCount = currentCount + 1
                newAvg = ((currentAvg * currentCount) + stars) / newCount.coerceAtLeast(1)
            } else {
                newCount = currentCount.coerceAtLeast(1)
                newAvg = (((currentAvg * newCount) - oldStars + stars) / newCount).coerceIn(1.0, 5.0)
            }

            transaction.set(ratingRef, hashMapOf("stars" to stars, "updatedAt" to System.currentTimeMillis()))
            transaction.set(
                themeRef,
                hashMapOf(
                    "ratingAvg" to newAvg,
                    "ratingCount" to newCount,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
            Pair(newAvg, newCount)
        }.addOnSuccessListener { (avg, count) ->
            val payload = JSONObject().apply {
                put("ok", true)
                put("themeId", themeId)
                put("myStars", stars)
                put("ratingAvg", avg)
                put("ratingCount", count)
            }.toString()
            activity.evaluateJs("window.onThemeRatedSuccess?.($payload);")
            activity.runOnUiThread {
                Toast.makeText(activity, "تم تسجيل تقييمك ($stars ★)", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener { e ->
            val errJson = JSONObject().apply {
                put("ok", false)
                put("error", AuthErrorFormatter.format(e))
            }.toString()
            activity.evaluateJs("window.onThemeRatedError?.($errJson);")
        }
    }

    @JavascriptInterface
    fun fetchThemeComments(themeId: String, beforeCreatedAt: Long) {
        activity.runOnUiThread { activity.communityManager.fetchThemeCommentsPage(themeId, beforeCreatedAt) }
    }

    @JavascriptInterface
    fun addComment(themeId: String, text: String) {
        activity.runOnUiThread { activity.communityManager.addComment(themeId, text) }
    }

    @JavascriptInterface
    fun deleteComment(themeId: String, commentId: String) {
        activity.runOnUiThread { activity.communityManager.deleteComment(themeId, commentId) }
    }

    @JavascriptInterface
    fun toggleCommentLike(themeId: String, commentId: String) {
        activity.runOnUiThread { activity.communityManager.toggleCommentLike(themeId, commentId) }
    }

    @JavascriptInterface
    fun fetchCommentReplies(themeId: String, commentId: String) {
        activity.runOnUiThread { activity.communityManager.fetchCommentReplies(themeId, commentId) }
    }

    @JavascriptInterface
    fun addCommentReply(themeId: String, commentId: String, text: String) {
        activity.runOnUiThread { activity.communityManager.addCommentReply(themeId, commentId, text) }
    }

    @JavascriptInterface
    fun deleteCommentReply(themeId: String, commentId: String, replyId: String) {
        activity.runOnUiThread { activity.communityManager.deleteCommentReply(themeId, commentId, replyId) }
    }

    @JavascriptInterface
    fun toggleReplyLike(themeId: String, commentId: String, replyId: String) {
        activity.runOnUiThread { activity.communityManager.toggleReplyLike(themeId, commentId, replyId) }
    }

    @JavascriptInterface
    fun reportContent(targetType: String, targetId: String, parentId: String, reason: String) {
        activity.runOnUiThread { activity.communityManager.submitReport(targetType, targetId, parentId, reason) }
    }

    @JavascriptInterface
    fun fetchReportsForAdmin() {
        activity.runOnUiThread { activity.communityManager.fetchReportsForAdmin() }
    }

    @JavascriptInterface
    fun resolveReport(reportId: String, status: String) {
        activity.runOnUiThread { activity.communityManager.resolveReport(reportId, status) }
    }

    @JavascriptInterface
    fun openInbox() {
        activity.runOnUiThread { activity.communityManager.startMemberInboxListener() }
    }

    @JavascriptInterface
    fun closeInbox() {
        activity.runOnUiThread { activity.communityManager.stopMemberInboxListener() }
    }

    @JavascriptInterface
    fun openAdminInbox() {
        activity.runOnUiThread { activity.communityManager.startMemberInboxListener() }
    }

    @JavascriptInterface
    fun closeAdminInbox() {
        activity.runOnUiThread { activity.communityManager.stopMemberInboxListener() }
    }

    @JavascriptInterface
    fun openConversationChat(targetUid: String) {
        activity.runOnUiThread { activity.communityManager.startConversationChatListener(targetUid) }
    }

    @JavascriptInterface
    fun closeConversationChat() {
        activity.runOnUiThread { activity.communityManager.stopConversationChatListener() }
    }

    @JavascriptInterface
    fun sendMessage(recipientUid: String, text: String) {
        activity.runOnUiThread { activity.communityManager.sendMessage(recipientUid, text) }
    }

    @JavascriptInterface
    fun submitThemeSubmission(jsonPayload: String) {
        activity.runOnUiThread { activity.communityManager.submitThemeSubmission(jsonPayload) }
    }

    @JavascriptInterface
    fun fetchMySubmissions() {
        activity.runOnUiThread { activity.communityManager.fetchMySubmissions() }
    }

    @JavascriptInterface
    fun fetchAllSubmissions() {
        activity.runOnUiThread { activity.communityManager.fetchAllSubmissionsForAdmin() }
    }

    @JavascriptInterface
    fun updateSubmissionStatus(
        submissionId: String,
        status: String,
        rejectReason: String,
        approvedThemeId: String
    ) {
        activity.runOnUiThread {
            activity.communityManager.updateSubmissionStatus(submissionId, status, rejectReason, approvedThemeId)
        }
    }

    @JavascriptInterface
    fun adminListUsers(query: String) {
        activity.runOnUiThread { activity.communityManager.adminListUsers(query) }
    }

    @JavascriptInterface
    fun adminModerateUser(targetUid: String, action: String, payloadJson: String) {
        activity.runOnUiThread { activity.communityManager.adminModerateUser(targetUid, action, payloadJson) }
    }

    @JavascriptInterface
    fun adminModerateTheme(themeId: String, action: String, payloadJson: String) {
        activity.runOnUiThread { activity.communityManager.adminModerateTheme(themeId, action, payloadJson) }
    }

    @JavascriptInterface
    fun adminFetchRecentComments() {
        activity.runOnUiThread { activity.communityManager.adminFetchRecentComments() }
    }

    @JavascriptInterface
    fun adminPinComment(themeId: String, commentId: String, pinned: Boolean) {
        activity.runOnUiThread { activity.communityManager.adminPinComment(themeId, commentId, pinned) }
    }

    @JavascriptInterface
    fun adminFetchStatsAndAuditLog() {
        activity.runOnUiThread { activity.communityManager.adminFetchStatsAndAuditLog() }
    }

    @JavascriptInterface
    fun fetchAppThemeConfig() {
        activity.runOnUiThread { activity.appThemeManager.fetchAppThemeNow() }
    }

    @JavascriptInterface
    fun publishAppTheme(jsonPayload: String) {
        activity.runOnUiThread { activity.appThemeManager.publishAppTheme(jsonPayload) }
    }

    @JavascriptInterface
    fun fetchAppThemeHistory() {
        activity.runOnUiThread { activity.appThemeManager.fetchAppThemeHistory() }
    }

    @JavascriptInterface
    fun restoreAppThemeHistory(historyId: String) {
        activity.runOnUiThread { activity.appThemeManager.restoreAppThemeHistory(historyId) }
    }

    @JavascriptInterface
    fun fetchCustomTemplates() {
        activity.runOnUiThread { activity.appThemeManager.fetchCustomTemplates() }
    }

    @JavascriptInterface
    fun saveCustomTemplate(jsonPayload: String) {
        activity.runOnUiThread { activity.appThemeManager.saveCustomTemplate(jsonPayload) }
    }

    @JavascriptInterface
    fun deleteCustomTemplate(templateId: String) {
        activity.runOnUiThread { activity.appThemeManager.deleteCustomTemplate(templateId) }
    }

    @JavascriptInterface
    fun fetchFrames() {
        activity.runOnUiThread { activity.appThemeManager.fetchFrames() }
    }

    @JavascriptInterface
    fun seedDefaultFrames(jsonPayload: String) {
        activity.runOnUiThread { activity.appThemeManager.seedDefaultFrames(jsonPayload) }
    }

    @JavascriptInterface
    fun saveCustomFrame(jsonPayload: String) {
        activity.runOnUiThread { activity.appThemeManager.saveCustomFrame(jsonPayload) }
    }

    @JavascriptInterface
    fun deleteCustomFrame(frameId: String) {
        activity.runOnUiThread { activity.appThemeManager.deleteCustomFrame(frameId) }
    }

    @JavascriptInterface
    fun selectMyFrame(frameId: String) {
        activity.runOnUiThread { activity.communityManager.selectMyFrame(frameId) }
    }

    // ---------------- CLUB ("النادي") BRIDGE ----------------
    @JavascriptInterface
    fun fetchClubConfig() {
        activity.runOnUiThread { activity.clubManager.fetchClubConfig() }
    }

    @JavascriptInterface
    fun fetchClubFeed(tab: String, category: String, cursorVal: Long, filterUid: String) {
        activity.runOnUiThread { activity.clubManager.fetchClubFeed(tab, category, cursorVal, filterUid) }
    }

    @JavascriptInterface
    fun startClubNewPostsListener(sinceCreatedAt: Long) {
        activity.runOnUiThread { activity.clubManager.startNewPostsListener(sinceCreatedAt) }
    }

    @JavascriptInterface
    fun stopClubNewPostsListener() {
        activity.runOnUiThread { activity.clubManager.stopNewPostsListener() }
    }

    @JavascriptInterface
    fun fetchClubPostDetail(postId: String) {
        activity.runOnUiThread { activity.clubManager.fetchClubPostDetail(postId) }
    }

    @JavascriptInterface
    fun acceptClubRules() {
        activity.runOnUiThread { activity.clubManager.acceptClubRules() }
    }

    @JavascriptInterface
    fun createClubPost(jsonPayload: String) {
        activity.runOnUiThread { activity.clubManager.createClubPost(jsonPayload) }
    }

    @JavascriptInterface
    fun editClubPost(postId: String, jsonPayload: String) {
        activity.runOnUiThread { activity.clubManager.editClubPost(postId, jsonPayload) }
    }

    @JavascriptInterface
    fun deleteClubPost(postId: String) {
        activity.runOnUiThread { activity.clubManager.deleteClubPost(postId) }
    }

    @JavascriptInterface
    fun togglePostReaction(postId: String, reaction: String) {
        activity.runOnUiThread { activity.clubManager.togglePostReaction(postId, reaction) }
    }

    @JavascriptInterface
    fun votePoll(postId: String, optionIndex: Int) {
        activity.runOnUiThread { activity.clubManager.votePoll(postId, optionIndex) }
    }

    @JavascriptInterface
    fun fetchPostComments(postId: String, beforeCreatedAt: Long) {
        activity.runOnUiThread { activity.clubManager.fetchPostComments(postId, beforeCreatedAt) }
    }

    @JavascriptInterface
    fun addPostComment(postId: String, text: String) {
        activity.runOnUiThread { activity.clubManager.addPostComment(postId, text) }
    }

    @JavascriptInterface
    fun deletePostComment(postId: String, commentId: String) {
        activity.runOnUiThread { activity.clubManager.deletePostComment(postId, commentId) }
    }

    @JavascriptInterface
    fun togglePostCommentLike(postId: String, commentId: String) {
        activity.runOnUiThread { activity.clubManager.togglePostCommentLike(postId, commentId) }
    }

    @JavascriptInterface
    fun fetchPostCommentReplies(postId: String, commentId: String) {
        activity.runOnUiThread { activity.clubManager.fetchPostCommentReplies(postId, commentId) }
    }

    @JavascriptInterface
    fun addPostCommentReply(postId: String, commentId: String, text: String) {
        activity.runOnUiThread { activity.clubManager.addPostCommentReply(postId, commentId, text) }
    }

    @JavascriptInterface
    fun deletePostCommentReply(postId: String, commentId: String, replyId: String) {
        activity.runOnUiThread { activity.clubManager.deletePostCommentReply(postId, commentId, replyId) }
    }

    @JavascriptInterface
    fun togglePostCommentReplyLike(postId: String, commentId: String, replyId: String) {
        activity.runOnUiThread { activity.clubManager.togglePostCommentReplyLike(postId, commentId, replyId) }
    }

    @JavascriptInterface
    fun toggleFollowUser(targetUid: String, follow: Boolean) {
        activity.runOnUiThread { activity.clubManager.toggleFollowUser(targetUid, follow) }
    }

    @JavascriptInterface
    fun toggleBookmarkPost(postId: String, bookmarked: Boolean) {
        activity.runOnUiThread { activity.clubManager.toggleBookmarkPost(postId, bookmarked) }
    }

    @JavascriptInterface
    fun reportClubTarget(targetType: String, targetId: String, parentId: String, reason: String) {
        activity.runOnUiThread { activity.clubManager.reportClubTarget(targetType, targetId, parentId, reason) }
    }

    @JavascriptInterface
    fun adminFetchClubPosts(statusFilter: String) {
        activity.runOnUiThread { activity.clubManager.adminFetchClubPosts(statusFilter) }
    }

    @JavascriptInterface
    fun adminModerateClubPost(postId: String, action: String, payloadJson: String) {
        activity.runOnUiThread { activity.clubManager.adminModerateClubPost(postId, action, payloadJson) }
    }

    @JavascriptInterface
    fun adminFetchClubReports() {
        activity.runOnUiThread { activity.clubManager.adminFetchClubReports() }
    }

    @JavascriptInterface
    fun adminSaveModerationConfig(jsonPayload: String) {
        activity.runOnUiThread { activity.clubManager.adminSaveModerationConfig(jsonPayload) }
    }

    @JavascriptInterface
    fun shareClubPost(postId: String, snippet: String) {
        val cleanId = postId.trim()
        if (cleanId.isEmpty()) return
        val shareUrl = "https://zaim9002.github.io/theme-hub/?post=${Uri.encode(cleanId)}"
        val text = if (snippet.isNotBlank()) "${snippet.trim().take(120)}\n\n$shareUrl" else shareUrl
        activity.runOnUiThread {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            runCatching {
                activity.startActivity(Intent.createChooser(sendIntent, "مشاركة المنشور"))
            }.onFailure {
                activity.sendToastToJs("تعذر فتح قائمة المشاركة")
            }
        }
    }

    @JavascriptInterface
    fun beginUploadTempFile(uploadId: String, fileKey: String): Boolean {
        return activity.beginUploadTempFile(uploadId, fileKey)
    }

    @JavascriptInterface
    fun appendUploadTempChunk(uploadId: String, fileKey: String, base64Chunk: String): Boolean {
        return activity.appendUploadTempChunk(uploadId, fileKey, base64Chunk)
    }

    @JavascriptInterface
    fun startBackgroundThemePublish(jobJson: String): Boolean {
        return activity.startBackgroundThemePublish(jobJson)
    }

    @JavascriptInterface
    fun cancelBackgroundThemePublish() {
        activity.runOnUiThread { activity.cancelBackgroundThemePublish() }
    }

    @JavascriptInterface
    fun getBackgroundPublishStatus(): String {
        return ThemeUploadService.getLatestStatusJson()
    }

    @JavascriptInterface
    fun recordPublishLog(logJson: String) {
        activity.runOnUiThread { activity.communityManager.recordPublishLog(logJson) }
    }

    @JavascriptInterface
    fun fetchPublishLogs() {
        activity.runOnUiThread { activity.communityManager.fetchPublishLogs() }
    }

    @JavascriptInterface
    fun translateStudioText(text: String, fromLang: String, toLang: String, requestId: String) {
        activity.runOnUiThread { activity.translateStudioText(text, fromLang, toLang, requestId) }
    }
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ThemeHubApp(
    onWebViewReady: (WebView) -> Unit,
    onExitApp: () -> Unit = {}
) {
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        val wv = webViewInstance
        if (wv == null) {
            onExitApp()
            return@BackHandler
        }
        wv.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : 'exit'") { rawResult ->
            val result = rawResult?.trim('"')
            if (result == "exit") {
                if (wv.canGoBack() && canGoBack) {
                    wv.goBack()
                } else {
                    onExitApp()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F14))
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .testTag("theme_hub_container")
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("theme_hub_webview"),
            factory = { ctx ->
                val activity = ctx.findMainActivity()
                    ?: error("ThemeHubApp must be hosted inside MainActivity")
                if (BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundColor(0xFF0F0F14.toInt())

                    val currentWebView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(currentWebView, true)
                    }

                    @Suppress("DEPRECATION")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = false
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    addJavascriptInterface(AndroidWebAppInterface(activity), "AndroidBridge")

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            view: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            return activity.openFileChooser(filePathCallback, fileChooserParams)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            canGoBack = view?.canGoBack() == true
                            activity.onWebViewLoaded(view)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                Log.e("ThemeHubWebView", "Main frame load error: ${error?.description}")
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            Log.e("ThemeHubWebView", "WebView renderer gone; reloading asset UI")
                            view?.loadUrl("file:///android_asset/index.html")
                            return true
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            return when {
                                url.startsWith("intent:") -> {
                                    runCatching { ctx.startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)) }
                                        .onFailure { Toast.makeText(ctx, "التطبيق المطلوب غير مثبت على الجهاز", Toast.LENGTH_SHORT).show() }
                                    true
                                }
                                url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("market:") -> {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    true
                                }
                                else -> false
                            }
                        }
                    }

                    loadUrl("file:///android_asset/index.html")
                    onWebViewReady(this)
                }
            },
            update = { wv ->
                if (webViewInstance !== wv) {
                    webViewInstance = wv
                }
                onWebViewReady(wv)
            }
        )
    }
}
