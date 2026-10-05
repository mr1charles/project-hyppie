package dev.hyppie.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (48 * d).toInt(), (20 * d).toInt(), (24 * d).toInt())
        }
        fun header(t: String) = col.addView(TextView(this).apply {
            text = t; textSize = 16f; setPadding(0, (18 * d).toInt(), 0, (4 * d).toInt())
        })
        fun note(t: String) = col.addView(TextView(this).apply { text = t; textSize = 13f; alpha = 0.7f })
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); isSingleLine = true
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            col.addView(this)
        }

        col.addView(TextView(this).apply { text = "✦ AI Assistant"; textSize = 26f })
        header("Brain")
        val group = RadioGroup(this)
        val choices = listOf("ollama" to "Laptop AI (free, runs on your PC with Ollama)",
            "claude" to "Claude (API key from console.anthropic.com)",
            "gemini" to "Gemini (API key from aistudio.google.com)",
            "groq" to "Groq (free API key from console.groq.com)")
        choices.forEachIndexed { i, (id, label) ->
            group.addView(RadioButton(this).apply { text = label; this.id = 100 + i; isChecked = p.provider == id })
        }
        col.addView(group)

        header("Laptop AI")
        val urls = field("Addresses (comma separated)", p.ollamaUrls)
        val oModel = field("Model", p.ollamaModel)
        note("Plugged in by USB: http://127.0.0.1:11434 works after `adb reverse tcp:11434 tcp:11434`.\n" +
            "On Wi-Fi: your laptop's address, port 11435 (AI relay).")

        header("Claude")
        val cKey = field("API key (sk-ant-…)", p.claudeKey, secret = true)
        val cModel = field("Model", p.claudeModel)
        header("Groq")
        val qKey = field("API key (gsk_…)", p.groqKey, secret = true)
        val qModel = field("Model", p.groqModel)
        header("Gemini")
        val gKey = field("API key", p.geminiKey, secret = true)
        val gModel = field("Model", p.geminiModel)

        header("Voice")
        val speak = CheckBox(this).apply { text = "Speak answers out loud"; isChecked = p.speak }
        col.addView(speak)
        header("Super Island")
        val island = CheckBox(this).apply { text = "Always show the Super Island (also on the Always-On Display)"; isChecked = p.island }
        col.addView(island)

        header("Phone setup")
        col.addView(Button(this).apply {
            text = "Make this the phone's assistant"; isAllCaps = false
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        })
        note("Choose “Digital assistant app” → AI Assistant. Then hold the power button or swipe up from a bottom corner.")
        col.addView(Button(this).apply {
            text = "Turn on agent mode (Accessibility)"; isAllCaps = false
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        note("Lets the assistant tap, type and scroll for you. It always asks before sending, buying or deleting.")

        col.addView(Button(this).apply {
            text = "Save"; isAllCaps = false
            setOnClickListener {
                p.provider = choices[(group.checkedRadioButtonId - 100).coerceIn(0, choices.size - 1)].first
                p.groqKey = qKey.text.toString().trim(); p.groqModel = qModel.text.toString().trim()
                p.ollamaUrls = urls.text.toString(); p.ollamaModel = oModel.text.toString().trim()
                p.claudeKey = cKey.text.toString().trim(); p.claudeModel = cModel.text.toString().trim()
                p.geminiKey = gKey.text.toString().trim(); p.geminiModel = gModel.text.toString().trim()
                p.speak = speak.isChecked
                p.island = island.isChecked
                AgentAccessibilityService.instance?.island?.start()
                Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * d).toInt() })

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
