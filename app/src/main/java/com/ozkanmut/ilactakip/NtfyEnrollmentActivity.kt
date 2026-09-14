package com.ozkanmut.ilactakip

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class NtfyEnrollmentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let { NtfyProvisioning.handleEnrollmentUrl(applicationContext, it) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
