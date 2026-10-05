package dev.hyppie.ai

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import java.text.DateFormat
import java.util.Date

/**
 * Offline layer: splits a request into parts ("…, then …") and runs the common ones directly, without any
 * AI model — Control Center toggles, volume, brightness, timers/alarms, media keys, screenshots, apps, web
 * and map searches, the camera, settings pages — and answers simple questions (time, date, battery, maths,
 * HyperOS version). All patterns are compiled once; nothing here keeps memory or a background thread.
 */
object QuickCommands {

    private val IC = RegexOption.IGNORE_CASE
    private val splitter = Regex(
        "\\s*(?:,\\s*(?:and\\s+)?then\\s+|\\s+and\\s+then\\s+|,?\\s+then\\s+|\\.\\s+|;\\s*|" +
            ",\\s+(?=(?:open|turn|switch|take|search|go|close|check|find|play|set|call|navigate|lock|mute)\\b))", IC)
    private val onThenOff = Regex("\\bon,?\\s+(?:and\\s+)?then\\s+off\\b", IC)

    fun split(task: String): List<String> =
        task.replace(onThenOff, "on-then-off")
            .split(splitter).map { it.trim().trim(',', '.').trim() }.filter { it.isNotEmpty() }

    // ------------------------------------------------------------------ patterns (compiled once)

    /** Control Center tiles: spoken name -> words that can appear on the HyperOS tile. */
    private val tiles = linkedMapOf(
        "wi-?fi|wifi|wireless" to listOf("Wi-Fi", "WLAN", "Wi‑Fi"),
        "bluetooth" to listOf("Bluetooth"),
        "mobile data|cellular( data)?|data" to listOf("Mobile data", "Data"),
        "airplane( mode)?|aeroplane( mode)?|flight mode" to listOf("Airplane mode", "Airplane"),
        "dark mode|dark theme|night mode" to listOf("Dark mode", "Dark theme"),
        "location|gps" to listOf("Location"),
        "hotspot|tethering" to listOf("Hotspot", "Personal hotspot"),
        "do not disturb|dnd|silent mode" to listOf("Do not disturb", "DND"),
        "auto-?rotate|rotation|rotate" to listOf("Auto-rotate", "Rotate", "Lock orientation"),
        "battery saver|power saver|saver" to listOf("Battery saver", "Saver"),
    ).map { (k, v) -> Regex("\\b($k)\\b", IC) to v }

