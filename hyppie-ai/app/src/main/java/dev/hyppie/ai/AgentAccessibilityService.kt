package dev.hyppie.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads the screen and performs actions for agent mode; also draws the agent's glow + step card. */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null

        fun isEnabled(ctx: Context): Boolean {
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return on.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: FrameLayout? = null
    private var glow: GlowView? = null
    private var stepText: TextView? = null
    private var buttons: LinearLayout? = null
    @Volatile var stopRequested = false
    private val nodes = mutableListOf<AccessibilityNodeInfo>()

    var island: Island? = null

    override fun onServiceConnected() {
        instance = this
        island = Island(this).also { it.start() }   // always-on Super Island (if enabled)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { island?.stop(); instance = null; hideOverlay(); super.onDestroy() }

    // ---------------------------------------------------------------- screen reading

    /** Root of the app the user is looking at (skips our own overlay / assistant windows). */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val ws = try { windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        for (w in ws.sortedByDescending { it.layer }) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            if (r.packageName?.toString() == packageName) continue
            return r
        }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
    }

    fun currentApp(): String = targetRoot()?.packageName?.toString() ?: "unknown"

    /** Numbered list of the visible, useful elements, e.g. `[4] button "Wi-Fi" on`. */
    fun describeScreen(limit: Int = 70): String {
        nodes.clear()
        val root = targetRoot() ?: return "(screen not readable)"
        val sb = StringBuilder()
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || nodes.size >= limit) return
            if (!n.isVisibleToUser) return
            val text = (n.text ?: n.contentDescription ?: n.hintText)?.toString()?.replace('\n', ' ')?.trim().orEmpty()
            val useful = n.isClickable || n.isEditable || n.isCheckable || n.isScrollable || text.isNotEmpty()
            if (useful) {
                n.getBoundsInScreen(r)
                if (r.width() > 4 && r.height() > 4) {
                    val kind = when {
                        n.isEditable -> "input"
                        n.isCheckable -> "switch"
                        n.isScrollable -> "list"
                        n.isClickable -> "button"
                        else -> "text"
                    }
                    val state = buildString {
                        if (n.isCheckable) append(if (n.isChecked) " on" else " off")
                        if (!n.isEnabled) append(" disabled")
                    }
                    nodes.add(n)
                    sb.append('[').append(nodes.size - 1).append("] ").append(kind)
                    if (text.isNotEmpty()) sb.append(" \"").append(text.take(60)).append('"')
                    sb.append(state).append('\n')
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().ifEmpty { "(no readable elements)" }
    }

    fun labelOf(id: Int): String = nodes.getOrNull(id)?.let { (it.text ?: it.contentDescription)?.toString() } ?: ""

    // ---------------------------------------------------------------- actions

    fun tap(id: Int): Boolean {
        var n: AccessibilityNodeInfo? = nodes.getOrNull(id) ?: return false
        val target = n
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); target!!.getBoundsInScreen(r)
        return gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
    }

    fun type(id: Int, text: String): Boolean {
        val n = nodes.getOrNull(id) ?: nodes.firstOrNull { it.isEditable } ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(down: Boolean): Boolean {
        val list = nodes.firstOrNull { it.isScrollable }
        if (list != null && list.performAction(
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        return if (down) gesture(x, dm.heightPixels * 0.7f, x, dm.heightPixels * 0.3f, 350)
        else gesture(x, dm.heightPixels * 0.3f, x, dm.heightPixels * 0.7f, 350)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Opens an installed app by (part of) its name; prefers exact, then the longest matching label. */
    fun openApp(name: String): String? {
        val pm = packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val want = name.lowercase().removePrefix("the ").removeSuffix(" app").trim()
        val hit = pm.queryIntentActivities(launch, 0).mapNotNull {
            val l = it.loadLabel(pm).toString().lowercase()
            val tier = when {
                l == want -> 0
                l.startsWith(want) || l.endsWith(want) -> 1
                l.contains(want) -> 2
                want.contains(l) -> 3
                else -> return@mapNotNull null
            }
            Triple(tier, -l.length, it)
        }.minWithOrNull(compareBy({ it.first }, { it.second }))?.third ?: return null
        startActivity(Intent(launch).setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return hit.loadLabel(pm).toString()
    }

    /** The working camera on this phone is LineageOS Aperture (Xiaomi's Camera app is model-locked). */
    fun openCamera(): Boolean = runCatching {
        val i = packageManager.getLaunchIntentForPackage("org.lineageos.aperture")
            ?: Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    /** Presses the camera shutter: a button described as shutter/capture/take photo, else bottom centre. */
    fun shutter(): Boolean {
        val root = targetRoot()
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val d = (n.contentDescription ?: n.text ?: n.viewIdResourceName ?: "").toString().lowercase()
            if (n.isClickable && Regex("shutter|capture|take (a )?(photo|picture)").containsMatchIn(d)) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        find(root)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        val dm = resources.displayMetrics
        return gesture(dm.widthPixels / 2f, dm.heightPixels * 0.83f, dm.widthPixels / 2f, dm.heightPixels * 0.83f, 60)
    }

    fun flashlight(on: Boolean): Boolean = runCatching {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
        val id = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        cm.setTorchMode(id, on); true
    }.getOrDefault(false)

    fun webSearch(query: String): Boolean = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    fun openSettings(page: String): Boolean = runCatching {
        val action = when (page.lowercase()) {
            "about", "about phone", "version" -> Settings.ACTION_DEVICE_INFO_SETTINGS
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- offline system actions

    private fun allRoots(): List<AccessibilityNodeInfo> =
        (try { windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }).mapNotNull { it.root }

    private fun findNode(labels: List<String>): AccessibilityNodeInfo? {
        val want = labels.map { it.lowercase() }
        fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (n == null || depth > 30) return null
            val t = listOfNotNull(n.text, n.contentDescription).joinToString(" ").lowercase()
            if (t.isNotEmpty() && want.any { t == it || t.startsWith("$it,") || t.startsWith("$it ") || t.startsWith(it) }) return n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        for (r in allRoots()) if (r.packageName?.toString() != packageName) walk(r, 0)?.let { return it }
        return null
    }

    private fun isOn(n: AccessibilityNodeInfo): Boolean? {
        var c: AccessibilityNodeInfo? = n
        repeat(4) {
            val node = c ?: return@repeat
            if (node.isCheckable) return node.isChecked
            val d = listOfNotNull(node.contentDescription, node.stateDescription, node.text).joinToString(",").lowercase()
            if (Regex("\\b(on|enabled|connected)\\b").containsMatchIn(d)) return true
            if (Regex("\\b(off|disabled)\\b").containsMatchIn(d)) return false
            c = node.parent
        }
        return null
    }

    /** Sets a Control Center tile on/off. Returns "on"/"off", or null if the tile wasn't found. */
    fun setTile(labels: List<String>, on: Boolean): String? {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        Thread.sleep(1200)
        var node = findNode(labels)
        if (node == null) { scroll(true); Thread.sleep(700); node = findNode(labels) }
        if (node == null) { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE); return null }
        val before = isOn(node)
        if (before != on) {
            var c: AccessibilityNodeInfo? = node
            while (c != null && !c.isClickable) c = c.parent
            (c ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(900)
        }
        Thread.sleep(500)
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        return if (on) "on" else "off"
    }

    private val audio by lazy { getSystemService(android.media.AudioManager::class.java) }
    fun setVolume(pct: Int) {
        val s = android.media.AudioManager.STREAM_MUSIC
        audio.setStreamVolume(s, (audio.getStreamMaxVolume(s) * pct + 50) / 100, android.media.AudioManager.FLAG_SHOW_UI)
    }
    fun stepVolume(up: Boolean) = audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
        if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
        android.media.AudioManager.FLAG_SHOW_UI)
    fun mute(on: Boolean) = audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
        if (on) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE,
        android.media.AudioManager.FLAG_SHOW_UI)

    /** Brightness needs "modify system settings" (granted once over ADB / in Settings). */
    private fun askWriteSettings(): String {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "Couldn't change brightness: turn on “Modify system settings” for the assistant (screen opened)"
    }

    fun setBrightness(pct: Int): String {
        if (!Settings.System.canWrite(this)) return askWriteSettings()
        val max = maxBrightness()
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (max * pct / 100).coerceAtLeast(1))
        return "Brightness set to $pct%"
    }
    fun stepBrightness(up: Boolean): String {
        if (!Settings.System.canWrite(this)) return askWriteSettings()
        val max = maxBrightness()
        val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, max / 2)
        val pct = (cur * 100 / max + if (up) 20 else -20).coerceIn(5, 100)
        setBrightness(pct)
        return if (up) "Made the screen brighter ($pct%)" else "Made the screen dimmer ($pct%)"
    }
    private fun maxBrightness(): Int = runCatching {
        val id = resources.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        android.content.res.Resources.getSystem().getInteger(id).takeIf { it > 0 }
    }.getOrNull() ?: 255

    fun setTimer(secs: Int, label: String) = startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
        .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
        .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Assistant")
        .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun setAlarm(h: Int, m: Int) = startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
        .putExtra(android.provider.AlarmClock.EXTRA_HOUR, h).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
        .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun mediaKey(which: String) {
        val code = when (which) {
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        }
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }
    fun screenshot() = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    fun lockScreen() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun quickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    private fun view(uri: String) = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
    fun youtube(q: String) = view("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8"))
    fun navigate(q: String) = view("geo:0,0?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
    /** Opens the dialer with the number filled in; the user presses call. */
    fun dial(n: String) = runCatching {
        startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + n.filter { it.isDigit() || it == '+' }))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- skills used by the planner / agent

    /** Posts a normal notification with [text]. */
    fun notify(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("assistant_msgs", "Assistant messages",
            android.app.NotificationManager.IMPORTANCE_HIGH))
        nm.notify((System.currentTimeMillis() % 100000).toInt(), android.app.Notification.Builder(this, "assistant_msgs")
            .setSmallIcon(R.drawable.ic_spark).setContentTitle("Assistant").setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }

    fun copy(text: String) = main.post {
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("assistant", text))
    }

    /** Opens a new note in Xiaomi Notes with [text] already pasted in. */
    fun newNote(text: String): Boolean = runCatching {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageManager.getLaunchIntentForPackage("com.miui.notes") != null) i.setPackage("com.miui.notes")
        startActivity(i); true
    }.getOrDefault(false)

    /** Launcher apps (label, package), excluding this assistant. */
    fun isLocked() = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    fun isInstalled(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun launcherApps(): List<Pair<String, String>> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    fun openPackage(pkg: String): Boolean = runCatching {
        startActivity(packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    fun swipeHome(left: Boolean) {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.5f
        if (left) gesture(dm.widthPixels * 0.85f, y, dm.widthPixels * 0.15f, y, 220)
        else gesture(dm.widthPixels * 0.15f, y, dm.widthPixels * 0.85f, y, 220)
    }

    /** Finds a clickable element whose text/description matches [rx] anywhere on screen and taps it. */
    fun tapText(rx: Regex): Boolean {
        fun walk(n: AccessibilityNodeInfo?, d: Int): AccessibilityNodeInfo? {
            if (n == null || d > 40) return null
            val t = listOfNotNull(n.text, n.contentDescription).joinToString(" ")
            if (n.isVisibleToUser && t.isNotEmpty() && rx.matches(t.trim())) return n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)?.let { return it }
            return null
        }
        for (r in allRoots()) {
            val hit = walk(r, 0) ?: continue
            var c: AccessibilityNodeInfo? = hit
            while (c != null && !c.isClickable) c = c.parent
            if ((c ?: hit).performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val b = Rect(); hit.getBoundsInScreen(b)
            return gesture(b.exactCenterX(), b.exactCenterY(), b.exactCenterX(), b.exactCenterY(), 60)
        }
        return false
    }

    fun screenText(): String = buildString {
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40 || length > 6000) return
            listOfNotNull(n.text, n.contentDescription).forEach { append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        allRoots().forEach { if (it.packageName?.toString() != packageName) walk(it, 0) }
    }

    /** Play Store install: details page by package if known, else search; taps Install. */
    fun playInstall(name: String, pkg: String?): String {
        if (pkg != null && runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess) return "$name is already installed"
        val uri = if (pkg != null) "market://details?id=$pkg" else "market://search?q=" + java.net.URLEncoder.encode(name, "UTF-8") + "&c=apps"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage("com.android.vending")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { return "Couldn't open the Play Store" }
        Thread.sleep(4500)
        val txt = screenText()
        if (Regex("(?i)\\bsign in\\b").containsMatchIn(txt) && !Regex("(?i)\\binstall\\b").containsMatchIn(txt))
            return "Skipped $name: the Play Store needs you to sign in"
        if (pkg == null) {   // search results: open the first matching app
            tapText(Regex("(?i).*${Regex.escape(name.split(' ').first())}.*")); Thread.sleep(3000)
        }
        if (Regex("(?i)\\b(open|uninstall|update)\\b").containsMatchIn(screenText()) &&
            !Regex("(?i)^install$", RegexOption.MULTILINE).containsMatchIn(screenText())) return "$name is already installed"
        if (tapText(Regex("(?i)install"))) return "Installing $name (downloading in the background)"
        if (pkg != null) {   // known ID page had no Install button (wrong/unavailable ID): search by name instead
            return playInstall(name, null)
        }
        return "Couldn't find the Install button for $name"
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val done = CountDownLatch(1)
        var ok = false
        main.post {
            dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { ok = true; done.countDown() }
                    override fun onCancelled(g: GestureDescription?) { done.countDown() }
                }, null)
        }
        done.await(3, TimeUnit.SECONDS)
        return ok
    }

    // ---------------------------------------------------------------- overlay (glow + step card)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private var cardWindow: View? = null
    private var checkHead: TextView? = null
    private var checkList: LinearLayout? = null
    private val rows = mutableListOf<TextView>()
    private val states = mutableListOf<Int>()   // 0 waiting, 1 working, 2 done, 3 skipped

    /** Bixby-style checklist: one row per part of the request. */
    fun setChecklist(parts: List<String>) = main.post {
        val list = checkList ?: return@post
        list.removeAllViews(); rows.clear(); states.clear()
        parts.forEach { part ->
            val r = TextView(this).apply {
                textSize = 14.5f; setPadding(0, dp(3), 0, dp(3)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }
            rows.add(r); states.add(0); list.addView(r)
            paintRow(rows.size - 1, part)
        }
        updateHead()
    }

    fun markPart(i: Int, state: Int, label: String? = null) = main.post {
        if (i !in rows.indices) return@post
        states[i] = state
        paintRow(i, label)
        val r = rows[i]
        if (state == 2 || state == 3) {   // tick pops in
            r.scaleX = 0.92f; r.scaleY = 0.92f
            r.animate().scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        } else if (state == 1) {           // working row gently pulses
            r.animate().alpha(0.55f).setDuration(450).withEndAction {
                r.animate().alpha(1f).setDuration(450).start()
            }.start()
        }
        updateHead()
    }

    private fun paintRow(i: Int, label: String?) {
        val r = rows[i]
        val text = label ?: (r.tag as? String) ?: ""
        r.tag = text
        val (icon, color) = when (states[i]) {
            1 -> "◉" to Color.parseColor("#A78BFA")
            2 -> "✓" to Color.parseColor("#4ADE80")
            3 -> "⤼" to Color.argb(150, 255, 255, 255)
            else -> "○" to Color.argb(150, 255, 255, 255)
        }
        r.text = "$icon  $text"
        r.setTextColor(if (states[i] == 0) Color.argb(170, 255, 255, 255) else if (states[i] == 3) Color.argb(140, 255, 255, 255) else Color.WHITE)
        val sp = android.text.SpannableString(r.text)
        sp.setSpan(android.text.style.ForegroundColorSpan(color), 0, icon.length, 0)
        sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, icon.length, 0)
        r.text = sp
    }

    private fun updateHead() {
        val done = states.count { it >= 2 }
        checkHead?.text = if (states.isEmpty()) "✦ Assistant" else
            if (done == states.size) "✦ Assistant · all ${states.size} done" else "✦ Assistant · $done of ${states.size} done"
    }

    fun showOverlay() = main.post {
        if (overlay != null) return@post
        val wm = getSystemService(WindowManager::class.java)
        // window 1: the glow, full screen, never touchable (taps go through to the app)
        val root = FrameLayout(this)
        val g = GlowView(this).apply { mode = GlowView.Mode.ACTING }
        root.addView(g, FrameLayout.LayoutParams(-1, -1))
        wm.addView(root, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        })
        // window 2: the step card with Stop / Allow / Skip, only as big as the card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(Color.argb(225, 18, 18, 28)) }
            elevation = dp(8).toFloat()
        }
        val head = TextView(this).apply {
            setTextColor(Color.argb(210, 199, 210, 254)); textSize = 13f; text = "✦ Assistant"
            setPadding(0, 0, 0, dp(6))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply {
            setTextColor(Color.argb(190, 255, 255, 255)); textSize = 13f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(head); card.addView(list); card.addView(t)
        // slide-up + fade-in like the Bixby / HyperAI panels
        card.alpha = 0f; card.translationY = dp(40).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(320)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        checkHead = head; checkList = list
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        card.addView(row)
        wm.addView(card, WindowManager.LayoutParams(-1, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM
            x = 0; y = dp(46)
            horizontalMargin = 0.035f
        })
        overlay = root; cardWindow = card; glow = g; stepText = t; buttons = row
        setButtons(listOf("Stop" to { stopRequested = true }))
    }

    fun hideOverlay() = main.post {
        val wm = getSystemService(WindowManager::class.java)
        overlay?.let { runCatching { wm.removeView(it) } }
        cardWindow?.let { runCatching { wm.removeView(it) } }
        overlay = null; cardWindow = null; glow = null; stepText = null; buttons = null
        checkHead = null; checkList = null; rows.clear(); states.clear()
    }

    fun setStep(text: String, mode: GlowView.Mode = GlowView.Mode.ACTING) = main.post {
        stepText?.text = text
        glow?.mode = mode
    }

    fun setButtons(list: List<Pair<String, () -> Unit>>) = main.post {
        val row = buttons ?: return@post
        row.removeAllViews()
        list.forEach { (label, action) ->
            row.addView(Button(this).apply {
                text = label; isAllCaps = false; setOnClickListener { action() }
            })
        }
    }

    /** Pauses the agent until the user allows or skips a risky step. */
    fun confirm(question: String): Boolean {
        val latch = CountDownLatch(1)
        var allowed = false
        setStep("⚠️ $question", GlowView.Mode.LISTENING)
        setButtons(listOf(
            "Skip" to { latch.countDown() },
            "Allow" to { allowed = true; latch.countDown() },
            "Stop" to { stopRequested = true; latch.countDown() }))
        latch.await(5, TimeUnit.MINUTES)
        setButtons(listOf("Stop" to { stopRequested = true }))
        return allowed && !stopRequested
    }
}
