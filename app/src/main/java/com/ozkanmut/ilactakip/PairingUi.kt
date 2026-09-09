package com.ozkanmut.ilactakip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private data class PairPayload(val topic: String, val name: String)

private fun pairingPayload(c: Context): String = Uri.Builder()
    .scheme("dosefolk")
    .authority("pair")
    .appendQueryParameter("topic", Store.topic(c))
    .appendQueryParameter("name", Store.myName(c))
    .build()
    .toString()

private fun parsePairPayload(raw: String): PairPayload? {
    val value = raw.trim()
    if (value.startsWith("dosefolk://pair")) {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        val topic = uri.getQueryParameter("topic").orEmpty().trim()
        val name = uri.getQueryParameter("name").orEmpty().trim()
        if (topic.isBlank()) return null
        return PairPayload(topic, name)
    }
    if (value.startsWith("dosefolk-") && value.length >= 12) return PairPayload(value, "")
    return null
}

private fun qrBitmap(value: String, size: Int = 720): Bitmap {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
}

@Composable
fun PairingCard(c: Context, people: List<Person>, save: (List<Person>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var showQr by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var pendingRevoke by remember { mutableStateOf<Person?>(null) }
    var rePairing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ownTopic = remember { Store.topic(c) }
    val payload = remember(ownTopic) { pairingPayload(c) }
    val qr = remember(payload) { qrBitmap(payload) }

    fun finishPair(targetTopic: String) {
        val updated = people + Person(UUID.randomUUID().toString(), name.trim(), targetTopic)
        save(updated)
        CircleInitialSync.publishToPeer(c, targetTopic)
        name = ""
        topic = ""
        scanMessage = if (I18n.language() == "tr") "Circle'a eklendi; ilaç programı, kurallar, ayrıntılar ve stok senkron sırasına alındı." else "Added to Circle; medication program, rules, details, and stock were queued for sync."
    }

    val scannerOptions = remember { GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build() }
    val scanner = remember { GmsBarcodeScanning.getClient(c, scannerOptions) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (I18n.language() == "tr") "Eşleştirme" else "Pairing", fontWeight = FontWeight.Bold)
            Text(if (I18n.language() == "tr") "QR ile eşleştir; gerekirse topic kodunu elle de kullanabilirsin." else "Pair with QR, or use the topic code manually when needed.", style = MaterialTheme.typography.bodySmall)
            Text(if (I18n.language() == "tr") "Topic kodum" else "My topic code", fontWeight = FontWeight.Bold)
            SelectionContainer { Text(ownTopic, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val clipboard = c.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Dosefolk topic", ownTopic))
                    scanMessage = if (I18n.language() == "tr") "Topic kodu kopyalandı." else "Topic code copied."
                }) { Text(if (I18n.language() == "tr") "Kopyala" else "Copy") }
                OutlinedButton(onClick = { showQr = !showQr }) { Text(if (I18n.language() == "tr") if (showQr) "QR'ı gizle" else "QR göster" else if (showQr) "Hide QR" else "Show QR") }
            }
            if (showQr) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(qr.asImageBitmap(), contentDescription = "Dosefolk pairing QR", modifier = Modifier.size(240.dp))
                Text(if (I18n.language() == "tr") "Diğer telefondan bu QR'ı okut." else "Scan this QR from the other phone.", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                scanner.startScan().addOnSuccessListener { barcode ->
                    val parsed = barcode.rawValue?.let(::parsePairPayload)
                    if (parsed == null) scanMessage = if (I18n.language() == "tr") "Bu Dosefolk eşleştirme QR'ı değil." else "This is not a Dosefolk pairing QR."
                    else if (parsed.topic == ownTopic) scanMessage = if (I18n.language() == "tr") "Bu QR bu telefona ait." else "This QR belongs to this phone."
                    else { topic = parsed.topic; if (parsed.name.isNotBlank()) name = parsed.name; scanMessage = if (I18n.language() == "tr") "QR okundu. İsmi kontrol edip ekle." else "QR scanned. Check the name and add." }
                }.addOnFailureListener { scanMessage = if (I18n.language() == "tr") "QR tarayıcı açılamadı. Topic kodunu elle girebilirsin." else "QR scanner could not open. You can enter the topic code manually." }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (I18n.language() == "tr") "QR tara" else "Scan QR") }

            OutlinedTextField(name, { name = it }, label = { Text(if (I18n.language() == "tr") "Kişinin adı" else "Person name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(topic, { topic = it.trim() }, label = { Text(if (I18n.language() == "tr") "Topic kodu" else "Topic code") }, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = !rePairing && name.isNotBlank() && topic.isNotBlank() && topic != ownTopic && people.none { it.topic == topic },
                onClick = {
                    val targetTopic = topic.trim()
                    if (!RevokedPeerFence.isRevoked(c, targetTopic)) {
                        finishPair(targetTopic)
                    } else {
                        rePairing = true
                        scanMessage = if (I18n.language() == "tr") "Eski eşleşme kuyruğu güvenli şekilde temizleniyor…" else "Safely clearing the previous relationship backlog…"
                        scope.launch {
                            val ready = withContext(Dispatchers.IO) { PairingLifecycle.prepareRePair(c, targetTopic) }
                            rePairing = false
                            if (ready) finishPair(targetTopic)
                            else scanMessage = if (I18n.language() == "tr") "Eski eşleşme kuyruğu doğrulanamadı. İnternet bağlantısını kontrol edip tekrar dene." else "The previous relationship backlog could not be verified. Check the connection and try again."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (I18n.language() == "tr") if (rePairing) "Kontrol ediliyor…" else "Circle'a ekle" else if (rePairing) "Checking…" else "Add to Circle") }

            scanMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (topic.isNotBlank() && people.any { it.topic == topic }) Text(if (I18n.language() == "tr") "Bu kişi zaten Circle'da." else "This person is already in Circle.", style = MaterialTheme.typography.bodySmall)

            if (people.isNotEmpty()) {
                HorizontalDivider()
                Text(if (I18n.language() == "tr") "Eşleşmeleri yönet" else "Manage pairings", fontWeight = FontWeight.Bold)
                people.forEach { person -> Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(person.name, fontWeight = FontWeight.Bold); SelectionContainer { Text(person.topic, style = MaterialTheme.typography.bodySmall) } }
                        TextButton(onClick = { pendingRevoke = person }) { Text(if (I18n.language() == "tr") "Çıkar" else "Remove") }
                    }
                } }
            }
        }
    }

    pendingRevoke?.let { person ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text(if (I18n.language() == "tr") "Circle'dan çıkar?" else "Remove from Circle?") },
            text = { Text(if (I18n.language() == "tr") "${person.name} artık bu telefonda yetkili olmayacak ve yeni Dosefolk kayıtları bu kişiye gönderilmeyecek." else "${person.name} will no longer be authorized on this phone and new Dosefolk records will no longer be sent to them.") },
            confirmButton = { Button(onClick = {
                PairingLifecycle.revoke(c, person)
                save(Store.people(c))
                pendingRevoke = null
                scanMessage = if (I18n.language() == "tr") "Eşleşme kaldırıldı ve yetkiler iptal edildi." else "Pairing removed and permissions revoked."
            }) { Text(if (I18n.language() == "tr") "Çıkar" else "Remove") } },
            dismissButton = { TextButton(onClick = { pendingRevoke = null }) { Text(if (I18n.language() == "tr") "Vazgeç" else "Cancel") } }
        )
    }
}