    private val torch = Regex("\\b(flashlight|torch)\\b", IC)
    private val wantOff = Regex("\\b(off|disable|deactivate|stop)\\b", IC)
    private val wantOn = Regex("\\b(on|enable|activate|start)\\b", IC)
    private val toggleVerb = Regex("^(turn|switch|toggle|enable|disable|activate|deactivate|put)\\b", IC)
    private val webSearch = Regex("^(?:search|look up|google)(?: the web| google| online)?(?: for)?\\s+(.+)$", IC)
    private val youtube = Regex("^(?:play|watch|search|find)\\s+(.+?)\\s+on\\s+youtube$", IC)
    private val navigate = Regex("^(?:navigate|directions|take me|drive|get directions)(?: to)?\\s+(.+)$", IC)
    private val dial = Regex("^(?:call|dial|phone)\\s+([+0-9 ()-]{3,})$", IC)
    private val timer = Regex("\\btimer\\b.*?(\\d+)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)", IC)
    private val alarm = Regex("\\balarm\\b.*?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?", IC)
    private val volumeSet = Regex("\\bvolume\\b.*?(\\d{1,3})\\s*%?", IC)
    private val volume = Regex("\\b(volume|sound|louder|quieter|mute|unmute)\\b", IC)
    private val brightnessSet = Regex("\\bbrightness\\b.*?(\\d{1,3})\\s*%?", IC)
    private val brightness = Regex("\\b(brightness|brighter|dimmer|darker screen)\\b", IC)
    private val up = Regex("\\b(up|louder|higher|increase|raise|brighter|max)\\b", IC)
    private val media = Regex("^(?:(play|pause|resume|stop)( the)?( music| song| video)?|(next|skip)( song| track)?|(previous|last|back)( song| track))$", IC)
    private val screenshot = Regex("\\b(screenshot|screen ?shot|capture the screen)\\b", IC)
    private val lock = Regex("^lock( the)?( phone| screen| device)?$", IC)
    private val recents = Regex("\\b(recent apps|recents|app switcher|multitask)\\b", IC)
    private val notifications = Regex("^(open|show|pull down)( the| my)? notifications?$", IC)
    private val controlCenter = Regex("^(open|show)( the)? (control cent(er|re)|quick settings)$", IC)
    private val version = Regex("\\b(hyperos version|android version|about phone|software version|os version|my version)\\b", IC)
    private val photo = Regex("\\b(take|snap)\\b.*\\b(picture|photo|selfie|pic)\\b", IC)
    private val openCamera = Regex("^open (the )?camera( app)?$", IC)
    private val close = Regex("^(close|exit|quit)( out of)?\\b", IC)
    private val home = Regex("^(go )?home( screen)?$|^go to (the )?home ?screen$", IC)
    private val back = Regex("^(go )?back$", IC)
    private val settingsPage = Regex("^(?:open|go to|show)(?: the)? (wi-?fi|bluetooth|display|battery|sound|apps) settings$", IC)
    private val openApp = Regex("^(?:open|launch|start)(?: the)? (.+?)(?: app)?$", IC)
    private val notifyRx = Regex("^(?:show|display|post|send|make)(?: me)?(?: a)? notification(?: that says| saying| with(?: the text)?| reading)?[: ]+[\"“]?(.+?)[\"”]?$", IC)
    private val noteRx = Regex("^(?:create|make|write|open)(?: up)?(?: a)?(?: new)? note(?: with| saying| containing| that says)?[: ]+(.+)$", IC)
    private val swipeRx = Regex("^swipe (left and right|right and left|left|right)(?: on the home ?screen)? (\\d+) times?$", IC)
    private val waitRx = Regex("^wait (\\d+) ?(seconds?|secs?|s)$", IC)
    private val everyApp = Regex("^(open|close).*(every|each|all) (single )?apps?.*$", IC)
    private val installRx = Regex("^install (.+?)(?: from (?:the )?(?:google )?play ?store)?$", IC)
    private val playRx = Regex("^play (.+?)(?: on (youtube|youtube music|a music app|music))?(?: for (\\d+) seconds?)?$", IC)
    private val maxRx = Regex("\\b(max|maximum|full|100)\\b", IC)
    private val zeroRx = Regex("\\b(zero|0|mute|minimum|min)\\b", IC)

    /** Known Play Store packages for common apps (search is used otherwise). */
    val storeIds = mapOf(
        "roblox" to "com.roblox.client", "chrome" to "com.android.chrome", "google chrome" to "com.android.chrome",
        "tiktok" to "com.zhiliaoapp.musically", "fc 26" to "com.ea.gp.fifamobile", "ea sports fc mobile" to "com.ea.gp.fifamobile",
        "fc mobile" to "com.ea.gp.fifamobile", "fc26" to "com.ea.gp.fifamobile", "fc 25" to "com.ea.gp.fifamobile", "youtube" to "com.google.android.youtube",
        "spotify" to "com.spotify.music", "google classroom" to "com.google.android.apps.classroom",
        "classroom" to "com.google.android.apps.classroom", "whatsapp" to "com.whatsapp", "instagram" to "com.instagram.android")

    // questions answered without a model
    private val askTime = Regex("\\b(what time|the time|time is it)\\b", IC)
    private val askDate = Regex("\\b(what day|the date|today's date|what date)\\b", IC)
    private val askBattery = Regex("\\b(battery)\\b.*\\b(level|percent|percentage|left|how much|status)\\b|\\bhow much battery\\b", IC)
    private val askMath = Regex("^(?:what(?:'s| is)\\s+|calculate\\s+|how much is\\s+)?([-+*/x×÷().%\\d\\s^]+)\\??$", IC)

    private fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    fun hyperOsVersion(): String {
        val v = listOf("ro.mi.os.version.incremental", "ro.mi.os.version.name", "ro.build.version.incremental")
            .map { prop(it) }.firstOrNull { it.isNotBlank() } ?: Build.DISPLAY
        return "HyperOS $v (Android ${Build.VERSION.RELEASE})"
    }

