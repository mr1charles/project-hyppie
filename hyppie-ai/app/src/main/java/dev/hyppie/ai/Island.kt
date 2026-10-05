package dev.hyppie.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Bundle
import org.json.JSONObject

/**
 * Always-on Super Island: one ongoing HyperOS "focus notification" (miui.focus.param) with a spark icon in
 * the small island, battery % when expanded, and the battery on the Always-On Display.
 * Posted from the already-running agent service and refreshed only on battery changes: no extra process.
 */
class Island(private val ctx: Context) {

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private var registered = false
    private var lastText = ""

    private var lastPost = 0L
    private var pct = 0
    private var charging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_BATTERY_CHANGED) {
                pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING
                post(force = false)
            } else if (System.currentTimeMillis() - lastPost > 6 * 3600_000L) {
                post(force = true)   // screen on: refresh well before HyperOS's 12 h island timeout
            }
        }
    }

    fun start() {
        if (!Prefs(ctx).island) { stop(); return }
        nm.deleteNotificationChannel("hyppie_island")   // old low-importance channel: the island ignored it
        // HyperOS only turns focus notifications of DEFAULT+ importance into an island; keep it silent
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Super Island", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Keeps the Super Island visible"; setShowBadge(false)
            setSound(null, null); enableVibration(false); enableLights(false)
        })
        if (!registered) {   // the sticky battery broadcast posts the first island immediately
            ctx.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_SCREEN_ON)
            })
            registered = true
        }
    }

    fun stop() {
        if (registered) { runCatching { ctx.unregisterReceiver(receiver) }; registered = false }
        nm.cancel(ID)
    }

    /** Same JSON layout HyperOS's own Clock uses for its timer island (miui.focus.param / param_v2). */
    private fun post(force: Boolean) {
        val right = if (charging) "$pct% ⚡" else "$pct%"
        if (!force && right == lastText) return            // nothing changed: don't redraw the island
        lastText = right; lastPost = System.currentTimeMillis()
        val pic = "miui.focus.pic_spark"
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("islandOrder", true)
            .put("bigIslandArea", JSONObject()
                .put("imageTextInfoLeft", JSONObject().put("type", 1)
                    .put("picInfo", JSONObject().put("type", 1).put("pic", pic)))
                .put("imageTextInfoRight", JSONObject().put("type", 2)
                    .put("textInfo", JSONObject().put("title", right))))
            .put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", pic)))
            .put("islandTimeout", 43200)
        val v2 = JSONObject()
            .put("protocol", 1).put("updatable", true).put("enableFloat", false)
            .put("ticker", "Assistant").put("aodTitle", "Battery $right").put("aodPic", pic)
            .put("baseInfo", JSONObject().put("type", 2).put("title", "Assistant ready")
                .put("content", "Hold the side button · battery $right"))
            .put("param_island", island)
            .put("islandFirstFloat", false)
            .put("business", "assistant")
        val param = JSONObject()
            .put("protocol", 1).put("scene", "assistant").put("ticker", "Assistant")
            .put("content", "Assistant ready").put("enableFloat", false).put("updatable", true)
            .put("param_v2", v2)
        val icon = Icon.createWithResource(ctx, R.drawable.ic_spark)
        val extras = Bundle().apply {
            putString("miui.focus.param", param.toString())
            putBundle("miui.focus.pics", Bundle().apply { putParcelable(pic, icon) })
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, AssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_spark)
            .setContentTitle("Assistant ready")
            .setContentText("Hold the side button · battery $right")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .addExtras(extras)
            .build()
        nm.notify(ID, n)
    }

    companion object {
        const val CHANNEL = "assistant_island"
        const val ID = 4242
    }
}
