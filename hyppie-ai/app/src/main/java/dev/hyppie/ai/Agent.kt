package dev.hyppie.ai

import org.json.JSONObject

/**
 * Step-by-step phone agent. Each step: read the screen -> ask the model for ONE action as JSON ->
 * (ask the user first if it looks risky) -> do it -> wait for the screen to settle.
 * Plain JSON instead of tool calling, because small local models follow that more reliably.
 */
class Agent(private val svc: AgentAccessibilityService, private val llm: Llm) {

    private val maxStepsPerPart = 10
    private val risky = Regex(
        "\\b(send|pay|buy|purchase|order|checkout|delete|remove|erase|reset|uninstall|post|publish|transfer|" +
            "subscribe|confirm|format|factory|call)\\b", RegexOption.IGNORE_CASE)

    private val system = """
You control an Android phone (Xiaomi HyperOS) for the user, one step at a time.
Each turn you get the task, the steps done so far, the current app, and the screen as a numbered list of elements.
Reply with ONLY one JSON object, no other text:
{"thought": "<short reason>", "action": "<action>", ...}
Actions:
  {"action":"open_app","name":"Settings"}     open an app by name (fastest way to start)
  {"action":"tap","id":4}                     tap element [4]
  {"action":"type","id":2,"text":"hello"}     type into input [2]
  {"action":"scroll","direction":"down"}      or "up", to find more elements
  {"action":"back"}  {"action":"home"}
  {"action":"flashlight","on":true}           turn the flashlight on or off (false)
  {"action":"web_search","query":"..."}       search the web in the browser
  {"action":"open_settings","page":"about"}   pages: about, wifi, bluetooth, display, battery, sound, apps, main
  {"action":"command","text":"install Roblox"}  run any built-in skill by name, e.g. "show a notification saying hi",
      "set volume to max", "take a screenshot", "play X on youtube for 5 seconds", "create a note with <text>"
  {"action":"done","answer":"<what you did or the answer>"}   when the task is finished
  {"action":"ask","question":"<question>"}    if you need information from the user
Rules: only use ids that are in the current list. If what you need is not visible, scroll.
Never pay, buy, send messages, delete or change accounts unless the task clearly asks for it.
If the task needs the user to sign in, create an account or enter a password, do NOT try: reply
{"action":"done","answer":"Skipped: needs you to sign in"}.
This phone: the working camera app is "Aperture" (the Xiaomi "Camera" app does not work); in Aperture the
shutter button is the big round button at the bottom. The HyperOS version is under Settings > About phone.
Finish with "done" as soon as the whole task is complete.
""".trimIndent()

