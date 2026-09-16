package com.ozkanmut.ilactakip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NtfyReprovisionFlagTest {
    @Test
    fun `successful reprovision can clear persistent reprovision requirement`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("dosefolk_ntfy_access_refresh", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("reprovision_required_v1", true)
            .commit()

        assertTrue(NtfyAccessRefresh.isReprovisionRequired(context))

        NtfyAccessRefresh.clearReprovisionRequired(context)

        assertFalse(NtfyAccessRefresh.isReprovisionRequired(context))
    }
}