    /** True if [text] (or its first part) is something the offline layer can do. */
    fun isCommand(text: String): Boolean {
        val p = split(text).firstOrNull()?.lowercase()?.removePrefix("please ")?.trim() ?: return false
        return toggleVerb.containsMatchIn(p) || torch.containsMatchIn(p) || webSearch.matches(p) || youtube.matches(p) ||
            navigate.matches(p) || dial.matches(p) || timer.containsMatchIn(p) || alarm.containsMatchIn(p) ||
            volume.containsMatchIn(p) || brightness.containsMatchIn(p) || media.matches(p) || screenshot.containsMatchIn(p) ||
            lock.matches(p) || photo.containsMatchIn(p) || openApp.matches(p) || home.matches(p) || back.matches(p)
    }

    /** Answers simple questions offline; null if a model is needed. */
    fun answer(ctx: Context, q: String): String? {
        val p = q.trim()
        if (askTime.containsMatchIn(p)) return "It's ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}."
        if (askDate.containsMatchIn(p)) return "Today is ${DateFormat.getDateInstance(DateFormat.FULL).format(Date())}."
        if (askBattery.containsMatchIn(p)) {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return "Battery is at $pct%" + if (bm.isCharging) " and charging." else "."
        }
        if (version.containsMatchIn(p)) return "Your phone runs ${hyperOsVersion()}."
        askMath.matchEntire(p)?.let { m ->
            val expr = m.groupValues[1].trim()
            if (expr.any { it.isDigit() } && expr.any { it in "+-*/x×÷^%" }) {
                runCatching { Calc(expr).parse() }.getOrNull()?.let { v ->
                    val s = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else "%.6g".format(v)
                    return "$expr = $s"
                }
            }
        }
        return null
    }

