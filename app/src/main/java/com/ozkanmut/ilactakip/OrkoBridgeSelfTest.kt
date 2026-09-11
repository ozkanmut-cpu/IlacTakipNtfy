package com.ozkanmut.ilactakip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.UUID

object OrkoBridgeSelfTest {
    const val ACTION_SELF_TEST = "com.dosefolk.action.ORKO_BRIDGE_SELF_TEST"
    const val ACTION_SELF_TEST_ACK = "com.dosefolk.action.ORKO_BRIDGE_SELF_TEST_ACK"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_SENT_AT_MS = "sentAtMs"

    private const val PREFS = "orko_bridge_self_test"
    private const val KEY_TOKEN = "token"
    private const val KEY_SENT_AT = "sent_at"
    private const val KEY_ACK_AT = "ack_at"
    private const val TIMEOUT_MS = 6_000L

    data class Status(
        val token: String = "",
        val sentAtMs: Long = 0L,
        val ackAtMs: Long = 0L
    ) {
        val acknowledged: Boolean get() = token.isNotBlank() && ackAtMs >= sentAtMs && sentAtMs > 0L
        val pending: Boolean get() = token.isNotBlank() && sentAtMs > 0L && !acknowledged
        fun timedOut(nowMs: Long = System.currentTimeMillis()): Boolean =
            pending && nowMs - sentAtMs >= TIMEOUT_MS
    }

    fun send(context: Context): Status {
        val token = UUID.randomUUID().toString()
        val sentAt = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_SENT_AT, sentAt)
            .putLong(KEY_ACK_AT, 0L)
            .commit()

        context.sendBroadcast(
            Intent(ACTION_SELF_TEST)
                .setPackage(OrkoTakipBridge.ORKO_PACKAGE)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_SENT_AT_MS, sentAt)
        )
        DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "orko_bridge_self_test_sent")
        return load(context)
    }

    fun load(context: Context): Status {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Status(
            token = p.getString(KEY_TOKEN, "").orEmpty(),
            sentAtMs = p.getLong(KEY_SENT_AT, 0L),
            ackAtMs = p.getLong(KEY_ACK_AT, 0L)
        )
    }

    internal fun acknowledge(context: Context, token: String) {
        val current = load(context)
        if (token.isBlank() || token != current.token) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ACK_AT, System.currentTimeMillis())
            .apply()
        DosefolkQaLog.record(context, DosefolkQaLog.Category.SYNC, "orko_bridge_self_test_ack")
    }
}

class OrkoBridgeSelfTestAckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OrkoBridgeSelfTest.ACTION_SELF_TEST_ACK) return
        if (Build.VERSION.SDK_INT >= 34) {
            val sender = sentFromPackage
            if (sender != null && sender != OrkoTakipBridge.ORKO_PACKAGE) return
        }
        OrkoBridgeSelfTest.acknowledge(
            context.applicationContext,
            intent.getStringExtra(OrkoBridgeSelfTest.EXTRA_TOKEN).orEmpty()
        )
    }
}
