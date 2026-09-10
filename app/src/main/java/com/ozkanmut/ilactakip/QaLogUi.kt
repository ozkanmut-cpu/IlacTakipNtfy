package com.ozkanmut.ilactakip

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider

@Composable
fun QaLogControls(c: Context) {
    var refresh by remember { mutableIntStateOf(0) }
    val sizeKb = remember(refresh) { DosefolkQaLog.exportFile(c).takeIf { it.exists() }?.length()?.div(1024L) ?: 0L }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (I18n.language() == "tr") "QA tanılama kaydı • ${sizeKb} KB" else "QA diagnostic log • ${sizeKb} KB")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                DosefolkQaLog.record(c, DosefolkQaLog.Category.APP, "qa_log_shared")
                val file = DosefolkQaLog.exportFile(c)
                val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/x-ndjson"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Dosefolk QA log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                c.startActivity(Intent.createChooser(intent, if (I18n.language() == "tr") "QA logunu paylaş" else "Share QA log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                refresh++
            }) { Text(if (I18n.language() == "tr") "QA logunu paylaş" else "Share QA log") }
            TextButton(onClick = { DosefolkQaLog.clear(c); refresh++ }) {
                Text(if (I18n.language() == "tr") "Temizle" else "Clear")
            }
        }
    }
}
