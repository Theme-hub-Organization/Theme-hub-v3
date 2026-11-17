package com.example

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
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

class AppThemeDesignerManager(
    private val activity: MainActivity,
    private val scope: CoroutineScope
) {
    private val db get() = Firebase.firestore
    private val auth get() = Firebase.auth

    private var appThemeListener: ListenerRegistration? = null
    @Volatile
    private var lastCloudThemePayloadJson: String? = null

    companion object {
        private const val TAG = "ThemeHubDesigner"
        private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")
        private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{2,48}$")

        private val ALLOWED_FONTS = setOf(
            "Cairo", "Tajawal", "Almarai", "IBM Plex Sans Arabic",
            "Nunito", "Quicksand", "Inter", "Space Grotesk", "Playfair Display"
        )
        private val ALLOWED_CARD_STYLES = setOf("glass", "flat", "outlined", "neon", "soft")
        private val ALLOWED_SHADOWS = setOf("none", "soft", "glow")
        private val ALLOWED_SPACING = setOf("compact", "cozy", "airy")
        private val ALLOWED_ICON_STYLES = setOf("outline", "filled", "duotone", "rounded")
        private val ALLOWED_NAV_TYPES = setOf(
            "bottom-floating", "bottom-bar", "top-tabs", "side-drawer", "rail-left", "dock-center-fab"
        )
        private val ALLOWED_NAV_ACTIVE = setOf("filled", "glass-capsule")
        private val ALLOWED_NAV_ORDER = setOf("home", "explore", "club", "favorites", "downloads", "messages", "settings")
        private val ALLOWED_NAV_TABS = setOf("themes", "icon_pack", "live_wallpaper", "font", "aod", "ringtone")
        private val ALLOWED_HEADERS = setOf("large-title", "compact", "none", "hero-profile")
        private val ALLOWED_SEARCH_STYLES = setOf("card", "pill-bar", "icon-only", "full-width-top")
        private val ALLOWED_SECTION_LAYOUTS = setOf(
            "vertical-grid", "horizontal-rows", "magazine", "stories-top", "masonry", "list"
        )
        private val ALLOWED_HERO_STYLES = setOf("carousel", "big-card", "none")
        private val ALLOWED_HOME_SECTIONS = setOf(
            "search", "deviceDetector", "brandChips", "banner",
            "categories", "topDownloads", "featured", "newReleases"
        )
        private val ALLOWED_GRID_LAYOUTS = setOf("grid2", "grid3", "list", "masonry", "carousel")
        private val ALLOWED_CARD_INFO = setOf(
            "overlay-title", "below-title", "minimal", "tall-9-16", "square", "wide-banner", "list-row", "polaroid"
        )
        private val ALLOWED_DETAILS_LAYOUT = setOf(
            "classic", "hero-gallery", "split-sheet", "fullscreen-phone", "tabs"
        )
        private val ALLOWED_DETAILS_STATS = setOf("three-cards", "inline")
        private val ALLOWED_ACTION_BAR = setOf("classic-bottom", "floating-wide", "side-fab", "top-inline")
        private val ALLOWED_DESC_STYLE = setOf("card", "plain")
        private val ALLOWED_CLUB_FEED = setOf("cards", "flat-list", "compact", "timeline", "magazine")
        private val ALLOWED_PROFILE_LAYOUT = setOf("banner-top", "centered", "side-card", "cover-overlap")
        private val ALLOWED_PHONE_FRAMES = setOf("gold", "titanium", "black", "pharaoh", "none")
        private val ALLOWED_ANIMATIONS = setOf("none", "smooth", "playful", "snappy")
        private val ALLOWED_TRANSITIONS = setOf("slide", "fade", "zoom", "shared-element")
        private val ALLOWED_MODES = setOf("dark", "light", "auto")
        private val ALLOWED_GLASS_CRACKS = setOf("none", "light", "heavy")

        private val ALLOWED_FRAME_TYPES = setOf("solid", "gradient", "conic", "animated", "pattern")
        private val ALLOWED_FRAME_ANIMS = setOf("none", "spin", "pulse", "shimmer", "breathe")
        private val ALLOWED_FRAME_ORNAMENTS = setOf(
            "none", "crown", "wings", "stars", "flames", "lotus", "laurel", "eye-of-horus", "cartouche", "pyramid"
        )
        private val ALLOWED_FRAME_SHAPES = setOf("circle", "rounded-square", "hexagon")
        private val ALLOWED_FRAME_ACCESS = setOf("everyone", "admins", "owner", "assigned")
    }

    fun startAppThemeListener() {
        replayLastThemeToWebView()
        if (appThemeListener != null) return

        appThemeListener = db.collection("config").document("appTheme")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "AppTheme snapshot error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    lastCloudThemePayloadJson = ""
                    activity.evaluateJs("window.onCloudAppThemeUpdated?.(null);")
                    return@addSnapshotListener
                }

                val rawJson = snapshot.getString("themeJson")
                val candidate = if (!rawJson.isNullOrBlank()) {
                    runCatching { JSONObject(rawJson) }.getOrNull()
                } else {
                    runCatching { JSONObject(snapshot.data ?: emptyMap<String, Any>()) }.getOrNull()
                }

                if (candidate == null) {
                    lastCloudThemePayloadJson = ""
                    activity.evaluateJs("window.onCloudAppThemeUpdated?.(null);")
                    return@addSnapshotListener
                }

                val sanitized = sanitizeThemeConfig(candidate)
                val cleanStr = sanitized.toString()
                lastCloudThemePayloadJson = cleanStr
                activity.evaluateJs("window.onCloudAppThemeUpdated?.(${JSONObject.quote(cleanStr)});")
            }
    }

    fun replayLastThemeToWebView() {
        val cached = lastCloudThemePayloadJson ?: return
        if (cached.isEmpty()) {
            activity.evaluateJs("window.onCloudAppThemeUpdated?.(null);")
        } else {
            activity.evaluateJs("window.onCloudAppThemeUpdated?.(${JSONObject.quote(cached)});")
        }
    }

    fun fetchAppThemeNow() {
        scope.launch {
            try {
                val snap = db.collection("config").document("appTheme").get().await()
                if (!snap.exists()) {
                    lastCloudThemePayloadJson = ""
                    activity.evaluateJs("window.onCloudAppThemeUpdated?.(null);")
                    return@launch
                }
                val rawJson = snap.getString("themeJson")
                val candidate = if (!rawJson.isNullOrBlank()) {
                    runCatching { JSONObject(rawJson) }.getOrNull()
                } else {
                    runCatching { JSONObject(snap.data ?: emptyMap<String, Any>()) }.getOrNull()
                }
                if (candidate == null) {
                    activity.evaluateJs("window.onCloudAppThemeUpdated?.(null);")
                    return@launch
                }
                val cleanStr = sanitizeThemeConfig(candidate).toString()
                lastCloudThemePayloadJson = cleanStr
                activity.evaluateJs("window.onCloudAppThemeUpdated?.(${JSONObject.quote(cleanStr)});")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "fetchAppThemeNow failed", e)
            }
        }
    }

    fun detachListener() {
        runCatching { appThemeListener?.remove() }
        appThemeListener = null
    }

    fun publishAppTheme(rawJsonPayload: String) {
        if (!activity.currentUserIsAdmin) {
            val err = JSONObject().put("ok", false).put("error", "صلاحية المشرف مطلوبة لنشر القالب على الجميع").toString()
            activity.evaluateJs("window.onAppThemePublished?.($err);")
            return
        }

        val parsed = runCatching { JSONObject(rawJsonPayload) }.getOrNull()
        if (parsed == null) {
            val err = JSONObject().put("ok", false).put("error", "صيغة القالب غير صالحة").toString()
            activity.evaluateJs("window.onAppThemePublished?.($err);")
            return
        }

        val sanitized = sanitizeThemeConfig(parsed)
        val cleanJson = sanitized.toString()
        val templateId = sanitized.optString("id", "custom")
        val templateName = sanitized.optString("name", "قالب مخصص")
        val userEmail = auth.currentUser?.email?.lowercase().orEmpty()
        val userName = auth.currentUser?.displayName.orEmpty().ifBlank { userEmail }
        val now = System.currentTimeMillis()

        scope.launch {
            try {
                val docData = hashMapOf(
                    "version" to sanitized.optInt("version", 1),
                    "templateId" to templateId,
                    "templateName" to templateName,
                    "themeJson" to cleanJson,
                    "updatedAt" to now,
                    "updatedByEmail" to userEmail,
                    "updatedByName" to userName
                )
                db.collection("config").document("appTheme")
                    .set(docData, SetOptions.merge())
                    .await()

                val histData = hashMapOf(
                    "templateId" to templateId,
                    "templateName" to templateName,
                    "themeJson" to cleanJson,
                    "savedAt" to now,
                    "savedByEmail" to userEmail,
                    "savedByName" to userName
                )
                db.collection("appThemeHistory").add(histData).await()
                pruneHistoryToTen()

                lastCloudThemePayloadJson = cleanJson
                val ok = JSONObject().apply {
                    put("ok", true)
                    put("templateId", templateId)
                    put("templateName", templateName)
                    put("updatedAt", now)
                }.toString()
                activity.evaluateJs("window.onAppThemePublished?.($ok);")
                fetchAppThemeHistory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "publishAppTheme failed", e)
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAppThemePublished?.($err);")
            }
        }
    }

    private suspend fun pruneHistoryToTen() {
        runCatching {
            val snap = db.collection("appThemeHistory")
                .orderBy("savedAt", Query.Direction.DESCENDING)
                .get()
                .await()
            if (snap.documents.size > 10) {
                snap.documents.drop(10).forEach { oldDoc ->
                    runCatching { oldDoc.reference.delete().await() }
                }
            }
        }
    }

    fun fetchAppThemeHistory() {
        if (!activity.currentUserIsAdmin) return
        scope.launch {
            try {
                val snap = db.collection("appThemeHistory")
                    .orderBy("savedAt", Query.Direction.DESCENDING)
                    .limit(10)
                    .get()
                    .await()
                val arr = JSONArray()
                snap.documents.forEach { doc ->
                    val obj = JSONObject().apply {
                        put("id", doc.id)
                        put("templateId", doc.getString("templateId").orEmpty())
                        put("templateName", doc.getString("templateName").orEmpty())
                        put("savedAt", doc.getLong("savedAt") ?: 0L)
                        put("savedByEmail", doc.getString("savedByEmail").orEmpty())
                        put("savedByName", doc.getString("savedByName").orEmpty())
                        put("themeJson", doc.getString("themeJson").orEmpty())
                    }
                    arr.put(obj)
                }
                val res = JSONObject().apply {
                    put("ok", true)
                    put("history", arr)
                }.toString()
                activity.evaluateJs("window.onAppThemeHistoryLoaded?.($res);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAppThemeHistoryLoaded?.($err);")
            }
        }
    }

    fun restoreAppThemeHistory(historyId: String) {
        if (!activity.currentUserIsAdmin || historyId.isBlank()) return
        scope.launch {
            try {
                val doc = db.collection("appThemeHistory").document(historyId).get().await()
                if (!doc.exists()) {
                    val err = JSONObject().put("ok", false).put("error", "النسخة المطلوبة غير موجودة").toString()
                    activity.evaluateJs("window.onAppThemeRestored?.($err);")
                    return@launch
                }
                val rawJson = doc.getString("themeJson").orEmpty()
                val parsed = runCatching { JSONObject(rawJson) }.getOrNull()
                if (parsed == null) {
                    val err = JSONObject().put("ok", false).put("error", "بيانات النسخة غير صالحة").toString()
                    activity.evaluateJs("window.onAppThemeRestored?.($err);")
                    return@launch
                }
                val sanitized = sanitizeThemeConfig(parsed)
                val cleanJson = sanitized.toString()
                val templateId = sanitized.optString("id", "restored")
                val templateName = sanitized.optString("name", "قالب مسترجع")
                val userEmail = auth.currentUser?.email?.lowercase().orEmpty()
                val userName = auth.currentUser?.displayName.orEmpty().ifBlank { userEmail }
                val now = System.currentTimeMillis()

                db.collection("config").document("appTheme")
                    .set(
                        hashMapOf(
                            "version" to sanitized.optInt("version", 1),
                            "templateId" to templateId,
                            "templateName" to templateName,
                            "themeJson" to cleanJson,
                            "updatedAt" to now,
                            "updatedByEmail" to userEmail,
                            "updatedByName" to userName
                        ),
                        SetOptions.merge()
                    )
                    .await()

                lastCloudThemePayloadJson = cleanJson
                val ok = JSONObject().apply {
                    put("ok", true)
                    put("templateName", templateName)
                    put("themeJson", cleanJson)
                }.toString()
                activity.evaluateJs("window.onAppThemeRestored?.($ok);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onAppThemeRestored?.($err);")
            }
        }
    }

    fun fetchCustomTemplates() {
        if (!activity.currentUserIsAdmin) return
        scope.launch {
            try {
                val snap = db.collection("templates")
                    .orderBy("updatedAt", Query.Direction.DESCENDING)
                    .limit(40)
                    .get()
                    .await()
                val arr = JSONArray()
                snap.documents.forEach { doc ->
                    val rawJson = doc.getString("themeJson").orEmpty()
                    val parsed = runCatching { JSONObject(rawJson) }.getOrNull() ?: return@forEach
                    val cleanObj = sanitizeThemeConfig(parsed)
                    val wrapper = JSONObject().apply {
                        put("docId", doc.id)
                        put("updatedAt", doc.getLong("updatedAt") ?: 0L)
                        put("createdByEmail", doc.getString("createdByEmail").orEmpty())
                        put("template", cleanObj)
                    }
                    arr.put(wrapper)
                }
                val res = JSONObject().apply {
                    put("ok", true)
                    put("templates", arr)
                }.toString()
                activity.evaluateJs("window.onCustomTemplatesLoaded?.($res);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onCustomTemplatesLoaded?.($err);")
            }
        }
    }

    fun saveCustomTemplate(rawJsonPayload: String) {
        if (!activity.currentUserIsAdmin) return
        val parsed = runCatching { JSONObject(rawJsonPayload) }.getOrNull()
        if (parsed == null) {
            val err = JSONObject().put("ok", false).put("error", "صيغة القالب غير صالحة").toString()
            activity.evaluateJs("window.onCustomTemplateSaved?.($err);")
            return
        }
        val sanitized = sanitizeThemeConfig(parsed)
        val docId = sanitized.optString("id", "").takeIf { SAFE_ID_REGEX.matches(it) }
            ?: "tpl_${System.currentTimeMillis()}"
        sanitized.put("id", docId)
        val cleanJson = sanitized.toString()
        val now = System.currentTimeMillis()
        val userEmail = auth.currentUser?.email?.lowercase().orEmpty()

        scope.launch {
            try {
                db.collection("templates").document(docId)
                    .set(
                        hashMapOf(
                            "id" to docId,
                            "name" to sanitized.optString("name", "قالب مخصص"),
                            "themeJson" to cleanJson,
                            "updatedAt" to now,
                            "createdByEmail" to userEmail
                        ),
                        SetOptions.merge()
                    )
                    .await()

                val res = JSONObject().apply {
                    put("ok", true)
                    put("docId", docId)
                    put("template", sanitized)
                }.toString()
                activity.evaluateJs("window.onCustomTemplateSaved?.($res);")
                fetchCustomTemplates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onCustomTemplateSaved?.($err);")
            }
        }
    }

    fun deleteCustomTemplate(templateId: String) {
        if (!activity.currentUserIsAdmin || !SAFE_ID_REGEX.matches(templateId)) return
        scope.launch {
            try {
                db.collection("templates").document(templateId).delete().await()
                val res = JSONObject().apply {
                    put("ok", true)
                    put("templateId", templateId)
                }.toString()
                activity.evaluateJs("window.onCustomTemplateDeleted?.($res);")
                fetchCustomTemplates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onCustomTemplateDeleted?.($err);")
            }
        }
    }

    // ---------------- AVATAR FRAMES CATALOG (frames/{id}) ----------------
    fun fetchFrames() {
        scope.launch {
            try {
                val snap = db.collection("frames").limit(100).get().await()
                val arr = JSONArray()
                snap.documents.forEach { d ->
                    val rawColors = (d.get("colors") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    val cleanColors = JSONArray()
                    rawColors.take(6).forEach { c ->
                        if (HEX_COLOR_REGEX.matches(c.trim())) cleanColors.put(c.trim())
                    }
                    arr.put(JSONObject().apply {
                        put("id", d.id)
                        put("name", stripUnsafeText(d.getString("name").orEmpty(), 48).ifEmpty { d.id })
                        put("category", stripUnsafeText(d.getString("category").orEmpty(), 32))
                        put("type", d.getString("type").takeIf { it in ALLOWED_FRAME_TYPES } ?: "gradient")
                        put("colors", cleanColors)
                        put("angle", (d.getLong("angle")?.toInt() ?: 135).coerceIn(0, 360))
                        put("width", (d.getLong("width")?.toInt() ?: 3).coerceIn(2, 8))
                        put("glow", (d.getLong("glow")?.toInt() ?: 8).coerceIn(0, 20))
                        put("animation", d.getString("animation").takeIf { it in ALLOWED_FRAME_ANIMS } ?: "none")
                        put("ornament", d.getString("ornament").takeIf { it in ALLOWED_FRAME_ORNAMENTS } ?: "none")
                        put("shape", d.getString("shape").takeIf { it in ALLOWED_FRAME_SHAPES } ?: "circle")
                        put("access", d.getString("access").takeIf { it in ALLOWED_FRAME_ACCESS } ?: "everyone")
                        put("builtIn", d.getBoolean("builtIn") ?: false)
                    })
                }
                val payload = JSONObject().apply {
                    put("ok", true)
                    put("frames", arr)
                }.toString()
                activity.evaluateJs("window.onFramesLoaded?.($payload);")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().apply {
                    put("ok", false)
                    put("error", AuthErrorFormatter.format(e))
                }.toString()
                activity.evaluateJs("window.onFramesLoaded?.($err);")
            }
        }
    }

    private fun sanitizeFrameMap(src: JSONObject): HashMap<String, Any>? {
        val rawId = src.optString("id", "").trim()
        val id = if (SAFE_ID_REGEX.matches(rawId)) rawId else "frame_${System.currentTimeMillis()}"
        val name = stripUnsafeText(src.optString("name", ""), 48)
        if (name.isEmpty()) return null
        val type = src.optString("type", "gradient").takeIf { it in ALLOWED_FRAME_TYPES } ?: "gradient"
        val access = src.optString("access", "everyone").takeIf { it in ALLOWED_FRAME_ACCESS } ?: "everyone"
        val rawColors = src.optJSONArray("colors")
        val colors = mutableListOf<String>()
        if (rawColors != null) {
            for (i in 0 until rawColors.length()) {
                val hex = rawColors.optString(i, "").trim()
                if (HEX_COLOR_REGEX.matches(hex) && colors.size < 6) colors.add(hex)
            }
        }
        if (colors.isEmpty()) colors.add("#D4AF37")
        val anim = src.optString("animation", "none").takeIf { it in ALLOWED_FRAME_ANIMS } ?: "none"
        val ornament = src.optString("ornament", "none").takeIf { it in ALLOWED_FRAME_ORNAMENTS } ?: "none"
        val shape = src.optString("shape", "circle").takeIf { it in ALLOWED_FRAME_SHAPES } ?: "circle"
        val category = stripUnsafeText(src.optString("category", "مخصص"), 32).ifEmpty { "مخصص" }

        return hashMapOf(
            "id" to id,
            "name" to name,
            "category" to category,
            "type" to type,
            "colors" to colors,
            "angle" to src.optInt("angle", 135).coerceIn(0, 360),
            "width" to src.optInt("width", 3).coerceIn(2, 8),
            "glow" to src.optInt("glow", 8).coerceIn(0, 20),
            "animation" to anim,
            "ornament" to ornament,
            "shape" to shape,
            "access" to access,
            "builtIn" to src.optBoolean("builtIn", false),
            "updatedAt" to System.currentTimeMillis()
        )
    }

    fun seedDefaultFrames(framesArrayJson: String) {
        if (!activity.currentUserIsAdmin) return
        val arr = runCatching { JSONArray(framesArrayJson) }.getOrNull() ?: return
        scope.launch {
            try {
                val existingIds = db.collection("frames").get().await().documents.map { it.id }.toSet()
                val batch = db.batch()
                var count = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val map = sanitizeFrameMap(obj) ?: continue
                    val fid = map["id"] as String
                    if (fid !in existingIds) {
                        map["builtIn"] = true
                        batch.set(db.collection("frames").document(fid), map, SetOptions.merge())
                        count++
                    }
                }
                if (count > 0) {
                    batch.commit().await()
                }
                val payload = JSONObject().put("ok", true).put("seeded", count).toString()
                activity.evaluateJs("window.onDefaultFramesSeeded?.($payload);")
                fetchFrames()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val payload = JSONObject().put("ok", false).put("error", AuthErrorFormatter.format(e)).toString()
                activity.evaluateJs("window.onDefaultFramesSeeded?.($payload);")
            }
        }
    }

    fun saveCustomFrame(frameJson: String) {
        if (!activity.currentUserIsAdmin) {
            val err = JSONObject().put("ok", false).put("error", "صلاحية المشرف مطلوبة").toString()
            activity.evaluateJs("window.onCustomFrameSaved?.($err);")
            return
        }
        val parsed = runCatching { JSONObject(frameJson) }.getOrNull()
        val map = parsed?.let { sanitizeFrameMap(it) }
        if (map == null) {
            val err = JSONObject().put("ok", false).put("error", "بيانات الإطار غير صالحة").toString()
            activity.evaluateJs("window.onCustomFrameSaved?.($err);")
            return
        }
        val fid = map["id"] as String
        val fname = map["name"] as String
        scope.launch {
            try {
                db.collection("frames").document(fid).set(map, SetOptions.merge()).await()
                activity.communityManager.writeAuditLog("save_frame", "frame", fid, "name=$fname")
                val ok = JSONObject().put("ok", true).put("frameId", fid).put("name", fname).toString()
                activity.evaluateJs("window.onCustomFrameSaved?.($ok);")
                fetchFrames()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().put("ok", false).put("error", AuthErrorFormatter.format(e)).toString()
                activity.evaluateJs("window.onCustomFrameSaved?.($err);")
            }
        }
    }

    fun deleteCustomFrame(frameId: String) {
        if (!activity.currentUserIsAdmin || !SAFE_ID_REGEX.matches(frameId)) return
        scope.launch {
            try {
                db.collection("frames").document(frameId).delete().await()
                activity.communityManager.writeAuditLog("delete_frame", "frame", frameId, "deleted")
                val ok = JSONObject().put("ok", true).put("frameId", frameId).toString()
                activity.evaluateJs("window.onCustomFrameDeleted?.($ok);")
                fetchFrames()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val err = JSONObject().put("ok", false).put("error", AuthErrorFormatter.format(e)).toString()
                activity.evaluateJs("window.onCustomFrameDeleted?.($err);")
            }
        }
    }

    private fun stripUnsafeText(input: String, maxLen: Int): String {
        return input
            .replace(Regex("[<>]"), "")
            .replace(Regex("[\u0000-\u001F]"), " ")
            .trim()
            .take(maxLen)
    }

    private fun validHex(value: String?, fallback: String): String {
        val v = value?.trim().orEmpty()
        return if (HEX_COLOR_REGEX.matches(v)) v else fallback
    }

    private fun sanitizeGlassSurface(
        src: JSONObject?,
        defOpacity: Int,
        defBlur: Int,
        defSat: Int,
        defTint: String,
        defTintOpacity: Int,
        defBorderWidth: Double,
        defBorderColor: String,
        defBorderOpacity: Int,
        defHighlight: Boolean,
        defShadow: String,
        defNoise: Int,
        defCrack: String
    ): JSONObject {
        val s = src ?: JSONObject()
        return JSONObject().apply {
            put("opacity", s.optInt("opacity", defOpacity).coerceIn(0, 100))
            put("blur", s.optInt("blur", defBlur).coerceIn(0, 40))
            put("saturation", s.optInt("saturation", defSat).coerceIn(80, 220))
            put("tint", validHex(s.optString("tint"), defTint))
            put("tintOpacity", s.optInt("tintOpacity", defTintOpacity).coerceIn(0, 100))
            val bw = s.optDouble("borderWidth", defBorderWidth).let { if (it.isNaN()) defBorderWidth else it.coerceIn(0.0, 2.0) }
            put("borderWidth", bw)
            put("borderColor", validHex(s.optString("borderColor"), defBorderColor))
            put("borderOpacity", s.optInt("borderOpacity", defBorderOpacity).coerceIn(0, 100))
            put("highlight", s.optBoolean("highlight", defHighlight))
            put("shadow", s.optString("shadow", defShadow).takeIf { it in ALLOWED_SHADOWS } ?: defShadow)
            put("noise", s.optInt("noise", defNoise).coerceIn(0, 10))
            put("crack", s.optString("crack", defCrack).takeIf { it in ALLOWED_GLASS_CRACKS } ?: defCrack)
        }
    }

    private fun sanitizeThemeConfig(src: JSONObject): JSONObject {
        val out = JSONObject()
        val rawId = src.optString("id", "default").trim()
        out.put("id", if (SAFE_ID_REGEX.matches(rawId)) rawId else "custom")
        out.put("name", stripUnsafeText(src.optString("name", "Theme Hub الأساسي"), 48).ifEmpty { "Theme Hub الأساسي" })
        out.put("version", src.optInt("version", 1).coerceIn(1, 999))

        val srcColors = src.optJSONObject("colors") ?: JSONObject()
        val colors = JSONObject().apply {
            put("bg", validHex(srcColors.optString("bg"), "#0F0F14"))
            put("surface", validHex(srcColors.optString("surface"), "#1A1A23"))
            put("surface2", validHex(srcColors.optString("surface2"), "#23232F"))
            put("text", validHex(srcColors.optString("text"), "#F2F2F7"))
            put("textMuted", validHex(srcColors.optString("textMuted"), "#8E8EA0"))
            put("primary", validHex(srcColors.optString("primary"), "#7C5CFF"))
            put("primary2", validHex(srcColors.optString("primary2"), "#4DA3FF"))
            put("accent", validHex(srcColors.optString("accent"), "#7C5CFF"))
            put("border", validHex(srcColors.optString("border"), "#2C2C38"))
            put("success", validHex(srcColors.optString("success"), "#34C759"))
            put("danger", validHex(srcColors.optString("danger"), "#FF4D6D"))
        }
        out.put("colors", colors)

        val srcFonts = src.optJSONObject("fonts") ?: JSONObject()
        val heading = srcFonts.optString("heading", "Cairo").takeIf { it in ALLOWED_FONTS } ?: "Cairo"
        val body = srcFonts.optString("body", "Cairo").takeIf { it in ALLOWED_FONTS } ?: "Cairo"
        val scale = srcFonts.optDouble("scale", 1.0).let { if (it.isNaN()) 1.0 else it.coerceIn(0.85, 1.25) }
        out.put("fonts", JSONObject().put("heading", heading).put("body", body).put("scale", scale))

        val srcShape = src.optJSONObject("shape") ?: JSONObject()
        out.put("shape", JSONObject().apply {
            put("radius", srcShape.optInt("radius", 20).coerceIn(0, 32))
            put("cardStyle", srcShape.optString("cardStyle", "glass").takeIf { it in ALLOWED_CARD_STYLES } ?: "glass")
            put("shadow", srcShape.optString("shadow", "soft").takeIf { it in ALLOWED_SHADOWS } ?: "soft")
            put("spacing", srcShape.optString("spacing", "cozy").takeIf { it in ALLOWED_SPACING } ?: "cozy")
            put("pillButtons", srcShape.optBoolean("pillButtons", false))
        })

        val srcIcons = src.optJSONObject("icons") ?: JSONObject()
        out.put("icons", JSONObject().apply {
            put("style", srcIcons.optString("style", "outline").takeIf { it in ALLOWED_ICON_STYLES } ?: "outline")
        })

        val srcGlass = src.optJSONObject("glass") ?: JSONObject()
        val defaultSurfaceHex = colors.optString("surface", "#1A1A23")
        val defaultPrimaryHex = colors.optString("primary", "#7C5CFF")
        val glassObj = JSONObject().apply {
            put("nav", sanitizeGlassSurface(srcGlass.optJSONObject("nav"), 82, 18, 140, defaultSurfaceHex, 15, 1.0, "#FFFFFF", 14, true, "soft", 2, "none"))
            put("capsule", sanitizeGlassSurface(srcGlass.optJSONObject("capsule"), 74, 14, 150, defaultPrimaryHex, 18, 1.0, "#FFFFFF", 18, true, "none", 0, "none"))
            put("card", sanitizeGlassSurface(srcGlass.optJSONObject("card"), 88, 12, 120, defaultSurfaceHex, 10, 1.0, "#FFFFFF", 10, true, "soft", 2, "none"))
            put("sheet", sanitizeGlassSurface(srcGlass.optJSONObject("sheet"), 92, 24, 140, defaultSurfaceHex, 12, 1.0, "#FFFFFF", 14, true, "soft", 2, "none"))
            put("search", sanitizeGlassSurface(srcGlass.optJSONObject("search"), 85, 16, 130, defaultSurfaceHex, 10, 1.0, "#FFFFFF", 14, false, "soft", 0, "none"))
            put("actionBar", sanitizeGlassSurface(srcGlass.optJSONObject("actionBar"), 84, 20, 140, defaultSurfaceHex, 14, 1.0, "#FFFFFF", 14, true, "soft", 0, "none"))
            put("modal", sanitizeGlassSurface(srcGlass.optJSONObject("modal"), 94, 24, 140, defaultSurfaceHex, 14, 1.0, "#FFFFFF", 16, true, "soft", 2, "none"))
        }
        out.put("glass", glassObj)

        val srcNav = src.optJSONObject("nav") ?: JSONObject()
        val rawOrder = srcNav.optJSONArray("order")
        val cleanOrder = JSONArray()
        val seenOrder = mutableSetOf<String>()
        if (rawOrder != null) {
            for (i in 0 until rawOrder.length()) {
                val item = rawOrder.optString(i)
                if (item in ALLOWED_NAV_ORDER && seenOrder.add(item)) cleanOrder.put(item)
            }
        }
        listOf("home", "explore", "club", "favorites", "downloads", "messages", "settings").forEach {
            if (seenOrder.add(it)) cleanOrder.put(it)
        }
        val rawTabs = srcNav.optJSONArray("tabs")
        val cleanTabs = JSONArray()
        val seenTabs = mutableSetOf<String>()
        if (rawTabs != null) {
            for (i in 0 until rawTabs.length()) {
                val item = rawTabs.optString(i)
                if (item in ALLOWED_NAV_TABS && seenTabs.add(item)) cleanTabs.put(item)
            }
        }
        out.put("nav", JSONObject().apply {
            put("type", srcNav.optString("type", "bottom-floating").takeIf { it in ALLOWED_NAV_TYPES } ?: "bottom-floating")
            put("labels", srcNav.optBoolean("labels", false))
            put("activeStyle", srcNav.optString("activeStyle", "filled").takeIf { it in ALLOWED_NAV_ACTIVE } ?: "filled")
            put("order", cleanOrder)
            put("tabs", cleanTabs)
        })

        out.put("header", src.optString("header", "large-title").takeIf { it in ALLOWED_HEADERS } ?: "large-title")

        val srcSearch = src.optJSONObject("search") ?: JSONObject()
        out.put("search", JSONObject().apply {
            put("style", srcSearch.optString("style", "card").takeIf { it in ALLOWED_SEARCH_STYLES } ?: "card")
        })

        val srcHome = src.optJSONObject("home") ?: JSONObject()
        val rawSections = srcHome.optJSONArray("sections")
        val cleanSections = JSONArray()
        val seenSections = mutableSetOf<String>()
        if (rawSections != null) {
            for (i in 0 until rawSections.length()) {
                val s = rawSections.optString(i)
                if (s in ALLOWED_HOME_SECTIONS && seenSections.add(s)) cleanSections.put(s)
            }
        }
        if (cleanSections.length() == 0) {
            listOf("search", "deviceDetector", "brandChips", "banner", "categories", "topDownloads", "featured", "newReleases")
                .forEach { cleanSections.put(it) }
        }
        out.put("home", JSONObject().apply {
            put("sections", cleanSections)
            put("sectionLayout", srcHome.optString("sectionLayout", "vertical-grid").takeIf { it in ALLOWED_SECTION_LAYOUTS } ?: "vertical-grid")
            put("sectionSubtitles", srcHome.optBoolean("sectionSubtitles", false))
            put("heroStyle", srcHome.optString("heroStyle", "carousel").takeIf { it in ALLOWED_HERO_STYLES } ?: "carousel")
        })

        val srcGrid = src.optJSONObject("themeGrid") ?: JSONObject()
        val rawCardInfo = srcGrid.optString("cardInfo", "overlay-title").let {
            when (it) {
                "overlay" -> "overlay-title"
                "below" -> "below-title"
                else -> it
            }
        }
        out.put("themeGrid", JSONObject().apply {
            put("layout", srcGrid.optString("layout", "grid2").takeIf { it in ALLOWED_GRID_LAYOUTS } ?: "grid2")
            put("cardInfo", rawCardInfo.takeIf { it in ALLOWED_CARD_INFO } ?: "overlay-title")
        })

        val srcDet = src.optJSONObject("details") ?: JSONObject()
        val rawActionBar = srcDet.optString("actionBar", "classic-bottom").let {
            when (it) {
                "classic" -> "classic-bottom"
                "floating-wide-primary" -> "floating-wide"
                else -> it
            }
        }
        out.put("details", JSONObject().apply {
            put("layout", srcDet.optString("layout", "classic").takeIf { it in ALLOWED_DETAILS_LAYOUT } ?: "classic")
            put("badges", srcDet.optBoolean("badges", true))
            put("stats", srcDet.optString("stats", "three-cards").takeIf { it in ALLOWED_DETAILS_STATS } ?: "three-cards")
            put("actionBar", rawActionBar.takeIf { it in ALLOWED_ACTION_BAR } ?: "classic-bottom")
            put("descriptionStyle", srcDet.optString("descriptionStyle", "plain").takeIf { it in ALLOWED_DESC_STYLE } ?: "plain")
        })

        val srcClub = src.optJSONObject("club") ?: JSONObject()
        out.put("club", JSONObject().apply {
            put("feedStyle", srcClub.optString("feedStyle", "cards").takeIf { it in ALLOWED_CLUB_FEED } ?: "cards")
        })

        val srcProfile = src.optJSONObject("profile") ?: JSONObject()
        out.put("profile", JSONObject().apply {
            put("layout", srcProfile.optString("layout", "banner-top").takeIf { it in ALLOWED_PROFILE_LAYOUT } ?: "banner-top")
        })

        val srcFrame = src.optJSONObject("phoneFrame") ?: JSONObject()
        out.put("phoneFrame", JSONObject().apply {
            put("defaultFrame", srcFrame.optString("defaultFrame", "titanium").takeIf { it in ALLOWED_PHONE_FRAMES } ?: "titanium")
        })

        val srcTexts = src.optJSONObject("texts") ?: JSONObject()
        val textsObj = JSONObject()
        val arSrc = srcTexts.optJSONObject("ar") ?: srcTexts
        val enSrc = srcTexts.optJSONObject("en") ?: JSONObject()
        textsObj.put("ar", JSONObject().apply {
            put("appName", stripUnsafeText(arSrc.optString("appName", "Theme Hub"), 32).ifEmpty { "Theme Hub" })
            put("tagline", stripUnsafeText(arSrc.optString("tagline", "متجر ثيمات وتخصيص الهواتف"), 80))
            put("bannerTitle", stripUnsafeText(arSrc.optString("bannerTitle", "ثيمات هونر وهواوي"), 80))
            put("bannerSubtitle", stripUnsafeText(arSrc.optString("bannerSubtitle", ""), 140))
            put("ctaDownload", stripUnsafeText(arSrc.optString("ctaDownload", "تحميل"), 32))
            put("ctaApply", stripUnsafeText(arSrc.optString("ctaApply", "تطبيق على الهاتف"), 32))
        })
        textsObj.put("en", JSONObject().apply {
            put("appName", stripUnsafeText(enSrc.optString("appName", "Theme Hub"), 32).ifEmpty { "Theme Hub" })
            put("tagline", stripUnsafeText(enSrc.optString("tagline", "Phone Themes & Customization Hub"), 80))
            put("bannerTitle", stripUnsafeText(enSrc.optString("bannerTitle", "Honor & Huawei Themes"), 80))
            put("bannerSubtitle", stripUnsafeText(enSrc.optString("bannerSubtitle", ""), 140))
            put("ctaDownload", stripUnsafeText(enSrc.optString("ctaDownload", "Download"), 32))
            put("ctaApply", stripUnsafeText(enSrc.optString("ctaApply", "Apply to Phone"), 32))
        })
        out.put("texts", textsObj)

        out.put("animation", src.optString("animation", "smooth").takeIf { it in ALLOWED_ANIMATIONS } ?: "smooth")
        out.put("transition", src.optString("transition", "slide").takeIf { it in ALLOWED_TRANSITIONS } ?: "slide")
        out.put("mode", src.optString("mode", "dark").takeIf { it in ALLOWED_MODES } ?: "dark")
        return out
    }
}
