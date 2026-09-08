package com.ozkanmut.ilactakip

import java.util.Locale

/** Dosefolk runtime localization. Follows the phone's primary language automatically. */
object I18n {
    private val en = mapOf(
        "app" to "Dosefolk",
        "today" to "Today",
        "meds" to "My meds",
        "follow" to "Circle",
        "history" to "History",
        "assistant" to "Assistant",
        "today_plan" to "Today's medication plan",
        "manage_voice" to "Speak or type to manage",
        "med_times" to "{0} medication times • {1} medications",
        "med_count" to "{0} meds",
        "taken" to "Taken",
        "snooze" to "+30 min",
        "missed" to "Not taken",
        "add_med" to "Add medication",
        "new_med" to "New medication",
        "med_name" to "Medication name",
        "dose_note" to "Dose / note",
        "add_time" to "+ Add time",
        "save" to "Save",
        "cancel" to "Cancel",
        "delete" to "Delete",
        "care_permissions" to "Circle & permissions",
        "care_help" to "People you trust can help track doses. Only explicitly authorized people can edit medication plans.",
        "can_edit" to "Can edit medication plan",
        "remind" to "Remind",
        "add_person" to "Add person",
        "name" to "Name",
        "pair_code" to "Pairing code",
        "add" to "Add",
        "my_code" to "My pairing code: {0}",
        "history_empty" to "Taken, snoozed, missed and plan-change events will appear here.",
        "ai_title" to "Dosefolk Assistant",
        "ai_help" to "Speak or type naturally. Dosefolk handles simple actions immediately and keeps safety-critical changes explicit.",
        "ai_ready" to "Ready. Tell me what you want to do.",
        "ai_example" to "e.g. snooze the 20:00 medications for 30 minutes",
        "send" to "Send",
        "speak" to "🎙 Speak",
        "speech_prompt" to "Tell Dosefolk",
        "channel" to "Medication reminders",
        "all_taken" to "All taken",
        "snooze30" to "Snooze 30 min",
        "notif_missed" to "Not taken",
        "event_taken" to "MEDICATIONS TAKEN",
        "event_missed" to "MEDICATIONS NOT TAKEN",
        "event_snoozed" to "SNOOZED 30 MIN",
        "event_alarm" to "MEDICATION TIME",
        "reminder_title" to "REMINDER",
        "reminder_body" to "Please check the medication dose.",
        "all_good" to "Everything is on track",
        "next" to "Next",
        "needs_attention" to "Needs attention"
    )

    private fun pack(vararg p: Pair<String, String>) = mapOf(*p)

