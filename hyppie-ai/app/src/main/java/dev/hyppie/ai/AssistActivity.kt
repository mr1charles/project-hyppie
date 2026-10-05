package dev.hyppie.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class AssistActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var llm: Llm
    private lateinit var glow: GlowView
    private lateinit var answer: TextView
    private lateinit var input: EditText
    private lateinit var mic: Button
    private lateinit var agentToggle: Button
    private var agentMode = false
    private var speech: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val history = mutableListOf<Msg>()

    private val commandLike = Regex(
        "^(please )?(open|launch|start|turn (on|off)|switch|enable|disable|go to|take me|set|change|play|search for|" +
            "find .* in|show me .* settings|connect|call up)\\b", RegexOption.IGNORE_CASE)

    private val chatSystem = """
You are the AI Assistant on the user's phone. Facts: the phone is a Samsung Galaxy A13 that runs
Xiaomi HyperOS 3 (Xiaomi's Android-based operating system), installed through a community port called Project Hyppie.
Answer the user's actual question. Be friendly and brief: 1-4 sentences unless asked for more. Plain text, no markdown.
If a screen description is included, use it to answer questions about what is on the screen.
""".trimIndent()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        llm = Llm(prefs)
        window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this)
        root.setOnClickListener { finish() }   // tap outside the card closes the assistant
        // soft dark gradient at the bottom so the card reads on any wallpaper
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 0, 0, 0), Color.argb(150, 0, 0, 0)))
        glow = GlowView(this)
        root.addView(glow, FrameLayout.LayoutParams(-1, -1))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(30).toFloat()
                setColor(Color.argb(232, 22, 22, 34))
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            elevation = dp(12).toFloat()
            isClickable = true   // don't close when tapping inside the card
        }
        val title = TextView(this).apply {
            text = "✦ Assistant · ${llm.label()}"
            setTextColor(Color.argb(200, 199, 210, 254)); textSize = 13f
        }
        Thread {
            val online = llm.reachable()
            runOnUiThread { title.text = if (online) "✦ Assistant · ${llm.label()}" else "✦ Assistant · Offline (quick commands)" }
        }.start()
        answer = TextView(this).apply {
            text = "Hi! Ask me anything, or tell me what to do on your phone."
            setTextColor(Color.WHITE); textSize = 17f
            maxHeight = dp(260)
            movementMethod = ScrollingMovementMethod()
            setPadding(0, dp(8), 0, dp(12))
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        agentToggle = chip("⚡ Agent: off") { setAgent(!agentMode) }
        chips.addView(agentToggle)
        chips.addView(chip("⚙ Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Ask or tell me to do something…"
            setHintTextColor(Color.argb(140, 255, 255, 255)); setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.argb(60, 255, 255, 255)) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        mic = roundButton("🎤") { listen() }
        row.addView(mic)
        row.addView(roundButton("➤") { submit() })

        card.addView(title); card.addView(answer); card.addView(chips)
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(40)) })
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val ime = ins.getInsets(android.view.WindowInsets.Type.ime()).bottom
            (card.layoutParams as FrameLayout.LayoutParams).bottomMargin = maxOf(dp(40), ime + dp(12))
            card.requestLayout(); ins
        }

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND) listen()
        handleTaskExtra(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleTaskExtra(i) }

    /** `am start -n dev.hyppie.ai/.AssistActivity --es task "…" [--ez agent true]` (used for demos/automation). */
    private fun handleTaskExtra(i: Intent?) {
        // `--es wallpaper <home.jpg> --es lock_wallpaper <lock.jpg>`: set theme wallpapers (MTZ themes applied
        // outside the Themes app don't set them)
        i?.getStringExtra("wallpaper")?.let { home ->
            val lock = i.getStringExtra("lock_wallpaper")
            Thread {
                val wm = android.app.WallpaperManager.getInstance(this)
                val r = runCatching {
                    java.io.FileInputStream(home).use { wm.setStream(it, null, true, android.app.WallpaperManager.FLAG_SYSTEM) }
                    if (lock != null) java.io.FileInputStream(lock).use {
                        wm.setStream(it, null, true, android.app.WallpaperManager.FLAG_LOCK)
                    }
                    "Wallpapers set"
                }.getOrElse { "Wallpaper failed: ${it.message}" }
                android.util.Log.i("HyppieAgent", r)
                runOnUiThread { answer.text = r; finish() }
            }.start()
            i.removeExtra("wallpaper")
            return
        }
        val task = i?.getStringExtra("task") ?: return
        i.removeExtra("task")
        if (i.getBooleanExtra("agent", false)) setAgent(true)
        input.setText(task)
        input.postDelayed({ submit() }, 2500)
    }

    private fun chip(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.argb(50, 255, 255, 255)) }
        minHeight = 0; minimumHeight = 0; setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
    }

    private fun roundButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 18f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#4F46E5")) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) }
    }

    private fun setAgent(on: Boolean) {
        agentMode = on
        agentToggle.text = if (on) "⚡ Agent: on" else "⚡ Agent: off"
        (agentToggle.background as GradientDrawable).setColor(
            if (on) Color.parseColor("#4F46E5") else Color.argb(50, 255, 255, 255))
    }

    // ------------------------------------------------------------------ voice

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { answer.text = "Voice input isn't available, type instead."; return }
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { glow.mode = GlowView.Mode.LISTENING; answer.text = "Listening…" }
                override fun onPartialResults(r: Bundle?) {
                    r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input.setText(it) }
                }
                override fun onResults(r: Bundle?) {
                    glow.mode = GlowView.Mode.IDLE
                    r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { input.setText(it); submit() }
                }
                override fun onError(e: Int) { glow.mode = GlowView.Mode.IDLE; answer.text = "Didn't catch that. Tap 🎤 or type." }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { glow.mode = GlowView.Mode.THINKING }
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen()
    }

    // ------------------------------------------------------------------ ask / act

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        if (agentMode || commandLike.containsMatchIn(text) || QuickCommands.isCommand(text)) { runAgent(text); return }
        QuickCommands.answer(this, text)?.let { answer.text = it; history.add(Msg("user", text)); history.add(Msg("assistant", it)); return }
        ask(text)
    }

    private fun ask(text: String) {
        glow.mode = GlowView.Mode.THINKING
        answer.text = "Thinking…"
        val screen = AgentAccessibilityService.instance
            ?.takeIf { text.contains(Regex("screen|this page|this app|what('| i)s (this|here)", RegexOption.IGNORE_CASE)) }
            ?.let { "\n\n[Screen (${it.currentApp()})]\n${it.describeScreen(50)}" } ?: ""
        history.add(Msg("user", text + screen))
        Thread {
            val reply = try { llm.chat(chatSystem, history.takeLast(10)) } catch (e: Exception) { "⚠️ ${e.message}" }
            history.add(Msg("assistant", reply))
            runOnUiThread {
                answer.text = reply
                glow.mode = GlowView.Mode.IDLE
                if (prefs.speak) tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "a")
            }
        }.start()
    }

    private fun runAgent(task: String) {
        val svc = AgentAccessibilityService.instance
        if (svc == null || !AgentAccessibilityService.isEnabled(this)) {
            answer.text = "To do things for you, turn on “AI Assistant agent” in Accessibility (one time). Opening it now…"
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val speak = prefs.speak
        val t = tts
        svc.showOverlay()
        svc.setStep("Starting: $task", GlowView.Mode.THINKING)
        moveTaskToBack(true)   // get out of the way so the agent sees and drives the real screen
        Thread {
            runCatching { java.io.File(filesDir, "last_result.txt").delete() }
            val result = Agent(svc, llm).run(task) { line ->
                android.util.Log.i("HyppieAgent", line)
                runCatching { java.io.File(filesDir, "progress.txt").appendText(line + "\n") }
            }
            android.util.Log.i("HyppieAgent", "RESULT: $result")
            runCatching { java.io.File(filesDir, "last_result.txt").writeText("${System.currentTimeMillis()}\n$result") }
            svc.setStep("", GlowView.Mode.IDLE)
            svc.setButtons(listOf("Close" to { svc.hideOverlay() }))
            if (speak) t?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "r")
            Thread.sleep(15000)
            svc.hideOverlay()
        }.start()
        finish()
    }

    override fun onDestroy() {
        speech?.destroy()
        super.onDestroy()
    }
}