    /** Runs [part] directly if it's a known command. Returns a result line, or null if the AI is needed. */
    fun run(part: String, svc: AgentAccessibilityService, show: (String) -> Unit): String? {
        val p = part.lowercase().removePrefix("please ").trim()
        fun on() = !wantOff.containsMatchIn(p) || wantOn.containsMatchIn(p.replace("on-then-off", ""))

        answer(svc, p)?.let { if (!toggleVerb.containsMatchIn(p)) return it }

        // flashlight (direct, no Control Center needed)
        if (torch.containsMatchIn(p)) {
            return when {
                p.contains("on-then-off") || Regex("\\bon\\b.*\\boff\\b").containsMatchIn(p) -> {
                    show("Flashlight on"); svc.flashlight(true); Thread.sleep(1500)
                    show("Flashlight off"); svc.flashlight(false); "Turned the flashlight on, then off"
                }
                wantOff.containsMatchIn(p) -> { show("Flashlight off"); svc.flashlight(false); "Turned the flashlight off" }
                else -> { show("Flashlight on"); svc.flashlight(true); "Turned the flashlight on" }
            }
        }

        // Control Center tiles: "turn on wifi", "disable bluetooth", "dark mode on" …
        if (toggleVerb.containsMatchIn(p) || wantOn.containsMatchIn(p) || wantOff.containsMatchIn(p)) {
            for ((rx, labels) in tiles) {
                val m = rx.find(p) ?: continue
                val want = !wantOff.containsMatchIn(p)
                show("${if (want) "Turning on" else "Turning off"} ${m.value}")
                return svc.setTile(labels, want)?.let { state -> "${m.value.replaceFirstChar { it.uppercase() }} is $state" }
                    ?: "Couldn't find the ${m.value} tile in Control Center"
            }
        }

        // volume / brightness
        if (volume.containsMatchIn(p)) {
            if (maxRx.containsMatchIn(p)) { svc.setVolume(100); return "Volume set to max" }
            if (Regex("\\b(zero|0)\\b", IC).containsMatchIn(p)) { svc.setVolume(0); return "Volume set to zero" }
            volumeSet.find(p)?.let { val v = it.groupValues[1].toInt().coerceIn(0, 100); svc.setVolume(v); return "Volume set to $v%" }
            if (Regex("\\bunmute\\b").containsMatchIn(p)) { svc.mute(false); return "Unmuted" }
            if (Regex("\\b(mute|silence)\\b").containsMatchIn(p)) { svc.mute(true); return "Muted" }
            val u = up.containsMatchIn(p); svc.stepVolume(u); svc.stepVolume(u)
            return if (u) "Turned the volume up" else "Turned the volume down"
        }
        if (brightness.containsMatchIn(p)) {
            if (maxRx.containsMatchIn(p)) return svc.setBrightness(100)
            brightnessSet.find(p)?.let { val v = it.groupValues[1].toInt().coerceIn(1, 100); return svc.setBrightness(v) }
            return svc.stepBrightness(up.containsMatchIn(p))
        }

        // clock
        timer.find(p)?.let {
            val n = it.groupValues[1].toInt(); val u = it.groupValues[2].lowercase()
            val secs = when { u.startsWith("h") -> n * 3600; u.startsWith("m") -> n * 60; else -> n }
            show("Setting a timer for $n $u"); svc.setTimer(secs, part); return "Timer set for $n $u"
        }
        alarm.find(p)?.let {
            var h = it.groupValues[1].toInt(); val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            val ampm = it.groupValues[3].lowercase().replace(".", "")
            if (ampm == "pm" && h < 12) h += 12; if (ampm == "am" && h == 12) h = 0
            show("Setting an alarm for %d:%02d".format(h, m)); svc.setAlarm(h, m); return "Alarm set for %d:%02d".format(h, m)
        }

        // media keys, system actions
        media.matchEntire(p)?.let {
            val key = when {
                it.groupValues[4].isNotEmpty() -> "next"; it.groupValues[6].isNotEmpty() -> "previous"
                it.groupValues[1] in listOf("pause", "stop") -> "pause"; else -> "play"
            }
            svc.mediaKey(key); return "Media: $key"
        }
        if (screenshot.containsMatchIn(p)) { show("Taking a screenshot"); svc.screenshot(); return "Took a screenshot" }
        if (lock.matches(p)) { svc.lockScreen(); return "Locked the screen" }
        if (recents.containsMatchIn(p)) { svc.recents(); return "Opened recent apps" }
        if (notifications.matches(p)) { svc.notifications(); return "Opened notifications" }
        if (controlCenter.matches(p)) { svc.quickSettings(); return "Opened Control Center" }
        if (home.matches(p) || close.containsMatchIn(p)) { show("Going home"); svc.home(); Thread.sleep(800); return "Closed it" }
        if (back.matches(p)) { svc.back(); return "Went back" }

        // planner skills
        notifyRx.matchEntire(part.trim())?.let { val t = it.groupValues[1].trim(); show("Notification: “$t”"); svc.notify(t); return "Showed a notification: “$t”" }
        noteRx.matchEntire(part.trim())?.let {
            val t = it.groupValues[1].trim()
            show("New note"); svc.newNote(t); Thread.sleep(3000); return "Opened a new note with the text pasted in"
        }
        swipeRx.matchEntire(p)?.let {
            val n = it.groupValues[2].toInt().coerceAtMost(50); val both = it.groupValues[1].contains("and")
            svc.home(); Thread.sleep(800)
            repeat(n) { k ->
                show("Swipe ${k + 1}/$n")
                if (both) { svc.swipeHome(true); Thread.sleep(350); svc.swipeHome(false) }
                else svc.swipeHome(it.groupValues[1].startsWith("left"))
                Thread.sleep(350)
            }
            return "Swiped ${if (both) "left and right" else it.groupValues[1]} $n times"
        }
        waitRx.matchEntire(p)?.let { val n = it.groupValues[1].toInt().coerceAtMost(120); show("Waiting $n s"); Thread.sleep(n * 1000L); return "Waited $n seconds" }
        if (everyApp.matches(p)) {
            val apps = svc.launcherApps()
            apps.forEachIndexed { k, (label, pkg) ->
                if (svc.stopRequested) return "Stopped after ${k} apps"
                show("Opening and closing ${k + 1}/${apps.size}: $label")
                svc.openPackage(pkg); Thread.sleep(1600); svc.home(); Thread.sleep(500)
            }
            return "Opened and closed all ${apps.size} apps"
        }
        installRx.matchEntire(p)?.let {
            val name = it.groupValues[1].trim().removeSuffix(" app")
            show("Play Store: installing $name"); return svc.playInstall(name, storeIds[name.lowercase()])
        }

        // web, YouTube, maps, dialer (dialer only opens — it never places the call)
        playRx.matchEntire(p)?.takeIf { it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty() }?.let {
            val q = it.groupValues[1].trim(); val secs = it.groupValues[3].ifEmpty { "0" }.toInt()
            show("YouTube: “$q”"); svc.youtube(q); Thread.sleep(6000)
            // tap the first video result, then let it play
            val tapped = svc.tapText(Regex("(?i).*${Regex.escape(q.split(' ').first())}.*"))
            if (secs > 0) { show("Playing for $secs s"); Thread.sleep(secs * 1000L + 1500) }
            return if (tapped) "Played “$q”${if (secs > 0) " for $secs seconds" else ""}" else "Opened YouTube results for “$q”"
        }
        youtube.matchEntire(p)?.let { val q = it.groupValues[1]; show("YouTube: “$q”"); svc.youtube(q); return "Searched YouTube for “$q”" }
        webSearch.matchEntire(p)?.let {
            if (!p.contains(" in ") || p.contains("the web") || p.contains("google")) {
                val q = part.trim().takeLast(it.groupValues[1].length)
                show("Searching the web for “$q”"); svc.webSearch(q); return "Searched the web for “$q”"
            }
        }
        navigate.matchEntire(p)?.let { val q = it.groupValues[1]; show("Directions to $q"); svc.navigate(q); return "Opened directions to $q" }
        dial.matchEntire(p)?.let { val n = it.groupValues[1].trim(); svc.dial(n); return "Opened the dialer with $n (tap call to call)" }

        // version, camera, settings pages, apps
        if (version.containsMatchIn(p)) {
            show("Opening Settings › About phone"); svc.openSettings("about"); Thread.sleep(2500)
            return "Your phone runs ${hyperOsVersion()}"
        }
        if (photo.containsMatchIn(p)) {
            show("Opening the camera"); svc.openCamera(); Thread.sleep(4000)
            show("Taking a picture"); val ok = svc.shutter(); Thread.sleep(2500)
            return if (ok) "Opened the camera and pressed the shutter" else "Couldn't press the shutter"
        }
        if (openCamera.matches(p)) { show("Opening the camera"); svc.openCamera(); Thread.sleep(3000); return "Opened the camera" }
        settingsPage.matchEntire(p)?.let {
            show("Opening ${it.groupValues[1]} settings"); svc.openSettings(it.groupValues[1]); Thread.sleep(2000)
            return "Opened ${it.groupValues[1]} settings"
        }
        openApp.matchEntire(p)?.let {
            val name = it.groupValues[1]
            if (!name.contains(" and ") && name.split(' ').size <= 4) {
                show("Opening $name")
                val opened = svc.openApp(name) ?: return "Skipped: “$name” isn't installed"
                Thread.sleep(2500)
                return "Opened $opened"
            }
        }
        return null
    }

    /** Tiny recursive-descent calculator: + - * / ^ % and brackets. */
    private class Calc(src: String) {
        private val s = src.replace('x', '*').replace('×', '*').replace('÷', '/').replace(" ", "")
        private var i = 0
        fun parse(): Double { val v = expr(); require(i == s.length); return v }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && s[i] in "+-") { val o = s[i++]; val t = term(); v = if (o == '+') v + t else v - t }
            return v
        }
        private fun term(): Double {
            var v = pow()
            while (i < s.length && s[i] in "*/%") {
                val o = s[i++]; val t = pow(); v = when (o) { '*' -> v * t; '/' -> v / t; else -> v % t }
            }
            return v
        }
        private fun pow(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, pow()) } else b }
        private fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else atom()
        private fun atom(): Double {
            if (s[i] == '(') { i++; val v = expr(); require(s[i++] == ')'); return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }
}