    /** Runs the whole request: split into parts, built-in commands first, the AI for the rest. */
    fun run(task: String, progress: (String) -> Unit): String {
        svc.stopRequested = false
        val parts = plan(task) ?: QuickCommands.split(task)
        var lastAnswer = ""
        val results = mutableListOf<String>()
        svc.setChecklist(parts.map { it.replaceFirstChar { c -> c.uppercase() } })
        parts.forEachIndexed { i, part ->
            if (svc.stopRequested) { results.add("⏹ Stopped before: $part"); return results.joinToString("\n") }
            svc.markPart(i, 1)
            val head = if (parts.size > 1) "Part ${i + 1}/${parts.size} · " else ""
            val show: (String) -> Unit = { progress(head + it); svc.setStep(it, GlowView.Mode.ACTING) }
            // apps can't open over the lock screen: wait for the user instead of failing the step
            if (svc.isLocked() && needsScreen.containsMatchIn(part)) {
                show("🔒 Unlock your phone to continue")
                var w = 0
                while (svc.isLocked() && w < 90 && !svc.stopRequested) { Thread.sleep(1000); w++ }
                if (svc.isLocked()) { results.add("⏭ Needs you: unlock the phone, then ask again ($part)"); svc.markPart(i, 3, "Phone locked"); return@forEachIndexed }
                Thread.sleep(800)
            }
            val askM = Regex("^ask[:,]?\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(part.trim())
            val step = part.replace(Regex("\\b(the answer|that answer|it|the copied text|the clipboard)\\b(?=\\s*$)", RegexOption.IGNORE_CASE), Regex.escapeReplacement(lastAnswer).ifBlank { "$0" })
            val r = if (askM != null) {
                show("Asking: ${askM.groupValues[1]}")
                lastAnswer = try {
                    llm.chat("Answer in 1-3 short sentences, plain text, no markdown.", listOf(Msg("user", askM.groupValues[1])), maxTokens = 250)
                } catch (e: Exception) { "" }
                if (lastAnswer.isBlank()) "Couldn't get an answer (offline?)"
                else { svc.copy(lastAnswer); "Answer copied: ${lastAnswer.take(80)}" }
            } else (QuickCommands.run(step, svc, show) ?: missingApp(step, show) ?: runPart(step, if (parts.size > 1) step else task, head, progress)).also { res ->
                // a web search also captures a short answer, so "copy/paste it" / "create a note with the answer" works
                Regex("^Searched the web for “(.+)”$").find(res)?.let { m ->
                    runCatching {
                        llm.chat("Answer in 1-3 short sentences, plain text, no markdown.", listOf(Msg("user", m.groupValues[1])), maxTokens = 250)
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { lastAnswer = it; svc.copy(it) }
                }
            }
            progress("$head→ $r")
            val bad = Regex("^(Skipped|Couldn|Agent stopped|Needs you|Wallpaper failed)|\\bneeds\\b.*\\bpermission\\b|\\bnot installed\\b|\\bisn.t installed\\b",
                RegexOption.IGNORE_CASE).containsMatchIn(r)
            svc.markPart(i, if (bad) 3 else 2, r)
            results.add((if (bad) "⏭ " else "✓ ") + r)
            Thread.sleep(450)
        }
        return results.joinToString("\n")
    }

    private val planner = """
Turn the user's phone request into an ordered list of short steps for a phone assistant.
Fix typos and apply the user's own corrections (e.g. "yellow world ... I meant hello world" -> "hello world").
Resolve conditions sensibly. Expand loops into the single step that does it.
Prefer these exact step forms when they fit (the assistant runs them instantly):
  open <app> | go home | close the app | open and close every app
  install <app>   (Play Store; it skips by itself if the Play Store isn't signed in)
  take a screenshot | set brightness to max | set volume to max | set volume to zero
  turn on/off <wifi|bluetooth|flashlight|dark mode|...>
  show a notification saying <text>
  ask: <question>            (the AI answers; the answer is copied to the clipboard)
  (a "search the web for ..." step also copies a short answer, so a later "create a note with the answer" works)
  create a note with the answer   (pastes the last answer into a new note)
  play <song> on youtube for <N> seconds
  wait <N> seconds | swipe left and right <N> times
  search the web for <query> | set a timer for <N> minutes | check my HyperOS version
Anything else: one plain-English instruction for the screen-operating agent (e.g. "open Google Classroom and
read whether there is any homework due").
Reply with ONLY JSON: {"steps": ["...", "..."]}
""".trimIndent()

    /** Uses the AI to turn a messy request into clean steps; null if offline or it's a single simple command. */
    private fun plan(task: String): List<String>? {
        val simple = QuickCommands.split(task)
        if (simple.size <= 1 && QuickCommands.isCommand(task)) return null
        return try {
            val r = llm.chat(planner, listOf(Msg("user", task)), json = true, maxTokens = 700)
            val o = JSONObject(r.substring(r.indexOf('{'), r.lastIndexOf('}') + 1))
            val a = o.getJSONArray("steps")
            (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() }.take(30).ifEmpty { null }
        } catch (e: Exception) { null }
    }

    private val needsScreen = Regex("(?i)\\b(open|install|play|search|go to|classroom|note|swipe|close|every|app|store|check|read|tap|youtube|camera|photo)\\b")

    /** If the step needs a known app that isn't installed (e.g. Google Classroom), install it and say so. */
    private fun missingApp(step: String, show: (String) -> Unit): String? {
        val low = step.lowercase()
        val hit = QuickCommands.storeIds.entries.sortedByDescending { it.key.length }
            .firstOrNull { (k, _) -> Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(low) } ?: return null
        if (svc.isInstalled(hit.value)) return null
        val name = hit.key.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        show("$name isn't installed — installing it")
        val r = svc.playInstall(name, hit.value)
        return "Needs you: $name wasn't installed — ${r.lowercase()}. Sign in to it, then ask again."
    }

    /** AI loop for one part of the request. */
    private fun runPart(task: String, whole: String, head: String, progress: (String) -> Unit): String {
        val log = mutableListOf<String>()
        for (step in 1..maxStepsPerPart) {
            if (svc.stopRequested) return "Stopped."
            val screen = svc.describeScreen(45)
            val prompt = buildString {
                append("Task: ").append(task).append('\n')
                if (whole != task) append("(It is one part of the user's request: ").append(whole).append(")\n")
                append("Steps so far:\n").append(if (log.isEmpty()) "(none)\n" else log.joinToString("\n", postfix = "\n"))
                append("Current app: ").append(svc.currentApp()).append('\n')
                append("Screen:\n").append(screen)
            }
            progress("${head}Step $step · thinking…")
            svc.setStep("Thinking…", GlowView.Mode.THINKING)
            val reply = try {
                llm.chat(system, listOf(Msg("user", prompt)), json = true, maxTokens = 200)
            } catch (e: Exception) {
                return "Agent stopped: ${e.message}"
            }
            val a = parse(reply) ?: run {
                log.add("$step. (invalid reply, retrying)")
                return@run null
            } ?: continue
            if (svc.stopRequested) return "Stopped."
            android.util.Log.i("HyppieAgent", "model: ${reply.take(300)}")
            val action = a.optString("action")
            val thought = a.optString("thought").take(120)
            val line = describe(a)
            progress("${head}Step $step · $line")
            svc.setStep(line, GlowView.Mode.ACTING)

            when (action) {
                "done" -> return a.optString("answer", "Done.").ifBlank { "Done." }
                "ask" -> return "Needs you: " + a.optString("question", "I need more information.")
            }
            val target = if (a.has("id")) svc.labelOf(a.optInt("id")) else ""
            if (action == "tap" && Regex("\\b(sign in|log in|login|sign up|create account)\\b", RegexOption.IGNORE_CASE).containsMatchIn(target))
                return "Skipped “$task”: it needs you to sign in first"
            if (risky.containsMatchIn("$action $target ${a.optString("text")}") && action in setOf("tap", "type")) {
                if (!svc.confirm("Allow: $line?")) {
                    if (svc.stopRequested) return "Stopped."
                    log.add("$step. $line -> SKIPPED by the user (do not try it again)")
                    continue
                }
            }
            val ok: Boolean = when (action) {
                "open_app" -> (svc.openApp(a.optString("name")) ?: return "Skipped: “${a.optString("name")}” isn't installed").isNotEmpty()
                "tap" -> svc.tap(a.optInt("id", -1))
                "type" -> svc.type(a.optInt("id", -1), a.optString("text"))
                "scroll" -> svc.scroll(a.optString("direction", "down") != "up")
                "back" -> svc.back()
                "home" -> svc.home()
                "flashlight" -> svc.flashlight(a.optBoolean("on", true))
                "web_search" -> svc.webSearch(a.optString("query"))
                "open_settings" -> svc.openSettings(a.optString("page", "main"))
                "command" -> QuickCommands.run(a.optString("text"), svc) { } != null
                else -> false
            }
            log.add("$step. $line -> ${if (ok) "ok" else "FAILED"}")
            Thread.sleep(if (action in setOf("open_app", "web_search", "open_settings")) 2500 else 1300)
        }
        return "Skipped “$task” (not finished after $maxStepsPerPart steps)"
    }

    private fun parse(reply: String): JSONObject? {
        val s = reply.indexOf('{'); val e = reply.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return runCatching { JSONObject(reply.substring(s, e + 1)) }.getOrNull()?.takeIf { it.has("action") }
    }

    private fun describe(a: JSONObject): String = when (a.optString("action")) {
        "open_app" -> "Open ${a.optString("name")}"
        "tap" -> "Tap \"${svc.labelOf(a.optInt("id", -1)).take(40)}\""
        "type" -> "Type \"${a.optString("text").take(40)}\""
        "scroll" -> "Scroll ${a.optString("direction", "down")}"
        "back" -> "Go back"
        "home" -> "Go home"
        "flashlight" -> "Flashlight ${if (a.optBoolean("on", true)) "on" else "off"}"
        "web_search" -> "Search the web for \"${a.optString("query").take(40)}\""
        "open_settings" -> "Open Settings › ${a.optString("page", "main")}"
        "command" -> a.optString("text").take(50)
        "done" -> "Done"
        "ask" -> "Question"
        else -> a.optString("action")
    }
}