    private val packs = mapOf(
        "tr" to pack(
            "today" to "Bugün", "meds" to "İlaçlarım", "follow" to "Circle", "history" to "Geçmiş", "assistant" to "Asistan",
            "today_plan" to "Bugünün ilaç planı", "manage_voice" to "Konuş veya yazarak yönet", "med_times" to "{0} ilaç saati • {1} ilaç", "med_count" to "{0} ilaç",
            "taken" to "İçtim", "snooze" to "+30 dk", "missed" to "İçilmedi", "add_med" to "İlaç ekle", "new_med" to "Yeni ilaç",
            "med_name" to "İlaç adı", "dose_note" to "Doz / not", "add_time" to "+ Saat ekle", "save" to "Kaydet", "cancel" to "Vazgeç", "delete" to "Sil",
            "care_permissions" to "Circle ve yetkiler", "care_help" to "Güvendiğin kişiler doz takibine yardımcı olabilir. İlaç programını yalnız açıkça izin verdiklerin düzenleyebilir.",
            "can_edit" to "İlaç programını düzenleyebilir", "remind" to "Hatırlat", "add_person" to "Kişi ekle", "name" to "Ad", "pair_code" to "Eşleştirme kodu",
            "add" to "Ekle", "my_code" to "Eşleştirme kodum: {0}", "history_empty" to "İçildi, ertelendi, içilmedi ve program değişiklikleri burada görünecek.",
            "ai_title" to "Dosefolk Asistan", "ai_help" to "Doğal şekilde konuş veya yaz. Basit işlemleri hemen yaparım; güvenlik açısından önemli değişiklikleri açıkça onaylatırım.",
            "ai_ready" to "Hazırım. Ne yapmak istediğini söyle.", "ai_example" to "Örn. 20:00 ilaçlarını 30 dakika ertele", "send" to "Gönder", "speak" to "🎙 Konuş", "speech_prompt" to "Dosefolk'a söyle",
            "channel" to "İlaç hatırlatmaları", "all_taken" to "Hepsini içtim", "snooze30" to "30 dk ertele", "notif_missed" to "İçilmedi",
            "event_taken" to "İLAÇLAR İÇİLDİ", "event_missed" to "İLAÇLAR İÇİLMEDİ", "event_snoozed" to "30 DK ERTELENDİ", "event_alarm" to "İLAÇ SAATİ",
            "reminder_title" to "HATIRLATMA", "reminder_body" to "İlaç dozunu kontrol eder misin?", "all_good" to "Her şey yolunda", "next" to "Sonraki", "needs_attention" to "İlgilenmek gerekiyor"
        ),
        "de" to pack("today" to "Heute", "meds" to "Meine Medikamente", "follow" to "Circle", "history" to "Verlauf", "assistant" to "Assistent", "taken" to "Eingenommen", "missed" to "Nicht eingenommen", "add_med" to "Medikament hinzufügen", "save" to "Speichern", "cancel" to "Abbrechen", "delete" to "Löschen", "all_taken" to "Alle eingenommen", "snooze30" to "30 Min. später"),
        "fr" to pack("today" to "Aujourd’hui", "meds" to "Mes médicaments", "follow" to "Circle", "history" to "Historique", "assistant" to "Assistant", "taken" to "Pris", "missed" to "Non pris", "add_med" to "Ajouter un médicament", "save" to "Enregistrer", "cancel" to "Annuler", "delete" to "Supprimer", "all_taken" to "Tout pris", "snooze30" to "Reporter de 30 min"),
        "es" to pack("today" to "Hoy", "meds" to "Mis medicamentos", "follow" to "Circle", "history" to "Historial", "assistant" to "Asistente", "taken" to "Tomado", "missed" to "No tomado", "add_med" to "Añadir medicamento", "save" to "Guardar", "cancel" to "Cancelar", "delete" to "Eliminar", "all_taken" to "Todo tomado", "snooze30" to "Posponer 30 min"),
        "it" to pack("today" to "Oggi", "meds" to "I miei farmaci", "follow" to "Circle", "history" to "Cronologia", "assistant" to "Assistente", "taken" to "Assunto", "missed" to "Non assunto", "save" to "Salva", "cancel" to "Annulla", "delete" to "Elimina"),
        "pt" to pack("today" to "Hoje", "meds" to "Meus medicamentos", "follow" to "Circle", "history" to "Histórico", "assistant" to "Assistente", "taken" to "Tomado", "missed" to "Não tomado", "save" to "Salvar", "cancel" to "Cancelar", "delete" to "Excluir"),
        "nl" to pack("today" to "Vandaag", "meds" to "Mijn medicijnen", "follow" to "Circle", "history" to "Geschiedenis", "assistant" to "Assistent", "taken" to "Ingenomen", "missed" to "Niet ingenomen"),
        "pl" to pack("today" to "Dzisiaj", "meds" to "Moje leki", "follow" to "Circle", "history" to "Historia", "assistant" to "Asystent", "taken" to "Przyjęto", "missed" to "Nie przyjęto"),
        "ru" to pack("today" to "Сегодня", "meds" to "Мои лекарства", "follow" to "Circle", "history" to "История", "assistant" to "Ассистент", "taken" to "Принято", "missed" to "Не принято"),
        "uk" to pack("today" to "Сьогодні", "meds" to "Мої ліки", "follow" to "Circle", "history" to "Історія", "assistant" to "Асистент", "taken" to "Прийнято", "missed" to "Не прийнято"),
        "ar" to pack("today" to "اليوم", "meds" to "أدويتي", "follow" to "الدائرة", "history" to "السجل", "assistant" to "المساعد", "taken" to "تم التناول", "missed" to "لم يتم التناول"),
        "fa" to pack("today" to "امروز", "meds" to "داروهای من", "follow" to "حلقه", "history" to "تاریخچه", "assistant" to "دستیار", "taken" to "مصرف شد", "missed" to "مصرف نشد"),
        "he" to pack("today" to "היום", "meds" to "התרופות שלי", "follow" to "מעגל", "history" to "היסטוריה", "assistant" to "עוזר", "taken" to "נלקח", "missed" to "לא נלקח"),
        "el" to pack("today" to "Σήμερα", "meds" to "Τα φάρμακά μου", "follow" to "Circle", "history" to "Ιστορικό", "assistant" to "Βοηθός", "taken" to "Ελήφθη", "missed" to "Δεν ελήφθη"),
        "ro" to pack("today" to "Astăzi", "meds" to "Medicamentele mele", "follow" to "Circle", "history" to "Istoric", "assistant" to "Asistent", "taken" to "Luat", "missed" to "Neluat"),
        "sv" to pack("today" to "Idag", "meds" to "Mina läkemedel", "follow" to "Circle", "history" to "Historik", "assistant" to "Assistent", "taken" to "Tagen", "missed" to "Inte tagen"),
        "da" to pack("today" to "I dag", "meds" to "Min medicin", "follow" to "Circle", "history" to "Historik", "assistant" to "Assistent", "taken" to "Taget", "missed" to "Ikke taget"),
        "no" to pack("today" to "I dag", "meds" to "Mine medisiner", "follow" to "Circle", "history" to "Historikk", "assistant" to "Assistent", "taken" to "Tatt", "missed" to "Ikke tatt"),
        "fi" to pack("today" to "Tänään", "meds" to "Lääkkeeni", "follow" to "Circle", "history" to "Historia", "assistant" to "Avustaja", "taken" to "Otettu", "missed" to "Ei otettu"),
        "cs" to pack("today" to "Dnes", "meds" to "Moje léky", "follow" to "Circle", "history" to "Historie", "assistant" to "Asistent", "taken" to "Užito", "missed" to "Neužito"),
        "hu" to pack("today" to "Ma", "meds" to "Gyógyszereim", "follow" to "Circle", "history" to "Előzmények", "assistant" to "Asszisztens", "taken" to "Bevéve", "missed" to "Nem vette be"),
        "id" to pack("today" to "Hari ini", "meds" to "Obat saya", "follow" to "Circle", "history" to "Riwayat", "assistant" to "Asisten", "taken" to "Diminum", "missed" to "Belum diminum"),
        "vi" to pack("today" to "Hôm nay", "meds" to "Thuốc của tôi", "follow" to "Circle", "history" to "Lịch sử", "assistant" to "Trợ lý", "taken" to "Đã uống", "missed" to "Chưa uống"),
        "hi" to pack("today" to "आज", "meds" to "मेरी दवाइयाँ", "follow" to "Circle", "history" to "इतिहास", "assistant" to "सहायक", "taken" to "ले लिया", "missed" to "नहीं लिया"),
        "ja" to pack("today" to "今日", "meds" to "服薬", "follow" to "Circle", "history" to "履歴", "assistant" to "アシスタント", "taken" to "服用済み", "missed" to "未服用"),
        "ko" to pack("today" to "오늘", "meds" to "내 약", "follow" to "Circle", "history" to "기록", "assistant" to "도우미", "taken" to "복용함", "missed" to "복용 안 함"),
        "zh" to pack("today" to "今天", "meds" to "我的药物", "follow" to "Circle", "history" to "记录", "assistant" to "助手", "taken" to "已服用", "missed" to "未服用")
    )

    fun language(): String {
        val code = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (packs.containsKey(code)) code else "en"
    }

    fun speechLocale(): String = Locale.getDefault().toLanguageTag()

    fun t(key: String, vararg args: Any): String {
        var value = packs[language()]?.get(key) ?: en[key] ?: key
        args.forEachIndexed { index, arg -> value = value.replace("{$index}", arg.toString()) }
        return value
    }
}
