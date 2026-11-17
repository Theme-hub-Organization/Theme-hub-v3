package com.example

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Calendar

class ClubFirestoreManager(
    private val activity: MainActivity,
    private val scope: CoroutineScope,
    private val communityManager: CommunityFirestoreManager
) {
    private val db get() = Firebase.firestore
    private val auth get() = Firebase.auth

    private var newPostsListener: ListenerRegistration? = null
    private var cachedConfig: ClubModerationConfig = defaultModerationConfig()

    data class ClubModerationConfig(
        val clubEnabled: Boolean,
        val maintenanceMessage: String,
        val categories: List<String>,
        val bannedWords: List<String>,
        val maxDailyPosts: Int,
        val newUserDailyPosts: Int,
        val postCooldownSec: Int,
        val allowImages: Boolean,
        val allowVideos: Boolean,
        val allowLinks: Boolean,
        val allowedDomains: List<String>
    )

    companion object {
        private const val TAG = "ThemeHubClub"
        private const val PAGE_SIZE = 20
        private const val COMMENTS_PAGE_SIZE = 15
        private const val MAX_IMAGE_DATA_URL_LEN = 220_000
        private val ALLOWED_REACTIONS = setOf("like", "love", "haha", "wow", "fire")
        private val MENTION_REGEX = Regex("@([a-z0-9_]{3,20})")
        private val URL_REGEX = Regex("(?i)\\b((?:https?|http|javascript|data|file):[^\\s\"'<>]+)")

        val DEFAULT_CATEGORIES = listOf(
            "نقاش",
            "مشكلة / مساعدة",
            "عرض إعداد جهازي",
            "اقتراح",
            "طلب ثيم",
            "عام"
        )

        fun defaultModerationConfig(): ClubModerationConfig = ClubModerationConfig(
            clubEnabled = true,
            maintenanceMessage = "",
            categories = DEFAULT_CATEGORIES,
            bannedWords = emptyList(),
            maxDailyPosts = 10,
            newUserDailyPosts = 3,
            postCooldownSec = 30,
            allowImages = true,
            allowVideos = true,
            allowLinks = true,
            allowedDomains = emptyList()
        )
    }

    fun detachAllListeners() {
        stopNewPostsListener()
    }

    fun stopNewPostsListener() {
        runCatching { newPostsListener?.remove() }
        newPostsListener = null
    }

    fun startNewPostsListener(sinceCreatedAt: Long) {
        stopNewPostsListener()
        val threshold = if (sinceCreatedAt > 0L) sinceCreatedAt else System.currentTimeMillis()
        newPostsListener = db.collection("posts")
            .whereEqualTo("status", "visible")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "New posts listener note: ${err.message}")
                    return@addSnapshotListener
                }
                val top = snap?.documents?.firstOrNull() ?: return@addSnapshotListener
                val ts = top.getLong("createdAt") ?: 0L
                val authorUid = top.getString("authorUid") ?: ""
                val myUid = auth.currentUser?.uid ?: ""
                if (ts > threshold && authorUid != myUid) {
                    activity.evaluateJs("window.onClubNewPostAvailable?.($ts);")
                }
            }
    }

    private fun extractStringList(snap: DocumentSnapshot, field: String): List<String> {
        val raw = snap.get(field) as? List<*> ?: return emptyList()
        return raw.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }

    private fun parseModerationConfig(snap: DocumentSnapshot?): ClubModerationConfig {
        if (snap == null || !snap.exists()) return defaultModerationConfig()
        val cats = extractStringList(snap, "categories").ifEmpty { DEFAULT_CATEGORIES }
        val banned = extractStringList(snap, "bannedWords").map { it.lowercase() }
        val domains = extractStringList(snap, "allowedDomains").map { it.lowercase().removePrefix("www.") }
        return ClubModerationConfig(
            clubEnabled = snap.getBoolean("clubEnabled") ?: true,
            maintenanceMessage = snap.getString("maintenanceMessage") ?: "",
            categories = cats,
            bannedWords = banned,
            maxDailyPosts = (snap.getLong("maxDailyPosts")?.toInt() ?: 10).coerceIn(1, 100),
            newUserDailyPosts = (snap.getLong("newUserDailyPosts")?.toInt() ?: 3).coerceIn(1, 50),
            postCooldownSec = (snap.getLong("postCooldownSec")?.toInt() ?: 30).coerceIn(0, 600),
            allowImages = snap.getBoolean("allowImages") ?: true,
            allowVideos = snap.getBoolean("allowVideos") ?: true,
            allowLinks = snap.getBoolean("allowLinks") ?: true,
            allowedDomains = domains
        )
    }

    private fun configToJson(cfg: ClubModerationConfig): JSONObject = JSONObject().apply {
        put("clubEnabled", cfg.clubEnabled)
        put("maintenanceMessage", cfg.maintenanceMessage)
        put("categories", JSONArray(cfg.categories))
        put("bannedWords", JSONArray(cfg.bannedWords))
        put("maxDailyPosts", cfg.maxDailyPosts)
        put("newUserDailyPosts", cfg.newUserDailyPosts)
        put("postCooldownSec", cfg.postCooldownSec)
        put("allowImages", cfg.allowImages)
        put("allowVideos", cfg.allowVideos)
        put("allowLinks", cfg.allowLinks)
        put("allowedDomains", JSONArray(cfg.allowedDomains))
    }

    fun fetchClubConfig() {
        scope.launch {
            try {
                val snap = db.collection("config").document("moderation").get().await()
                val cfg = parseModerationConfig(snap)
                cachedConfig = cfg
                val payload = configToJson(cfg).apply { put("ok", true) }.toString()
                activity.evaluateJs("window.onClubConfigLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = configToJson(cachedConfig).apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onClubConfigLoaded?.($payload);")
            }
        }
    }

    private suspend fun loadModerationConfigFresh(): ClubModerationConfig {
        return runCatching {
            val snap = db.collection("config").document("moderation").get().await()
            parseModerationConfig(snap).also { cachedConfig = it }
        }.getOrDefault(cachedConfig)
    }

    private fun validateTextSafety(
        text: String,
        cfg: ClubModerationConfig,
        userCreatedAt: Long,
        isAdmin: Boolean
    ): String? {
        val lower = text.lowercase()
        for (word in cfg.bannedWords) {
            if (word.isNotBlank() && lower.contains(word)) {
                return "يحتوي النص على كلمة غير مسموح بها في النادي"
            }
        }
        val urls = URL_REGEX.findAll(text).map { it.value }.toList()
        if (urls.isEmpty()) return null
        if (!isAdmin && !cfg.allowLinks) {
            return "نشر الروابط معطّل حالياً في إعدادات النادي"
        }
        val now = System.currentTimeMillis()
        val ageMs = if (userCreatedAt > 0L) (now - userCreatedAt) else Long.MAX_VALUE
        if (!isAdmin && ageMs < 86_400_000L) {
            return "للحماية من السبام، يُسمح بنشر الروابط بعد مرور 24 ساعة من التسجيل"
        }
        for (u in urls) {
            if (!u.startsWith("https://", ignoreCase = true)) {
                return "لأمان الأعضاء، يُسمح فقط بروابط https:// الآمنة"
            }
            if (!isAdmin && cfg.allowedDomains.isNotEmpty()) {
                val host = runCatching { URI(u).host?.lowercase()?.removePrefix("www.") ?: "" }.getOrDefault("")
                val allowed = cfg.allowedDomains.any { d -> host == d || host.endsWith(".$d") }
                if (!allowed) {
                    return "النطاق ($host) غير موجود ضمن قائمة النطاقات المسموح بنشرها في النادي"
                }
            }
        }
        return null
    }

    private suspend fun resolveMentionsUids(text: String): List<String> {
        val unames = MENTION_REGEX.findAll(text).map { it.groupValues[1] }.distinct().take(10).toList()
        if (unames.isEmpty()) return emptyList()
        val uids = mutableListOf<String>()
        for (uname in unames) {
            runCatching {
                val snap = db.collection("usernames").document(uname).get().await()
                val uid = snap.getString("uid")?.trim().orEmpty()
                if (uid.isNotEmpty() && uid !in uids) uids.add(uid)
            }
        }
        return uids
    }

    private suspend fun getMyBlockedSet(myUid: String?): Set<String> {
        if (myUid.isNullOrBlank()) return emptySet()
        return runCatching {
            db.collection("users").document(myUid).collection("blockedUsers").get().await()
                .documents.map { it.id }.toSet()
        }.getOrDefault(emptySet())
    }

    private suspend fun hydratePosts(
        docs: List<DocumentSnapshot>,
        myUid: String?
    ): JSONArray {
        val authorUids = docs.mapNotNull { it.getString("authorUid") }.distinct()
        val authorMap = communityManager.fetchUserSummaries(authorUids)
        val arr = JSONArray()
        for (d in docs) {
            val postId = d.id
            val authorUid = d.getString("authorUid") ?: ""
            val author = authorMap[authorUid]

            val mediaCount = (d.getLong("mediaCount")?.toInt() ?: 0).coerceAtLeast(0)
            val mediaUrls = JSONArray()
            if (mediaCount > 0) {
                runCatching {
                    val mSnap = d.reference.collection("media").get().await()
                    mSnap.documents
                        .sortedBy { it.getLong("index") ?: it.id.toLongOrNull() ?: 0L }
                        .forEach { m ->
                            val url = m.getString("dataUrl") ?: ""
                            if (url.isNotEmpty()) mediaUrls.put(url)
                        }
                }
            }

            var myReaction = ""
            var myPollVote = -1
            var bookmarked = false
            if (!myUid.isNullOrBlank()) {
                runCatching {
                    val rSnap = d.reference.collection("likes").document(myUid).get().await()
                    if (rSnap.exists()) myReaction = rSnap.getString("reaction") ?: ""
                }
                if (d.get("poll") != null) {
                    runCatching {
                        val pSnap = d.reference.collection("pollVotes").document(myUid).get().await()
                        if (pSnap.exists()) myPollVote = pSnap.getLong("optionIndex")?.toInt() ?: -1
                    }
                }
                runCatching {
                    bookmarked = db.collection("bookmarks").document(myUid)
                        .collection("posts").document(postId).get().await().exists()
                }
            }

            arr.put(serializePostDoc(d, author, mediaUrls, myReaction, myPollVote, bookmarked))
        }
        return arr
    }

    private fun serializePostDoc(
        d: DocumentSnapshot,
        author: JSONObject?,
        mediaUrls: JSONArray,
        myReaction: String,
        myPollVote: Int,
        bookmarked: Boolean
    ): JSONObject {
        val authorUid = d.getString("authorUid") ?: ""
        val rawReactions = d.get("reactions") as? Map<*, *>
        val reactionsObj = JSONObject()
        ALLOWED_REACTIONS.forEach { key ->
            val count = (rawReactions?.get(key) as? Number)?.toInt() ?: 0
            reactionsObj.put(key, count.coerceAtLeast(0))
        }

        val rawPoll = d.get("poll") as? Map<*, *>
        val pollObj: Any = if (rawPoll != null) {
            val optsRaw = rawPoll["options"] as? List<*> ?: emptyList<Any>()
            val optsArr = JSONArray()
            optsRaw.forEach { item ->
                val map = item as? Map<*, *> ?: return@forEach
                optsArr.put(JSONObject().apply {
                    put("text", (map["text"] as? String) ?: "")
                    put("votes", ((map["votes"] as? Number)?.toInt() ?: 0).coerceAtLeast(0))
                })
            }
            JSONObject().apply {
                put("question", (rawPoll["question"] as? String) ?: "")
                put("options", optsArr)
                put("totalVotes", ((rawPoll["totalVotes"] as? Number)?.toInt() ?: 0).coerceAtLeast(0))
            }
        } else JSONObject.NULL

        val rawThemeSnap = d.get("themeSnapshot") as? Map<*, *>
        val themeSnapObj: Any = if (rawThemeSnap != null) {
            JSONObject().apply {
                put("id", (rawThemeSnap["id"] as? String) ?: "")
                put("name", (rawThemeSnap["name"] as? String) ?: "")
                put("category", (rawThemeSnap["category"] as? String) ?: "")
                put("brand", (rawThemeSnap["brand"] as? String) ?: "")
                put("coverUrl", (rawThemeSnap["coverUrl"] as? String) ?: "")
            }
        } else JSONObject.NULL

        return JSONObject().apply {
            put("id", d.id)
            put("authorUid", authorUid)
            put("authorName", author?.optString("displayName")?.ifBlank { null } ?: (d.getString("authorName") ?: "عضو Theme Hub"))
            put("authorUsername", author?.optString("username") ?: (d.getString("authorUsername") ?: ""))
            put("authorPhoto", author?.optString("photoURL") ?: "")
            put("authorFrame", author?.optString("frame") ?: "")
            put("authorFrameId", author?.optString("frameId") ?: (author?.optString("frame") ?: ""))
            put("authorBadges", author?.optJSONArray("badges") ?: JSONArray())
            put("authorCustomTitle", author?.optString("customTitle") ?: "")
            put("authorTitle", author?.optString("title") ?: (author?.optString("customTitle") ?: ""))
            put("authorVerified", author?.optBoolean("verified") ?: false)
            put("authorBadgeType", author?.optString("badgeType") ?: "")
            put("authorNameColor", author?.optString("nameColor") ?: "")
            put("authorNameGradient", author?.optString("nameGradient") ?: "")
            put("authorNameFont", author?.optString("nameFont") ?: "")
            put("authorIsAdmin", author?.optBoolean("isAdmin") ?: false)
            put("authorIsOwner", author?.optBoolean("isOwner") ?: false)
            put("authorIsPublisher", author?.optBoolean("publisher") ?: false)
            put("text", d.getString("text") ?: "")
            put("category", d.getString("category") ?: "عام")
            put("themeRef", d.getString("themeRef") ?: "")
            put("themeSnapshot", themeSnapObj)
            put("images", mediaUrls)
            put("mediaCount", d.getLong("mediaCount")?.toInt() ?: mediaUrls.length())
            put("videoUrl", d.getString("videoUrl") ?: "")
            put("videoDurationSec", d.getLong("videoDurationSec")?.toInt() ?: 0)
            put("poll", pollObj)
            put("pinned", d.getBoolean("pinned") ?: false)
            put("isAnnouncement", d.getBoolean("isAnnouncement") ?: false)
            put("commentsLocked", d.getBoolean("commentsLocked") ?: false)
            put("status", d.getString("status") ?: "visible")
            put("likesCount", (d.getLong("likesCount")?.toInt() ?: 0).coerceAtLeast(0))
            put("commentsCount", (d.getLong("commentsCount")?.toInt() ?: 0).coerceAtLeast(0))
            put("reportsCount", (d.getLong("reportsCount")?.toInt() ?: 0).coerceAtLeast(0))
            put("engagementScore", (d.getLong("engagementScore")?.toInt() ?: 0).coerceAtLeast(0))
            put("reactions", reactionsObj)
            put("myReaction", myReaction)
            put("myPollVote", myPollVote)
            put("bookmarked", bookmarked)
            put("createdAt", d.getLong("createdAt") ?: 0L)
            put("updatedAt", d.getLong("updatedAt") ?: 0L)
        }
    }

    fun fetchClubFeed(tab: String, category: String, cursorVal: Long, filterUid: String) {
        scope.launch {
            try {
                val myUid = auth.currentUser?.uid
                val blockedSet = getMyBlockedSet(myUid)
                val isFirstPage = cursorVal <= 0L

                var docs: List<DocumentSnapshot> = emptyList()
                var pinnedDocs: List<DocumentSnapshot> = emptyList()
                var hasMore = false
                var nextCursor = 0L

                when (tab) {
                    "following" -> {
                        if (myUid.isNullOrBlank()) {
                            emitFeedResult(tab, category, !isFirstPage, false, 0L, JSONArray())
                            return@launch
                        }
                        val followingSnap = db.collection("follows").document(myUid)
                            .collection("following").limit(50).get().await()
                        val targetUids = followingSnap.documents.map { it.id }.filter { it !in blockedSet }
                        if (targetUids.isEmpty()) {
                            emitFeedResult(tab, category, !isFirstPage, false, 0L, JSONArray())
                            return@launch
                        }
                        val collected = mutableListOf<DocumentSnapshot>()
                        for (chunk in targetUids.chunked(10)) {
                            val snap = db.collection("posts")
                                .whereEqualTo("status", "visible")
                                .whereIn("authorUid", chunk)
                                .limit(40)
                                .get().await()
                            collected.addAll(snap.documents)
                        }
                        val filtered = collected
                            .filter { d ->
                                val ts = d.getLong("createdAt") ?: 0L
                                isFirstPage || ts < cursorVal
                            }
                            .sortedByDescending { it.getLong("createdAt") ?: 0L }
                        hasMore = filtered.size > PAGE_SIZE
                        docs = if (hasMore) filtered.take(PAGE_SIZE) else filtered
                        nextCursor = docs.lastOrNull()?.getLong("createdAt") ?: 0L
                    }
                    "trending" -> {
                        val offset = cursorVal.toInt().coerceAtLeast(0)
                        val snap = db.collection("posts")
                            .whereEqualTo("status", "visible")
                            .orderBy("engagementScore", Query.Direction.DESCENDING)
                            .limit((offset + PAGE_SIZE + 1).toLong())
                            .get().await()
                        val filtered = snap.documents.filter { (it.getString("authorUid") ?: "") !in blockedSet }
                        hasMore = filtered.size > offset + PAGE_SIZE
                        docs = filtered.drop(offset).take(PAGE_SIZE)
                        nextCursor = (offset + docs.size).toLong()
                    }
                    "category" -> {
                        var q: Query = db.collection("posts")
                            .whereEqualTo("status", "visible")
                            .whereEqualTo("category", category)
                            .orderBy("createdAt", Query.Direction.DESCENDING)
                        if (!isFirstPage) q = q.whereLessThan("createdAt", cursorVal)
                        val snap = q.limit((PAGE_SIZE + 1).toLong()).get().await()
                        val raw = snap.documents
                        hasMore = raw.size > PAGE_SIZE
                        val sliced = if (hasMore) raw.take(PAGE_SIZE) else raw
                        docs = sliced.filter { (it.getString("authorUid") ?: "") !in blockedSet }
                        nextCursor = sliced.lastOrNull()?.getLong("createdAt") ?: 0L
                    }
                    "bookmarks" -> {
                        if (myUid.isNullOrBlank()) {
                            emitFeedResult(tab, category, !isFirstPage, false, 0L, JSONArray())
                            return@launch
                        }
                        val offset = cursorVal.toInt().coerceAtLeast(0)
                        val bmSnap = db.collection("bookmarks").document(myUid)
                            .collection("posts")
                            .orderBy("createdAt", Query.Direction.DESCENDING)
                            .limit((offset + PAGE_SIZE + 1).toLong())
                            .get().await()
                        hasMore = bmSnap.documents.size > offset + PAGE_SIZE
                        val sliceBm = bmSnap.documents.drop(offset).take(PAGE_SIZE)
                        val resolved = mutableListOf<DocumentSnapshot>()
                        for (bm in sliceBm) {
                            runCatching {
                                val p = db.collection("posts").document(bm.id).get().await()
                                if (p.exists()) {
                                    val st = p.getString("status") ?: "visible"
                                    val au = p.getString("authorUid") ?: ""
                                    if ((st == "visible" || au == myUid || activity.currentUserIsAdmin) && au !in blockedSet) {
                                        resolved.add(p)
                                    }
                                }
                            }
                        }
                        docs = resolved
                        nextCursor = (offset + sliceBm.size).toLong()
                    }
                    "user" -> {
                        val target = filterUid.trim()
                        if (target.isEmpty()) {
                            emitFeedResult(tab, category, !isFirstPage, false, 0L, JSONArray())
                            return@launch
                        }
                        var q: Query = if (target == myUid || activity.currentUserIsAdmin) {
                            db.collection("posts").whereEqualTo("authorUid", target)
                        } else {
                            db.collection("posts").whereEqualTo("status", "visible").whereEqualTo("authorUid", target)
                        }
                        q = q.orderBy("createdAt", Query.Direction.DESCENDING)
                        if (!isFirstPage) q = q.whereLessThan("createdAt", cursorVal)
                        val snap = q.limit((PAGE_SIZE + 1).toLong()).get().await()
                        hasMore = snap.documents.size > PAGE_SIZE
                        docs = if (hasMore) snap.documents.take(PAGE_SIZE) else snap.documents
                        nextCursor = docs.lastOrNull()?.getLong("createdAt") ?: 0L
                    }
                    else -> { // "all"
                        if (isFirstPage) {
                            pinnedDocs = runCatching {
                                db.collection("posts")
                                    .whereEqualTo("status", "visible")
                                    .whereEqualTo("pinned", true)
                                    .orderBy("createdAt", Query.Direction.DESCENDING)
                                    .limit(5)
                                    .get().await().documents
                                    .filter { (it.getString("authorUid") ?: "") !in blockedSet }
                            }.getOrDefault(emptyList())
                        }
                        var q: Query = db.collection("posts")
                            .whereEqualTo("status", "visible")
                            .orderBy("createdAt", Query.Direction.DESCENDING)
                        if (!isFirstPage) q = q.whereLessThan("createdAt", cursorVal)
                        val snap = q.limit((PAGE_SIZE + 1).toLong()).get().await()
                        val raw = snap.documents
                        hasMore = raw.size > PAGE_SIZE
                        val sliced = if (hasMore) raw.take(PAGE_SIZE) else raw
                        val pinnedIds = pinnedDocs.map { it.id }.toSet()
                        docs = sliced.filter { it.id !in pinnedIds && (it.getString("authorUid") ?: "") !in blockedSet }
                        nextCursor = sliced.lastOrNull()?.getLong("createdAt") ?: 0L
                    }
                }

                val combined = pinnedDocs + docs
                val hydrated = hydratePosts(combined, myUid)
                emitFeedResult(tab, category, !isFirstPage, hasMore, nextCursor, hydrated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("tab", tab)
                    put("category", category)
                    put("append", cursorVal > 0L)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onClubFeedLoaded?.($payload);")
            }
        }
    }

    private fun emitFeedResult(
        tab: String,
        category: String,
        append: Boolean,
        hasMore: Boolean,
        nextCursor: Long,
        posts: JSONArray
    ) {
        val payload = JSONObject().apply {
            put("ok", true)
            put("tab", tab)
            put("category", category)
            put("append", append)
            put("hasMore", hasMore)
            put("nextCursor", nextCursor)
            put("posts", posts)
        }.toString()
        activity.evaluateJs("window.onClubFeedLoaded?.($payload);")
    }

    fun fetchClubPostDetail(postId: String) {
        val cleanId = postId.trim()
        if (cleanId.isEmpty()) return
        scope.launch {
            try {
                val doc = db.collection("posts").document(cleanId).get().await()
                if (!doc.exists()) {
                    val err = JSONObject().apply {
                        put("ok", false)
                        put("postId", cleanId)
                        put("error", "لم يتم العثور على هذا المنشور أو تم حذفه")
                    }.toString()
                    activity.evaluateJs("window.onClubPostDetailLoaded?.($err);")
                    return@launch
                }
                val myUid = auth.currentUser?.uid
                val arr = hydratePosts(listOf(doc), myUid)
                val postObj = arr.optJSONObject(0) ?: JSONObject()
                val authorUid = postObj.optString("authorUid", "")
                var isFollowing = false
                if (!myUid.isNullOrBlank() && authorUid.isNotBlank() && myUid != authorUid) {
                    isFollowing = runCatching {
                        db.collection("follows").document(myUid)
                            .collection("following").document(authorUid).get().await().exists()
                    }.getOrDefault(false)
                }
                postObj.put("authorFollowedByMe", isFollowing)
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("post", postObj)
                }.toString()
                activity.evaluateJs("window.onClubPostDetailLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("postId", cleanId)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onClubPostDetailLoaded?.($err);")
            }
        }
    }

    fun acceptClubRules() {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubRulesAccepted?.({ok:false,needAuth:true});")
            return
        }
        scope.launch {
            try {
                db.collection("users").document(user.uid)
                    .update("clubRulesAccepted", true, "updatedAt", System.currentTimeMillis())
                    .await()
                activity.evaluateJs("window.onClubRulesAccepted?.({ok:true});")
                activity.sendToastToJs("✓ أهلاً بك في نادي Theme Hub!")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onClubRulesAccepted?.($err);")
            }
        }
    }

    fun createClubPost(jsonPayload: String) {
        val user = auth.currentUser
        if (user == null) {
            emitPostSaveError("يجب تسجيل الدخول أولاً للنشر في النادي")
            return
        }
        if (activity.currentUserBlocked) {
            emitPostSaveError("حسابك محظور من النشر في النادي")
            return
        }
        if (activity.currentUserMuted) {
            emitPostSaveError("حسابك مكتوم حالياً ولا يمكنك النشر حتى انتهاء فترة الكتم")
            return
        }
        scope.launch {
            try {
                val cfg = loadModerationConfigFresh()
                val isAdmin = activity.currentUserIsAdmin
                if (!cfg.clubEnabled && !isAdmin) {
                    emitPostSaveError(cfg.maintenanceMessage.ifBlank { "النادي في وضع الصيانة مؤقتاً، يرجى المحاولة لاحقاً" })
                    return@launch
                }

                val userDoc = db.collection("users").document(user.uid).get().await()
                val rulesAccepted = userDoc.getBoolean("clubRulesAccepted") ?: false
                if (!rulesAccepted && !isAdmin) {
                    emitPostSaveError("يرجى الموافقة على قوانين النادي أولاً قبل النشر")
                    return@launch
                }
                val userCreatedAt = userDoc.getLong("createdAt") ?: 0L
                val now = System.currentTimeMillis()

                // Rate limit & daily quota check (client-enforced on Spark plan)
                if (!isAdmin) {
                    val myPostsSnap = db.collection("posts")
                        .whereEqualTo("authorUid", user.uid)
                        .limit(50)
                        .get().await()
                    val latestPostTs = myPostsSnap.documents.maxOfOrNull { it.getLong("createdAt") ?: 0L } ?: 0L
                    if (latestPostTs > 0L && (now - latestPostTs) < cfg.postCooldownSec * 1000L) {
                        val waitSec = ((cfg.postCooldownSec * 1000L - (now - latestPostTs) + 999L) / 1000L).coerceAtLeast(1L)
                        emitPostSaveError("يرجى الانتظار $waitSec ثانية قبل نشر منشور جديد")
                        return@launch
                    }
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val dayStart = cal.timeInMillis
                    val todayCount = myPostsSnap.documents.count { (it.getLong("createdAt") ?: 0L) >= dayStart }
                    val isNewUser = userCreatedAt > 0L && (now - userCreatedAt) < 86_400_000L
                    val quota = if (isNewUser) cfg.newUserDailyPosts else cfg.maxDailyPosts
                    if (todayCount >= quota) {
                        emitPostSaveError("وصلت للحد اليومي للمنشورات ($quota منشور يومياً)")
                        return@launch
                    }
                }

                val req = JSONObject(jsonPayload)
                val text = req.optString("text", "").trim().take(2000)
                val category = req.optString("category", "عام").trim().take(50).ifBlank { "عام" }
                val videoUrl = req.optString("videoUrl", "").trim().take(500)
                val videoDurationSec = req.optInt("videoDurationSec", 0).coerceAtLeast(0)
                val isAnnouncement = isAdmin && req.optBoolean("isAnnouncement", false)
                val pinned = isAdmin && req.optBoolean("pinned", false)

                val imagesArr = req.optJSONArray("images") ?: JSONArray()
                val images = mutableListOf<String>()
                for (i in 0 until imagesArr.length().coerceAtMost(4)) {
                    val u = imagesArr.optString(i, "").trim()
                    if (u.isNotEmpty()) {
                        if (u.length > MAX_IMAGE_DATA_URL_LEN) {
                            emitPostSaveError("إحدى الصور تتجاوز الحجم المسموح (200KB WebP)")
                            return@launch
                        }
                        images.add(u)
                    }
                }
                if (images.isNotEmpty() && !cfg.allowImages && !isAdmin) {
                    emitPostSaveError("رفع الصور معطّل حالياً من قبل الإدارة")
                    return@launch
                }
                if (videoUrl.isNotEmpty()) {
                    if (!cfg.allowVideos && !isAdmin) {
                        emitPostSaveError("إرفاق الفيديو معطّل حالياً من قبل الإدارة")
                        return@launch
                    }
                    if (!videoUrl.startsWith("https://")) {
                        emitPostSaveError("رابط الفيديو يجب أن يبدأ بـ https://")
                        return@launch
                    }
                    if (videoDurationSec > 120) {
                        emitPostSaveError("الحد الأقصى لمدة الفيديو 120 ثانية (دقيقتان)")
                        return@launch
                    }
                }

                val pollReq = req.optJSONObject("poll")
                var pollMap: Map<String, Any>? = null
                if (pollReq != null) {
                    val qText = pollReq.optString("question", "").trim().take(200)
                    val optsArr = pollReq.optJSONArray("options") ?: JSONArray()
                    val cleanOpts = mutableListOf<Map<String, Any>>()
                    for (i in 0 until optsArr.length().coerceAtMost(4)) {
                        val opt = optsArr.optString(i, "").trim().take(80)
                        if (opt.isNotEmpty()) {
                            cleanOpts.add(hashMapOf("text" to opt, "votes" to 0))
                        }
                    }
                    if (qText.isEmpty() || cleanOpts.size < 2) {
                        emitPostSaveError("التصويت يتطلب سؤالاً وخيارين على الأقل (حتى 4 خيارات)")
                        return@launch
                    }
                    val pollErr = validateTextSafety(
                        qText + " " + cleanOpts.joinToString(" ") { it["text"] as String },
                        cfg, userCreatedAt, isAdmin
                    )
                    if (pollErr != null) {
                        emitPostSaveError(pollErr)
                        return@launch
                    }
                    pollMap = hashMapOf(
                        "question" to qText,
                        "options" to cleanOpts,
                        "totalVotes" to 0
                    )
                }

                val themeRefId = req.optString("themeRef", "").trim().take(120)
                val themeSnapJson = req.optJSONObject("themeSnapshot")
                var themeSnapMap: Map<String, Any>? = null
                if (themeRefId.isNotEmpty() && themeSnapJson != null) {
                    themeSnapMap = hashMapOf(
                        "id" to themeRefId,
                        "name" to themeSnapJson.optString("name", "").take(100),
                        "category" to themeSnapJson.optString("category", "").take(50),
                        "brand" to themeSnapJson.optString("brand", "").take(40),
                        "coverUrl" to themeSnapJson.optString("coverUrl", "").take(500)
                    )
                }

                if (text.isEmpty() && images.isEmpty() && videoUrl.isEmpty() && pollMap == null && themeRefId.isEmpty()) {
                    emitPostSaveError("يرجى كتابة نص أو إضافة صورة أو تصويت أو ثيم للمنشور")
                    return@launch
                }

                val textSafetyErr = validateTextSafety(text, cfg, userCreatedAt, isAdmin)
                if (textSafetyErr != null) {
                    emitPostSaveError(textSafetyErr)
                    return@launch
                }

                val authorName = userDoc.getString("displayName")?.ifBlank { null }
                    ?: (user.displayName ?: "عضو Theme Hub").take(60)
                val authorUsername = userDoc.getString("username").orEmpty()
                val mentionUids = resolveMentionsUids(text)

                val postRef = db.collection("posts").document()
                val postData = hashMapOf<String, Any>(
                    "authorUid" to user.uid,
                    "authorName" to authorName.take(60),
                    "authorUsername" to authorUsername,
                    "text" to text,
                    "category" to category,
                    "themeRef" to themeRefId,
                    "videoUrl" to videoUrl,
                    "videoDurationSec" to videoDurationSec,
                    "mediaCount" to images.size,
                    "mentions" to mentionUids,
                    "pinned" to pinned,
                    "isAnnouncement" to isAnnouncement,
                    "commentsLocked" to false,
                    "status" to "visible",
                    "likesCount" to 0,
                    "commentsCount" to 0,
                    "reportsCount" to 0,
                    "engagementScore" to 0,
                    "reactions" to hashMapOf("like" to 0, "love" to 0, "haha" to 0, "wow" to 0, "fire" to 0),
                    "createdAt" to now,
                    "updatedAt" to now
                )
                if (pollMap != null) postData["poll"] = pollMap
                if (themeSnapMap != null) postData["themeSnapshot"] = themeSnapMap

                val batch = db.batch()
                batch.set(postRef, postData)
                images.forEachIndexed { idx, dataUrl ->
                    val mRef = postRef.collection("media").document(idx.toString())
                    batch.set(
                        mRef,
                        hashMapOf(
                            "authorUid" to user.uid,
                            "index" to idx,
                            "dataUrl" to dataUrl,
                            "createdAt" to now
                        )
                    )
                }
                batch.commit().await()

                for (targetUid in mentionUids) {
                    if (targetUid != user.uid) {
                        communityManager.sendUserNotification(
                            targetUid = targetUid,
                            type = "post_mention",
                            title = "📣 ذكرك $authorName في منشور",
                            body = text.take(140),
                            postId = postRef.id,
                            actorUid = user.uid,
                            actorName = authorName
                        )
                    }
                }

                if (isAnnouncement) {
                    communityManager.writeAuditLog("club_announcement", "post", postRef.id, text.take(120))
                }

                val savedDoc = postRef.get().await()
                val hydrated = hydratePosts(listOf(savedDoc), user.uid)
                val result = JSONObject().apply {
                    put("ok", true)
                    put("mode", "create")
                    put("post", hydrated.optJSONObject(0) ?: JSONObject())
                }.toString()
                activity.evaluateJs("window.onClubPostSaved?.($result);")
                activity.sendToastToJs("✓ تم نشر منشورك في النادي")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emitPostSaveError(AuthErrorFormatter.format(e))
            }
        }
    }

    fun editClubPost(postId: String, jsonPayload: String) {
        val user = auth.currentUser
        if (user == null) {
            emitPostSaveError("يجب تسجيل الدخول")
            return
        }
        if (activity.currentUserBlocked) {
            emitPostSaveError("حسابك محظور من التعديل")
            return
        }
        if (activity.currentUserMuted && !activity.currentUserIsAdmin) {
            emitPostSaveError("حسابك مكتوم حالياً")
            return
        }
        val cleanId = postId.trim()
        if (cleanId.isEmpty()) return

        scope.launch {
            try {
                val cfg = loadModerationConfigFresh()
                val isAdmin = activity.currentUserIsAdmin
                val postRef = db.collection("posts").document(cleanId)
                val postSnap = postRef.get().await()
                if (!postSnap.exists()) {
                    emitPostSaveError("المنشور غير موجود")
                    return@launch
                }
                val ownerUid = postSnap.getString("authorUid") ?: ""
                if (ownerUid != user.uid && !isAdmin) {
                    emitPostSaveError("لا تملك صلاحية تعديل هذا المنشور")
                    return@launch
                }

                val userDoc = db.collection("users").document(user.uid).get().await()
                val userCreatedAt = userDoc.getLong("createdAt") ?: 0L
                val req = JSONObject(jsonPayload)
                val text = req.optString("text", "").trim().take(2000)
                val category = req.optString("category", "عام").trim().take(50).ifBlank { "عام" }
                val videoUrl = req.optString("videoUrl", "").trim().take(500)
                val videoDurationSec = req.optInt("videoDurationSec", 0).coerceAtLeast(0)
                val imagesChanged = req.optBoolean("imagesChanged", false)

                if (videoUrl.isNotEmpty()) {
                    if (!videoUrl.startsWith("https://")) {
                        emitPostSaveError("رابط الفيديو يجب أن يبدأ بـ https://")
                        return@launch
                    }
                    if (videoDurationSec > 120) {
                        emitPostSaveError("الحد الأقصى لمدة الفيديو 120 ثانية")
                        return@launch
                    }
                }

                val safetyErr = validateTextSafety(text, cfg, userCreatedAt, isAdmin)
                if (safetyErr != null) {
                    emitPostSaveError(safetyErr)
                    return@launch
                }

                val images = mutableListOf<String>()
                if (imagesChanged) {
                    val imagesArr = req.optJSONArray("images") ?: JSONArray()
                    for (i in 0 until imagesArr.length().coerceAtMost(4)) {
                        val u = imagesArr.optString(i, "").trim()
                        if (u.isNotEmpty()) {
                            if (u.length > MAX_IMAGE_DATA_URL_LEN) {
                                emitPostSaveError("إحدى الصور تتجاوز الحد الأقصى (200KB WebP)")
                                return@launch
                            }
                            images.add(u)
                        }
                    }
                }

                val themeRefId = req.optString("themeRef", "").trim().take(120)
                val themeSnapJson = req.optJSONObject("themeSnapshot")
                val themeSnapMap: Any = if (themeRefId.isNotEmpty() && themeSnapJson != null) {
                    hashMapOf(
                        "id" to themeRefId,
                        "name" to themeSnapJson.optString("name", "").take(100),
                        "category" to themeSnapJson.optString("category", "").take(50),
                        "brand" to themeSnapJson.optString("brand", "").take(40),
                        "coverUrl" to themeSnapJson.optString("coverUrl", "").take(500)
                    )
                } else com.google.firebase.firestore.FieldValue.delete()

                val mentionUids = resolveMentionsUids(text)
                val now = System.currentTimeMillis()
                val updates = hashMapOf<String, Any>(
                    "text" to text,
                    "category" to category,
                    "themeRef" to themeRefId,
                    "themeSnapshot" to themeSnapMap,
                    "videoUrl" to videoUrl,
                    "videoDurationSec" to videoDurationSec,
                    "mentions" to mentionUids,
                    "updatedAt" to now
                )
                if (imagesChanged) updates["mediaCount"] = images.size

                postRef.update(updates).await()

                if (imagesChanged) {
                    val existingMedia = postRef.collection("media").get().await()
                    val batch = db.batch()
                    existingMedia.documents.forEach { batch.delete(it.reference) }
                    images.forEachIndexed { idx, dataUrl ->
                        batch.set(
                            postRef.collection("media").document(idx.toString()),
                            hashMapOf(
                                "authorUid" to ownerUid,
                                "index" to idx,
                                "dataUrl" to dataUrl,
                                "createdAt" to now
                            )
                        )
                    }
                    batch.commit().await()
                }

                if (isAdmin && ownerUid != user.uid) {
                    communityManager.writeAuditLog("admin_edit_post", "post", cleanId, text.take(100))
                }

                val updatedDoc = postRef.get().await()
                val hydrated = hydratePosts(listOf(updatedDoc), user.uid)
                val result = JSONObject().apply {
                    put("ok", true)
                    put("mode", "edit")
                    put("post", hydrated.optJSONObject(0) ?: JSONObject())
                }.toString()
                activity.evaluateJs("window.onClubPostSaved?.($result);")
                activity.sendToastToJs("✓ تم حفظ تعديلات المنشور")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emitPostSaveError(AuthErrorFormatter.format(e))
            }
        }
    }

    private fun emitPostSaveError(error: String) {
        val payload = JSONObject().apply {
            put("ok", false)
            put("error", error)
        }.toString()
        activity.evaluateJs("window.onClubPostSaved?.($payload);")
    }

    fun deleteClubPost(postId: String) {
        val user = auth.currentUser ?: return
        val cleanId = postId.trim()
        if (cleanId.isEmpty()) return
        scope.launch {
            try {
                val postRef = db.collection("posts").document(cleanId)
                val postSnap = postRef.get().await()
                if (!postSnap.exists()) {
                    activity.evaluateJs("window.onClubPostDeleted?.({ok:true,postId:${JSONObject.quote(cleanId)}});")
                    return@launch
                }
                val ownerUid = postSnap.getString("authorUid") ?: ""
                if (ownerUid != user.uid && !activity.currentUserIsAdmin) {
                    activity.sendToastToJs("لا تملك صلاحية حذف هذا المنشور")
                    return@launch
                }

                // Delete subcollections cleanly
                val subNames = listOf("media", "likes", "pollVotes", "postReports")
                for (sub in subNames) {
                    val subDocs = runCatching { postRef.collection(sub).limit(200).get().await().documents }.getOrDefault(emptyList())
                    for (chunk in subDocs.chunked(200)) {
                        val b = db.batch()
                        chunk.forEach { b.delete(it.reference) }
                        runCatching { b.commit().await() }
                    }
                }
                val commentsSnap = runCatching { postRef.collection("comments").limit(150).get().await().documents }.getOrDefault(emptyList())
                for (c in commentsSnap) {
                    val cLikes = runCatching { c.reference.collection("likes").limit(100).get().await().documents }.getOrDefault(emptyList())
                    val replies = runCatching { c.reference.collection("replies").limit(100).get().await().documents }.getOrDefault(emptyList())
                    for (r in replies) {
                        val rLikes = runCatching { r.reference.collection("likes").limit(100).get().await().documents }.getOrDefault(emptyList())
                        if (rLikes.isNotEmpty()) {
                            val b = db.batch()
                            rLikes.forEach { b.delete(it.reference) }
                            runCatching { b.commit().await() }
                        }
                        runCatching { r.reference.delete().await() }
                    }
                    if (cLikes.isNotEmpty()) {
                        val b = db.batch()
                        cLikes.forEach { b.delete(it.reference) }
                        runCatching { b.commit().await() }
                    }
                    runCatching { c.reference.delete().await() }
                }

                postRef.delete().await()
                if (activity.currentUserIsAdmin && ownerUid != user.uid) {
                    communityManager.writeAuditLog("delete_club_post", "post", cleanId, "author=$ownerUid")
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", cleanId)
                }.toString()
                activity.evaluateJs("window.onClubPostDeleted?.($payload);")
                activity.sendToastToJs("تم حذف المنشور")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل حذف المنشور: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun togglePostReaction(postId: String, rawReaction: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التفاعل")
            return
        }
        val cleanId = postId.trim()
        val chosen = rawReaction.trim().lowercase().takeIf { it in ALLOWED_REACTIONS } ?: "like"

        scope.launch {
            try {
                val postRef = db.collection("posts").document(cleanId)
                val likeRef = postRef.collection("likes").document(user.uid)
                val now = System.currentTimeMillis()

                val res = db.runTransaction { tx ->
                    val postSnap = tx.get(postRef)
                    if (!postSnap.exists()) throw IllegalStateException("المنشور غير موجود")
                    val likeSnap = tx.get(likeRef)
                    val oldReaction = if (likeSnap.exists()) (likeSnap.getString("reaction") ?: "") else ""

                    val curLikes = (postSnap.getLong("likesCount")?.toInt() ?: 0).coerceAtLeast(0)
                    val curScore = (postSnap.getLong("engagementScore")?.toInt() ?: 0).coerceAtLeast(0)
                    val rawReactions = postSnap.get("reactions") as? Map<*, *>
                    val nextReactions = hashMapOf<String, Int>()
                    ALLOWED_REACTIONS.forEach { k ->
                        nextReactions[k] = ((rawReactions?.get(k) as? Number)?.toInt() ?: 0).coerceAtLeast(0)
                    }

                    val finalReaction: String
                    val nextLikes: Int
                    val nextScore: Int

                    when {
                        oldReaction.isEmpty() -> {
                            finalReaction = chosen
                            nextLikes = curLikes + 1
                            nextScore = curScore + 2
                            nextReactions[chosen] = (nextReactions[chosen] ?: 0) + 1
                            tx.set(likeRef, hashMapOf("uid" to user.uid, "reaction" to chosen, "updatedAt" to now))
                        }
                        oldReaction == chosen -> {
                            finalReaction = ""
                            nextLikes = (curLikes - 1).coerceAtLeast(0)
                            nextScore = (curScore - 2).coerceAtLeast(0)
                            nextReactions[oldReaction] = ((nextReactions[oldReaction] ?: 1) - 1).coerceAtLeast(0)
                            tx.delete(likeRef)
                        }
                        else -> {
                            finalReaction = chosen
                            nextLikes = curLikes
                            nextScore = curScore
                            nextReactions[oldReaction] = ((nextReactions[oldReaction] ?: 1) - 1).coerceAtLeast(0)
                            nextReactions[chosen] = (nextReactions[chosen] ?: 0) + 1
                            tx.set(likeRef, hashMapOf("uid" to user.uid, "reaction" to chosen, "updatedAt" to now))
                        }
                    }

                    tx.update(
                        postRef,
                        mapOf(
                            "likesCount" to nextLikes,
                            "engagementScore" to nextScore,
                            "reactions" to nextReactions,
                            "updatedAt" to now
                        )
                    )

                    listOf(
                        finalReaction,
                        nextLikes,
                        nextScore,
                        nextReactions,
                        postSnap.getString("authorUid") ?: "",
                        postSnap.getString("text") ?: ""
                    )
                }.await()

                val finalReaction = res[0] as String
                val nextLikes = res[1] as Int
                val nextScore = res[2] as Int
                @Suppress("UNCHECKED_CAST")
                val nextReactions = res[3] as Map<String, Int>
                val authorUid = res[4] as String
                val postText = res[5] as String

                if (finalReaction.isNotEmpty() && authorUid.isNotBlank() && authorUid != user.uid) {
                    val actorName = communityManager.fetchUserSummaries(listOf(user.uid))[user.uid]
                        ?.optString("displayName")?.ifBlank { null } ?: (user.displayName ?: "عضو")
                    val emoji = when (finalReaction) {
                        "love" -> "❤️"
                        "haha" -> "😂"
                        "wow" -> "😮"
                        "fire" -> "🔥"
                        else -> "👍"
                    }
                    communityManager.sendUserNotification(
                        targetUid = authorUid,
                        type = "post_like",
                        title = "$emoji تفاعل $actorName مع منشورك",
                        body = postText.take(120).ifBlank { "تم التفاعل مع منشورك في النادي" },
                        postId = cleanId,
                        actorUid = user.uid,
                        actorName = actorName
                    )
                }

                val rJson = JSONObject()
                nextReactions.forEach { (k, v) -> rJson.put(k, v) }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", cleanId)
                    put("myReaction", finalReaction)
                    put("likesCount", nextLikes)
                    put("engagementScore", nextScore)
                    put("reactions", rJson)
                }.toString()
                activity.evaluateJs("window.onClubPostReactionUpdated?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("تعذر تحديث التفاعل: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun votePoll(postId: String, optionIndex: Int) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التصويت")
            return
        }
        val cleanId = postId.trim()
        if (cleanId.isEmpty() || optionIndex !in 0..3) return

        scope.launch {
            try {
                val postRef = db.collection("posts").document(cleanId)
                val voteRef = postRef.collection("pollVotes").document(user.uid)
                val now = System.currentTimeMillis()

                val updatedPoll = db.runTransaction { tx ->
                    val existingVote = tx.get(voteRef)
                    if (existingVote.exists()) {
                        throw IllegalStateException("لقد قمت بالتصويت في هذا الاستطلاع مسبقاً")
                    }
                    val postSnap = tx.get(postRef)
                    if (!postSnap.exists()) throw IllegalStateException("المنشور غير موجود")
                    val rawPoll = postSnap.get("poll") as? Map<*, *>
                        ?: throw IllegalStateException("لا يوجد تصويت في هذا المنشور")
                    val rawOpts = rawPoll["options"] as? List<*> ?: emptyList<Any>()
                    if (optionIndex >= rawOpts.size) {
                        throw IllegalStateException("خيار التصويت غير صالح")
                    }
                    val nextOpts = rawOpts.mapIndexed { idx, item ->
                        val m = item as? Map<*, *> ?: emptyMap<String, Any>()
                        val curVotes = ((m["votes"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
                        hashMapOf<String, Any>(
                            "text" to ((m["text"] as? String) ?: ""),
                            "votes" to if (idx == optionIndex) curVotes + 1 else curVotes
                        )
                    }
                    val nextTotal = ((rawPoll["totalVotes"] as? Number)?.toInt() ?: 0).coerceAtLeast(0) + 1
                    val nextPoll = hashMapOf<String, Any>(
                        "question" to ((rawPoll["question"] as? String) ?: ""),
                        "options" to nextOpts,
                        "totalVotes" to nextTotal
                    )
                    tx.set(
                        voteRef,
                        hashMapOf(
                            "uid" to user.uid,
                            "optionIndex" to optionIndex,
                            "createdAt" to now
                        )
                    )
                    tx.update(postRef, "poll", nextPoll, "updatedAt", now)
                    nextPoll
                }.await()

                val optsArr = JSONArray()
                @Suppress("UNCHECKED_CAST")
                (updatedPoll["options"] as? List<Map<String, Any>>)?.forEach { opt ->
                    optsArr.put(JSONObject().apply {
                        put("text", opt["text"] ?: "")
                        put("votes", opt["votes"] ?: 0)
                    })
                }
                val pollJson = JSONObject().apply {
                    put("question", updatedPoll["question"] ?: "")
                    put("options", optsArr)
                    put("totalVotes", updatedPoll["totalVotes"] ?: 0)
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", cleanId)
                    put("myPollVote", optionIndex)
                    put("poll", pollJson)
                }.toString()
                activity.evaluateJs("window.onClubPollVoted?.($payload);")
                activity.sendToastToJs("✓ تم تسجيل صوتك")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = if (e is IllegalStateException) (e.message ?: "خطأ") else AuthErrorFormatter.format(e)
                activity.sendToastToJs(msg)
            }
        }
    }

    // ---------------- POST COMMENTS & 1-LEVEL REPLIES ----------------
    fun fetchPostComments(postId: String, beforeCreatedAt: Long) {
        val cleanId = postId.trim()
        if (cleanId.isEmpty()) return
        scope.launch {
            try {
                var query: Query = db.collection("posts").document(cleanId)
                    .collection("comments")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                if (beforeCreatedAt > 0L) query = query.whereLessThan("createdAt", beforeCreatedAt)
                val snap = query.limit((COMMENTS_PAGE_SIZE + 1).toLong()).get().await()
                val docs = snap.documents
                val hasMore = docs.size > COMMENTS_PAGE_SIZE
                val slice = if (hasMore) docs.take(COMMENTS_PAGE_SIZE) else docs

                val authorMap = communityManager.fetchUserSummaries(slice.mapNotNull { it.getString("uid") })
                val myUid = auth.currentUser?.uid
                val arr = JSONArray()
                for (d in slice) {
                    val uid = d.getString("uid") ?: ""
                    val author = authorMap[uid]
                    val likedByMe = if (!myUid.isNullOrBlank()) {
                        runCatching { d.reference.collection("likes").document(myUid).get().await().exists() }.getOrDefault(false)
                    } else false

                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("uid", uid)
                        put("name", author?.optString("displayName")?.ifBlank { null } ?: (d.getString("name") ?: "عضو"))
                        put("username", author?.optString("username") ?: "")
                        put("photo", author?.optString("photoURL") ?: "")
                        put("frame", author?.optString("frame") ?: "")
                        put("frameId", author?.optString("frameId") ?: (author?.optString("frame") ?: ""))
                        put("badges", author?.optJSONArray("badges") ?: JSONArray())
                        put("customTitle", author?.optString("customTitle") ?: "")
                        put("title", author?.optString("title") ?: "")
                        put("verified", author?.optBoolean("verified") ?: false)
                        put("badgeType", author?.optString("badgeType") ?: "")
                        put("nameColor", author?.optString("nameColor") ?: "")
                        put("nameGradient", author?.optString("nameGradient") ?: "")
                        put("nameFont", author?.optString("nameFont") ?: "")
                        put("isAdmin", author?.optBoolean("isAdmin") ?: false)
                        put("isOwner", author?.optBoolean("isOwner") ?: false)
                        put("isPublisher", author?.optBoolean("publisher") ?: false)
                        put("text", d.getString("text") ?: "")
                        put("createdAt", d.getLong("createdAt") ?: 0L)
                        put("likes", (d.getLong("likes")?.toInt() ?: 0).coerceAtLeast(0))
                        put("replyCount", (d.getLong("replyCount")?.toInt() ?: 0).coerceAtLeast(0))
                        put("likedByMe", likedByMe)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", cleanId)
                    put("append", beforeCreatedAt > 0L)
                    put("hasMore", hasMore)
                    put("comments", arr)
                }.toString()
                activity.evaluateJs("window.onClubCommentsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("postId", cleanId)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onClubCommentsLoaded?.($payload);")
            }
        }
    }

    fun addPostComment(postId: String, text: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        if (activity.currentUserBlocked) {
            emitClubCommentError("حسابك محظور من التعليق")
            return
        }
        if (activity.currentUserMuted && !activity.currentUserIsAdmin) {
            emitClubCommentError("حسابك مكتوم حالياً ولا يمكنك التعليق")
            return
        }
        val cleanId = postId.trim()
        val cleanText = text.trim().take(800)
        if (cleanId.isEmpty() || cleanText.isEmpty()) return

        scope.launch {
            try {
                val cfg = loadModerationConfigFresh()
                val userDoc = db.collection("users").document(user.uid).get().await()
                val safetyErr = validateTextSafety(
                    cleanText, cfg, userDoc.getLong("createdAt") ?: 0L, activity.currentUserIsAdmin
                )
                if (safetyErr != null) {
                    emitClubCommentError(safetyErr)
                    return@launch
                }

                val postRef = db.collection("posts").document(cleanId)
                val commentRef = postRef.collection("comments").document()
                val now = System.currentTimeMillis()
                val authorSummary = communityManager.fetchUserSummaries(listOf(user.uid))[user.uid]
                val displayName = authorSummary?.optString("displayName")?.ifBlank { null }
                    ?: (user.displayName ?: "عضو Theme Hub").take(60)
                val photo = authorSummary?.optString("photoURL") ?: (user.photoUrl?.toString() ?: "")

                val postInfo = db.runTransaction { tx ->
                    val postSnap = tx.get(postRef)
                    if (!postSnap.exists()) throw IllegalStateException("المنشور غير موجود")
                    val locked = postSnap.getBoolean("commentsLocked") ?: false
                    if (locked && !activity.currentUserIsAdmin) {
                        throw IllegalStateException("التعليقات مقفلة على هذا المنشور")
                    }
                    val nextComments = (postSnap.getLong("commentsCount")?.toInt() ?: 0).coerceAtLeast(0) + 1
                    val nextScore = (postSnap.getLong("engagementScore")?.toInt() ?: 0).coerceAtLeast(0) + 3
                    tx.set(
                        commentRef,
                        hashMapOf(
                            "uid" to user.uid,
                            "name" to displayName,
                            "text" to cleanText,
                            "createdAt" to now,
                            "likes" to 0,
                            "replyCount" to 0
                        )
                    )
                    tx.update(
                        postRef,
                        mapOf(
                            "commentsCount" to nextComments,
                            "engagementScore" to nextScore,
                            "updatedAt" to now
                        )
                    )
                    listOf(postSnap.getString("authorUid") ?: "", nextComments, nextScore)
                }.await()

                val postAuthorUid = postInfo[0] as String
                val nextCommentsCount = postInfo[1] as Int
                val nextScore = postInfo[2] as Int

                if (postAuthorUid.isNotBlank() && postAuthorUid != user.uid) {
                    communityManager.sendUserNotification(
                        targetUid = postAuthorUid,
                        type = "post_comment",
                        title = "💬 علّق $displayName على منشورك",
                        body = cleanText.take(140),
                        postId = cleanId,
                        actorUid = user.uid,
                        actorName = displayName
                    )
                }

                val mentionUids = resolveMentionsUids(cleanText)
                for (mUid in mentionUids) {
                    if (mUid != user.uid && mUid != postAuthorUid) {
                        communityManager.sendUserNotification(
                            targetUid = mUid,
                            type = "post_mention",
                            title = "📣 ذكرك $displayName في تعليق",
                            body = cleanText.take(140),
                            postId = cleanId,
                            actorUid = user.uid,
                            actorName = displayName
                        )
                    }
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", cleanId)
                    put("commentsCount", nextCommentsCount)
                    put("engagementScore", nextScore)
                    put("comment", JSONObject().apply {
                        put("id", commentRef.id)
                        put("uid", user.uid)
                        put("name", displayName)
                        put("username", authorSummary?.optString("username") ?: "")
                        put("photo", photo)
                        put("frame", authorSummary?.optString("frame") ?: "")
                        put("frameId", authorSummary?.optString("frameId") ?: (authorSummary?.optString("frame") ?: ""))
                        put("badges", authorSummary?.optJSONArray("badges") ?: JSONArray())
                        put("customTitle", authorSummary?.optString("customTitle") ?: "")
                        put("title", authorSummary?.optString("title") ?: "")
                        put("verified", authorSummary?.optBoolean("verified") ?: false)
                        put("badgeType", authorSummary?.optString("badgeType") ?: "")
                        put("nameColor", authorSummary?.optString("nameColor") ?: "")
                        put("nameGradient", authorSummary?.optString("nameGradient") ?: "")
                        put("nameFont", authorSummary?.optString("nameFont") ?: "")
                        put("isAdmin", activity.currentUserIsAdmin)
                        put("isOwner", authorSummary?.optBoolean("isOwner") ?: activity.isOwnerSession())
                        put("isPublisher", authorSummary?.optBoolean("publisher") ?: false)
                        put("text", cleanText)
                        put("createdAt", now)
                        put("likes", 0)
                        put("replyCount", 0)
                        put("likedByMe", false)
                    })
                }.toString()
                activity.evaluateJs("window.onClubCommentAdded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emitClubCommentError(if (e is IllegalStateException) (e.message ?: "خطأ") else AuthErrorFormatter.format(e))
            }
        }
    }

    private fun emitClubCommentError(err: String) {
        val payload = JSONObject().apply {
            put("ok", false)
            put("error", err)
        }.toString()
        activity.evaluateJs("window.onClubCommentAdded?.($payload);")
        activity.sendToastToJs(err)
    }

    fun deletePostComment(postId: String, commentId: String) {
        val cleanPost = postId.trim()
        val cleanComment = commentId.trim()
        if (cleanPost.isEmpty() || cleanComment.isEmpty()) return
        scope.launch {
            try {
                val postRef = db.collection("posts").document(cleanPost)
                val commentRef = postRef.collection("comments").document(cleanComment)
                val now = System.currentTimeMillis()
                val res = db.runTransaction { tx ->
                    val postSnap = tx.get(postRef)
                    val nextComments = ((postSnap.getLong("commentsCount")?.toInt() ?: 0) - 1).coerceAtLeast(0)
                    val nextScore = ((postSnap.getLong("engagementScore")?.toInt() ?: 0) - 3).coerceAtLeast(0)
                    tx.delete(commentRef)
                    if (postSnap.exists()) {
                        tx.update(
                            postRef,
                            mapOf(
                                "commentsCount" to nextComments,
                                "engagementScore" to nextScore,
                                "updatedAt" to now
                            )
                        )
                    }
                    nextComments to nextScore
                }.await()

                if (activity.currentUserIsAdmin) {
                    communityManager.writeAuditLog("delete_club_comment", "post_comment", "$cleanPost/$cleanComment", "حذف تعليق بالنادي")
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", cleanPost)
                    put("commentId", cleanComment)
                    put("commentsCount", res.first)
                    put("engagementScore", res.second)
                }.toString()
                activity.evaluateJs("window.onClubCommentDeleted?.($payload);")
                activity.sendToastToJs("تم حذف التعليق")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل حذف التعليق: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun togglePostCommentLike(postId: String, commentId: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التفاعل")
            return
        }
        scope.launch {
            try {
                val cRef = db.collection("posts").document(postId).collection("comments").document(commentId)
                val lRef = cRef.collection("likes").document(user.uid)
                val (liked, newCount) = db.runTransaction { tx ->
                    val cSnap = tx.get(cRef)
                    val lSnap = tx.get(lRef)
                    val cur = (cSnap.getLong("likes")?.toInt() ?: 0).coerceAtLeast(0)
                    if (lSnap.exists()) {
                        val next = (cur - 1).coerceAtLeast(0)
                        tx.delete(lRef)
                        tx.update(cRef, "likes", next)
                        false to next
                    } else {
                        val next = cur + 1
                        tx.set(lRef, hashMapOf("uid" to user.uid, "createdAt" to System.currentTimeMillis()))
                        tx.update(cRef, "likes", next)
                        true to next
                    }
                }.await()
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", postId)
                    put("commentId", commentId)
                    put("liked", liked)
                    put("likes", newCount)
                }.toString()
                activity.evaluateJs("window.onClubCommentLikeToggled?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("تعذر تحديث الإعجاب: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun fetchPostCommentReplies(postId: String, commentId: String) {
        scope.launch {
            try {
                val snap = db.collection("posts").document(postId)
                    .collection("comments").document(commentId)
                    .collection("replies")
                    .orderBy("createdAt", Query.Direction.ASCENDING)
                    .limit(50)
                    .get().await()
                val authorMap = communityManager.fetchUserSummaries(snap.documents.mapNotNull { it.getString("uid") })
                val myUid = auth.currentUser?.uid
                val arr = JSONArray()
                for (d in snap.documents) {
                    val uid = d.getString("uid") ?: ""
                    val author = authorMap[uid]
                    val likedByMe = if (!myUid.isNullOrBlank()) {
                        runCatching { d.reference.collection("likes").document(myUid).get().await().exists() }.getOrDefault(false)
                    } else false
                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("uid", uid)
                        put("name", author?.optString("displayName")?.ifBlank { null } ?: "عضو")
                        put("username", author?.optString("username") ?: "")
                        put("photo", author?.optString("photoURL") ?: "")
                        put("frame", author?.optString("frame") ?: "")
                        put("frameId", author?.optString("frameId") ?: (author?.optString("frame") ?: ""))
                        put("badges", author?.optJSONArray("badges") ?: JSONArray())
                        put("customTitle", author?.optString("customTitle") ?: "")
                        put("title", author?.optString("title") ?: "")
                        put("verified", author?.optBoolean("verified") ?: false)
                        put("badgeType", author?.optString("badgeType") ?: "")
                        put("nameColor", author?.optString("nameColor") ?: "")
                        put("nameGradient", author?.optString("nameGradient") ?: "")
                        put("nameFont", author?.optString("nameFont") ?: "")
                        put("isAdmin", author?.optBoolean("isAdmin") ?: false)
                        put("isOwner", author?.optBoolean("isOwner") ?: false)
                        put("isPublisher", author?.optBoolean("publisher") ?: false)
                        put("text", d.getString("text") ?: "")
                        put("createdAt", d.getLong("createdAt") ?: 0L)
                        put("likes", (d.getLong("likes")?.toInt() ?: 0).coerceAtLeast(0))
                        put("likedByMe", likedByMe)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", postId)
                    put("commentId", commentId)
                    put("replies", arr)
                }.toString()
                activity.evaluateJs("window.onClubRepliesLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("postId", postId)
                    put("commentId", commentId)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onClubRepliesLoaded?.($payload);")
            }
        }
    }

    fun addPostCommentReply(postId: String, commentId: String, text: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التعليق")
            return
        }
        if (activity.currentUserMuted && !activity.currentUserIsAdmin) {
            activity.sendToastToJs("حسابك مكتوم حالياً")
            return
        }
        val cleanText = text.trim().take(800)
        if (cleanText.isEmpty()) return

        scope.launch {
            try {
                val cfg = loadModerationConfigFresh()
                val userDoc = db.collection("users").document(user.uid).get().await()
                val safetyErr = validateTextSafety(
                    cleanText, cfg, userDoc.getLong("createdAt") ?: 0L, activity.currentUserIsAdmin
                )
                if (safetyErr != null) {
                    activity.sendToastToJs(safetyErr)
                    return@launch
                }

                val cRef = db.collection("posts").document(postId)
                    .collection("comments").document(commentId)
                val rRef = cRef.collection("replies").document()
                val now = System.currentTimeMillis()
                val author = communityManager.fetchUserSummaries(listOf(user.uid))[user.uid]
                val displayName = author?.optString("displayName")?.ifBlank { null }
                    ?: (user.displayName ?: "عضو").take(60)

                val txRes = db.runTransaction { tx ->
                    val cSnap = tx.get(cRef)
                    val nextCount = (cSnap.getLong("replyCount")?.toInt() ?: 0).coerceAtLeast(0) + 1
                    tx.set(
                        rRef,
                        hashMapOf(
                            "uid" to user.uid,
                            "text" to cleanText,
                            "createdAt" to now,
                            "likes" to 0
                        )
                    )
                    tx.update(cRef, "replyCount", nextCount)
                    (cSnap.getString("uid") ?: "") to nextCount
                }.await()

                val commentAuthorUid = txRes.first
                val newCount = txRes.second
                if (commentAuthorUid.isNotBlank() && commentAuthorUid != user.uid) {
                    communityManager.sendUserNotification(
                        targetUid = commentAuthorUid,
                        type = "post_reply",
                        title = "↩️ ردّ $displayName على تعليقك",
                        body = cleanText.take(140),
                        postId = postId,
                        actorUid = user.uid,
                        actorName = displayName
                    )
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", postId)
                    put("commentId", commentId)
                    put("replyCount", newCount)
                    put("reply", JSONObject().apply {
                        put("id", rRef.id)
                        put("uid", user.uid)
                        put("name", displayName)
                        put("username", author?.optString("username") ?: "")
                        put("photo", author?.optString("photoURL") ?: "")
                        put("frame", author?.optString("frame") ?: "")
                        put("frameId", author?.optString("frameId") ?: (author?.optString("frame") ?: ""))
                        put("badges", author?.optJSONArray("badges") ?: JSONArray())
                        put("customTitle", author?.optString("customTitle") ?: "")
                        put("title", author?.optString("title") ?: "")
                        put("verified", author?.optBoolean("verified") ?: false)
                        put("badgeType", author?.optString("badgeType") ?: "")
                        put("nameColor", author?.optString("nameColor") ?: "")
                        put("nameGradient", author?.optString("nameGradient") ?: "")
                        put("nameFont", author?.optString("nameFont") ?: "")
                        put("isAdmin", activity.currentUserIsAdmin)
                        put("isOwner", author?.optBoolean("isOwner") ?: activity.isOwnerSession())
                        put("isPublisher", author?.optBoolean("publisher") ?: false)
                        put("text", cleanText)
                        put("createdAt", now)
                        put("likes", 0)
                        put("likedByMe", false)
                    })
                }.toString()
                activity.evaluateJs("window.onClubReplyAdded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("تعذر إضافة الرد: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun deletePostCommentReply(postId: String, commentId: String, replyId: String) {
        scope.launch {
            try {
                val cRef = db.collection("posts").document(postId)
                    .collection("comments").document(commentId)
                val rRef = cRef.collection("replies").document(replyId)
                val nextCount = db.runTransaction { tx ->
                    val cSnap = tx.get(cRef)
                    val next = ((cSnap.getLong("replyCount")?.toInt() ?: 0) - 1).coerceAtLeast(0)
                    tx.delete(rRef)
                    tx.update(cRef, "replyCount", next)
                    next
                }.await()
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", postId)
                    put("commentId", commentId)
                    put("replyId", replyId)
                    put("replyCount", nextCount)
                }.toString()
                activity.evaluateJs("window.onClubReplyDeleted?.($payload);")
                activity.sendToastToJs("تم حذف الرد")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل حذف الرد: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun togglePostCommentReplyLike(postId: String, commentId: String, replyId: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التفاعل")
            return
        }
        scope.launch {
            try {
                val rRef = db.collection("posts").document(postId)
                    .collection("comments").document(commentId)
                    .collection("replies").document(replyId)
                val lRef = rRef.collection("likes").document(user.uid)
                val (liked, newCount) = db.runTransaction { tx ->
                    val rSnap = tx.get(rRef)
                    val lSnap = tx.get(lRef)
                    val cur = (rSnap.getLong("likes")?.toInt() ?: 0).coerceAtLeast(0)
                    if (lSnap.exists()) {
                        val next = (cur - 1).coerceAtLeast(0)
                        tx.delete(lRef)
                        tx.update(rRef, "likes", next)
                        false to next
                    } else {
                        val next = cur + 1
                        tx.set(lRef, hashMapOf("uid" to user.uid, "createdAt" to System.currentTimeMillis()))
                        tx.update(rRef, "likes", next)
                        true to next
                    }
                }.await()
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", postId)
                    put("commentId", commentId)
                    put("replyId", replyId)
                    put("liked", liked)
                    put("likes", newCount)
                }.toString()
                activity.evaluateJs("window.onClubReplyLikeToggled?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("تعذر تحديث الإعجاب: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    // ---------------- FOLLOW, BOOKMARKS & REPORTS ----------------
    fun toggleFollowUser(rawTargetUid: String, follow: Boolean) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التفاعل")
            return
        }
        val targetUid = rawTargetUid.trim()
        if (targetUid.isEmpty() || targetUid == user.uid) return

        scope.launch {
            try {
                val followingRef = db.collection("follows").document(user.uid)
                    .collection("following").document(targetUid)
                val followerRef = db.collection("follows").document(targetUid)
                    .collection("followers").document(user.uid)
                val now = System.currentTimeMillis()
                val batch = db.batch()
                if (follow) {
                    batch.set(followingRef, hashMapOf("uid" to targetUid, "createdAt" to now))
                    batch.set(followerRef, hashMapOf("uid" to user.uid, "createdAt" to now))
                } else {
                    batch.delete(followingRef)
                    batch.delete(followerRef)
                }
                batch.commit().await()

                if (follow) {
                    val myName = communityManager.fetchUserSummaries(listOf(user.uid))[user.uid]
                        ?.optString("displayName")?.ifBlank { null } ?: (user.displayName ?: "عضو")
                    communityManager.sendUserNotification(
                        targetUid = targetUid,
                        type = "follow",
                        title = "👤 بدأ $myName بمتابعتك",
                        body = "اضغط لعرض ملفه الشخصي في النادي",
                        actorUid = user.uid,
                        actorName = myName
                    )
                }

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("targetUid", targetUid)
                    put("following", follow)
                }.toString()
                activity.evaluateJs("window.onUserFollowToggled?.($payload);")
                activity.sendToastToJs(if (follow) "✓ تمت المتابعة" else "تم إلغاء المتابعة")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().apply {
                    put("ok", false)
                    put("targetUid", targetUid)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onUserFollowToggled?.($payload);")
            }
        }
    }

    fun toggleBookmarkPost(postId: String, bookmarked: Boolean) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        val cleanId = postId.trim()
        if (cleanId.isEmpty()) return
        scope.launch {
            try {
                val ref = db.collection("bookmarks").document(user.uid)
                    .collection("posts").document(cleanId)
                if (bookmarked) {
                    ref.set(hashMapOf("postId" to cleanId, "createdAt" to System.currentTimeMillis())).await()
                } else {
                    ref.delete().await()
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("postId", cleanId)
                    put("bookmarked", bookmarked)
                }.toString()
                activity.evaluateJs("window.onClubBookmarkToggled?.($payload);")
                activity.sendToastToJs(if (bookmarked) "🔖 تم حفظ المنشور في المحفوظات" else "تمت الإزالة من المحفوظات")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("تعذر تحديث الحفظ: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun reportClubTarget(targetType: String, targetId: String, parentId: String, reason: String) {
        val user = auth.currentUser
        if (user == null) {
            activity.evaluateJs("window.onClubAuthRequired?.();")
            return
        }
        if (activity.currentUserBlocked) {
            activity.sendToastToJs("حسابك محظور من التفاعل")
            return
        }
        val cleanType = targetType.trim().take(40)
        val cleanTarget = targetId.trim().take(120)
        val cleanParent = parentId.trim().take(120)
        val cleanReason = reason.trim().take(300)
        if (cleanTarget.isEmpty() || cleanReason.isEmpty()) return

        scope.launch {
            try {
                val now = System.currentTimeMillis()
                var autoHidden = false

                if (cleanType == "post") {
                    val postRef = db.collection("posts").document(cleanTarget)
                    val postReportRef = postRef.collection("postReports").document(user.uid)
                    autoHidden = db.runTransaction { tx ->
                        val prSnap = tx.get(postReportRef)
                        if (prSnap.exists()) {
                            throw IllegalStateException("لقد أبلغت عن هذا المنشور مسبقاً")
                        }
                        val postSnap = tx.get(postRef)
                        if (!postSnap.exists()) throw IllegalStateException("المنشور غير موجود")
                        val curReports = (postSnap.getLong("reportsCount")?.toInt() ?: 0).coerceAtLeast(0)
                        val nextReports = curReports + 1
                        val nextStatus = if (nextReports >= 5) "hidden" else (postSnap.getString("status") ?: "visible")
                        tx.set(
                            postReportRef,
                            hashMapOf(
                                "reporterUid" to user.uid,
                                "reason" to cleanReason,
                                "createdAt" to now
                            )
                        )
                        tx.update(
                            postRef,
                            mapOf(
                                "reportsCount" to nextReports,
                                "status" to nextStatus,
                                "updatedAt" to now
                            )
                        )
                        nextReports >= 5
                    }.await()
                }

                db.collection("reports").add(
                    hashMapOf(
                        "reporterUid" to user.uid,
                        "targetType" to cleanType,
                        "targetId" to cleanTarget,
                        "parentId" to cleanParent,
                        "reason" to cleanReason,
                        "status" to "open",
                        "createdAt" to now
                    )
                ).await()

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("targetType", cleanType)
                    put("targetId", cleanTarget)
                    put("autoHidden", autoHidden)
                }.toString()
                activity.evaluateJs("window.onClubReportSubmitted?.($payload);")
                activity.sendToastToJs(
                    if (autoHidden) "✓ تم إرسال البلاغ وإخفاء المنشور تلقائياً للمراجعة"
                    else "✓ تم إرسال البلاغ للمشرفين"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = if (e is IllegalStateException) (e.message ?: "خطأ") else AuthErrorFormatter.format(e)
                activity.sendToastToJs(msg)
            }
        }
    }

    // ---------------- ADMIN CLUB MODERATION ----------------
    fun adminFetchClubPosts(statusFilter: String) {
        if (!activity.currentUserIsAdmin) {
            val err = JSONObject().apply {
                put("ok", false)
                put("error", "غير مصرح: إدارة النادي متاحة للمشرفين فقط")
            }.toString()
            activity.evaluateJs("window.onAdminClubPostsLoaded?.($err);")
            return
        }
        scope.launch {
            try {
                val snap = db.collection("posts")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(60)
                    .get().await()
                val filtered = snap.documents.filter { d ->
                    val st = d.getString("status") ?: "visible"
                    when (statusFilter) {
                        "visible" -> st == "visible"
                        "hidden" -> st == "hidden"
                        "pinned" -> d.getBoolean("pinned") ?: false
                        "reported" -> (d.getLong("reportsCount") ?: 0L) > 0L
                        else -> true
                    }
                }
                val hydrated = hydratePosts(filtered.take(35), auth.currentUser?.uid)
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("statusFilter", statusFilter)
                    put("posts", hydrated)
                }.toString()
                activity.evaluateJs("window.onAdminClubPostsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminClubPostsLoaded?.($err);")
            }
        }
    }

    fun adminModerateClubPost(postId: String, action: String, payloadJson: String) {
        if (!activity.currentUserIsAdmin || postId.isBlank()) return
        if (action == "delete_post") {
            deleteClubPost(postId)
            return
        }
        scope.launch {
            try {
                val data = runCatching { JSONObject(payloadJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                val ref = db.collection("posts").document(postId)
                val now = System.currentTimeMillis()
                val updates = hashMapOf<String, Any>("updatedAt" to now)
                when (action) {
                    "set_pin" -> {
                        val pinned = data.optBoolean("pinned", false)
                        updates["pinned"] = pinned
                        ref.update(updates).await()
                        communityManager.writeAuditLog(if (pinned) "pin_post" else "unpin_post", "post", postId, "pinned=$pinned")
                    }
                    "set_status" -> {
                        val status = if (data.optString("status") == "hidden") "hidden" else "visible"
                        updates["status"] = status
                        if (status == "visible") updates["reportsCount"] = 0
                        ref.update(updates).await()
                        communityManager.writeAuditLog("set_post_status", "post", postId, "status=$status")
                    }
                    "toggle_lock" -> {
                        val locked = data.optBoolean("locked", false)
                        updates["commentsLocked"] = locked
                        ref.update(updates).await()
                        communityManager.writeAuditLog(if (locked) "lock_post_comments" else "unlock_post_comments", "post", postId, "locked=$locked")
                    }
                    "edit_post" -> {
                        val newText = data.optString("text", "").trim().take(2000)
                        val newCat = data.optString("category", "").trim().take(50)
                        updates["text"] = newText
                        if (newCat.isNotEmpty()) updates["category"] = newCat
                        ref.update(updates).await()
                        communityManager.writeAuditLog("admin_edit_post", "post", postId, newText.take(100))
                    }
                }
                val res = JSONObject().apply {
                    put("ok", true)
                    put("postId", postId)
                    put("action", action)
                }.toString()
                activity.evaluateJs("window.onAdminClubPostModerated?.($res);")
                activity.sendToastToJs("✓ تم تحديث حالة المنشور")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل الإجراء: ${AuthErrorFormatter.format(e)}")
            }
        }
    }

    fun adminFetchClubReports() {
        if (!activity.currentUserIsAdmin) {
            val err = JSONObject().apply {
                put("ok", false)
                put("error", "غير مصرح")
            }.toString()
            activity.evaluateJs("window.onAdminClubReportsLoaded?.($err);")
            return
        }
        scope.launch {
            try {
                val reportsSnap = db.collection("reports")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(80)
                    .get().await()
                val clubReportTypes = setOf("post", "post_comment", "post_reply")
                val arr = JSONArray()
                reportsSnap.documents
                    .filter { (it.getString("targetType") ?: "") in clubReportTypes }
                    .forEach { d ->
                        arr.put(JSONObject().apply {
                            put("id", d.id)
                            put("reporterUid", d.getString("reporterUid") ?: "")
                            put("targetType", d.getString("targetType") ?: "")
                            put("targetId", d.getString("targetId") ?: "")
                            put("parentId", d.getString("parentId") ?: "")
                            put("reason", d.getString("reason") ?: "")
                            put("status", d.getString("status") ?: "open")
                            put("createdAt", d.getLong("createdAt") ?: 0L)
                        })
                    }

                val reportedPostsSnap = runCatching {
                    db.collection("posts")
                        .whereGreaterThan("reportsCount", 0)
                        .limit(30)
                        .get().await().documents
                }.getOrDefault(emptyList())
                val postsArr = hydratePosts(reportedPostsSnap, auth.currentUser?.uid)

                val payload = JSONObject().apply {
                    put("ok", true)
                    put("reports", arr)
                    put("reportedPosts", postsArr)
                }.toString()
                activity.evaluateJs("window.onAdminClubReportsLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAdminClubReportsLoaded?.($err);")
            }
        }
    }

    fun adminSaveModerationConfig(jsonPayload: String) {
        if (!activity.currentUserIsAdmin) {
            activity.sendToastToJs("غير مصرح")
            return
        }
        scope.launch {
            try {
                val req = JSONObject(jsonPayload)
                val catsArr = req.optJSONArray("categories") ?: JSONArray()
                val cats = mutableListOf<String>()
                for (i in 0 until catsArr.length()) {
                    val s = catsArr.optString(i, "").trim().take(50)
                    if (s.isNotEmpty() && s !in cats) cats.add(s)
                }
                if (cats.isEmpty()) cats.addAll(DEFAULT_CATEGORIES)

                val bannedArr = req.optJSONArray("bannedWords") ?: JSONArray()
                val banned = mutableListOf<String>()
                for (i in 0 until bannedArr.length()) {
                    val s = bannedArr.optString(i, "").trim().lowercase().take(60)
                    if (s.isNotEmpty() && s !in banned) banned.add(s)
                }

                val domainsArr = req.optJSONArray("allowedDomains") ?: JSONArray()
                val domains = mutableListOf<String>()
                for (i in 0 until domainsArr.length()) {
                    val s = domainsArr.optString(i, "").trim().lowercase().removePrefix("www.").take(80)
                    if (s.isNotEmpty() && s !in domains) domains.add(s)
                }

                val clubEnabled = req.optBoolean("clubEnabled", true)
                val maintenanceMessage = req.optString("maintenanceMessage", "").trim().take(300)
                val maxDailyPosts = req.optInt("maxDailyPosts", 10).coerceIn(1, 100)
                val newUserDailyPosts = req.optInt("newUserDailyPosts", 3).coerceIn(1, 50)
                val postCooldownSec = req.optInt("postCooldownSec", 30).coerceIn(0, 600)
                val allowImages = req.optBoolean("allowImages", true)
                val allowVideos = req.optBoolean("allowVideos", true)
                val allowLinks = req.optBoolean("allowLinks", true)

                val data = hashMapOf<String, Any>(
                    "clubEnabled" to clubEnabled,
                    "maintenanceMessage" to maintenanceMessage,
                    "categories" to cats,
                    "bannedWords" to banned,
                    "maxDailyPosts" to maxDailyPosts,
                    "newUserDailyPosts" to newUserDailyPosts,
                    "postCooldownSec" to postCooldownSec,
                    "allowImages" to allowImages,
                    "allowVideos" to allowVideos,
                    "allowLinks" to allowLinks,
                    "allowedDomains" to domains,
                    "updatedAt" to System.currentTimeMillis()
                )
                db.collection("config").document("moderation").set(data, SetOptions.merge()).await()
                cachedConfig = ClubModerationConfig(
                    clubEnabled, maintenanceMessage, cats, banned,
                    maxDailyPosts, newUserDailyPosts, postCooldownSec,
                    allowImages, allowVideos, allowLinks, domains
                )
                communityManager.writeAuditLog("update_club_moderation", "config", "moderation", "clubEnabled=$clubEnabled")

                val res = configToJson(cachedConfig).apply { put("ok", true) }.toString()
                activity.evaluateJs("window.onClubConfigLoaded?.($res);")
                activity.evaluateJs("window.onAdminClubConfigSaved?.($res);")
                activity.sendToastToJs("✓ تم حفظ إعدادات النادي")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                activity.sendToastToJs("فشل حفظ إعدادات النادي: ${AuthErrorFormatter.format(e)}")
            }
        }
    }
}
