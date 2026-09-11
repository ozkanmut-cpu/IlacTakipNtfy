# Dosefolk iOS TODO

Amaç: Android Dosefolk ile aynı Circle protokolünü kullanan, native Swift/SwiftUI tabanlı, Android ↔ iPhone ↔ iPhone birlikte çalışabilir iOS sürümü oluşturmak.

## Milestone 1 — Temel iOS iskeleti ve ortak protokol

- [ ] `ios/Dosefolk/` altında Swift + SwiftUI Xcode projesini oluştur.
- [ ] Minimum iOS sürümünü belirle (hedef: modern API'ler için iOS 17; cihaz gereksinimine göre düşürülebilir).
- [ ] Bundle ID ve signing yapısını hazırla.
- [ ] Android uygulamasındaki platformdan bağımsız protokolü `PROTOCOL_SPEC.md` olarak belgele.
- [ ] Medication, DoseEvent, CirclePeer, MedicationMeta, ProgramRule, StockState ve SyncEnvelope modellerinin Swift karşılıklarını oluştur.
- [ ] JSON alan adlarını Android ile birebir uyumlu tut.
- [ ] Android fixture → iOS decode ve iOS fixture → Android decode compatibility testlerini oluştur.
- [ ] Lokal veri katmanını kur (SwiftData/Core Data veya uygun SQLite katmanı).
- [ ] Küçük uygulama ayarlarını UserDefaults'ta; gizli credential'ları Keychain'de sakla.

## Milestone 2 — Self-hosted ntfy ve Circle

- [ ] `https://ntfy.field-maintenance-prod.com` için Swift ntfy client oluştur.
- [ ] URLSession POST publisher desteği ekle.
- [ ] Stream/poll subscriber desteği ekle.
- [ ] Timeout/reconnect/backoff davranışını ekle.
- [ ] HTTP 429 ve Retry-After handling ekle.
- [ ] Topic değiştiğinde subscription reconnect uygula.
- [ ] Publisher-topic modelini Android ile birebir uygula.
- [ ] `envelope.topic == actorTopic` doğrulamasını uygula.
- [ ] `targetTopic` filtresini tüm side-effect'lerden önce uygula.
- [ ] Replay protection uygula.
- [ ] Revoked peer / re-pair fencing uygula.
- [ ] Unsupported future protocol version'ları güvenli şekilde skip et.
- [ ] Circle presence handshake'i uygula.
- [ ] QR-first pairing akışını oluştur.
- [ ] Android QR → iPhone ve iPhone QR → Android pairing testlerini yap.
- [ ] Revoke/re-pair senaryolarını test et.

## Milestone 3 — İlaç motoru ve bildirimler

- [x] Dose state engine'i Swift'e taşı.
- [x] Pending / Taken / Snoozed / Missed / correction davranışlarını Android ile eşleştir.
- [x] Event idempotency uygula; aynı event ikinci kez doz/stock değiştirmemeli.
- [x] Local notification altyapısını `UNUserNotificationCenter` ile kur.
- [x] Notification action olarak Taken / Snooze / Missed ekle.
- [x] Kullanıcının uygulamayı açmadan aksiyon alabilmesini sağla.
- [x] İlaç programlarını önceden schedule et.
- [x] Program değişikliğinde notification reconciliation yap.
- [x] Timezone değişiminde programları yeniden hesapla.
- [x] Snooze için yeni local notification schedule et.
- [x] Stock azaltma ve stock sync mantığını taşı.
- [x] Undo/correction akışını taşı.

## Milestone 4 — Native iOS UX

- [ ] Today ekranını SwiftUI ile oluştur.
- [ ] Normal durumda sıfır etkileşim / gerektiğinde tek belirgin aksiyon ilkesini koru.
- [ ] İlaç ekleme/düzenleme ekranını progressive disclosure ile oluştur.
- [ ] PRN kullanımını ihtiyaç olana kadar gizli tut.
- [ ] Circle ekranını oluştur; QR pairing öncelikli olsun.
- [ ] Raw topic/teknik protokol bilgilerini normal kullanıcıdan gizle.
- [ ] Stock ekranını oluştur.
- [ ] History ekranını oluştur.
- [ ] Teknik Circle/protocol eventlerini kullanıcı geçmişinden filtrele.
- [ ] History eventlerini tarihe göre grupla ve insan-okunur isimler kullan.
- [ ] Assistant ekranını Android'deki sade UX yaklaşımıyla oluştur.
- [ ] Light/dark mode'u sistem temasına bağla.
- [ ] Dynamic Type ve temel accessibility kontrollerini tamamla.

## Milestone 5 — Gerçek zamanlı background mimarisi

- [ ] iOS background kısıtlarını ntfy stream'den bağımsız ele al.
- [ ] APNs push wake-up mimarisini tasarla.
- [ ] VDS üzerinde iOS push gateway/provisioning servisini oluştur.
- [ ] Cihaz APNs token registration akışını oluştur.
- [ ] Circle event geldiğinde ilgili iOS cihazını APNs ile uyandır.
- [ ] ntfy'yi event/protocol source of truth olarak koru.
- [ ] BackgroundTasks ile reconciliation/fallback mekanizmasını uygula.
- [ ] Uygulama force-quit, ekran kilitli ve uzun süre background senaryolarını fiziksel cihazda test et.

## Milestone 6 — ntfy production security

- [ ] Anonymous/open ntfy kullanımını kaldıracak credential mimarisini oluştur.
- [ ] Install başına runtime-provisioned credential/token üret.
- [ ] iOS credential'ını Keychain'de sakla.
- [ ] Android credential'ını Android Keystore ile güvenli sakla.
- [ ] Public GitHub reposuna hiçbir static secret/token koyma.
- [ ] Server-side provisioning/bootstrap oluştur.
- [ ] ntfy `auth-file`/uygun auth yapısını etkinleştir.
- [ ] `auth-default-access: deny-all` geçişini Android + iOS auth hazır olduğunda aynı rollout içinde yap.
- [ ] Topic bazlı minimum yetki/ACL modelini uygula.
- [ ] 429/backoff korumasını self-hosted sistemde de koru.

## Milestone 7 — QA ve observability

- [ ] Android QA log formatıyla uyumlu iOS QA logger oluştur.
- [ ] Her log satırına timestamp, session, app version/build, iOS version ve cihaz modeli ekle.
- [ ] NTFY_TX / NTFY_RX / SYNC / PAIR / REVOKE / ALARM / ACTION / ERROR / SECURITY_REJECT kategorilerini uygula.
- [ ] Topic/token/secret değerlerini mask/hash et.
- [ ] İlaç adı, doz, notification body veya protokol payload'ını QA loguna yazma.
- [ ] QA logunu yalnız uygulamaya ait özel dizinde tut.
- [ ] Güvenli Share Sheet ile QA log export ekle.
- [ ] Android ve iOS loglarını event ID üzerinden korele edilebilir hale getir.

## Milestone 8 — Cross-platform E2E

- [ ] Android → Android regression testini koru.
- [ ] Android → iPhone E2E testi yap.
- [ ] iPhone → Android E2E testi yap.
- [ ] iPhone → iPhone E2E testi yap.
- [ ] Taken event senkronizasyonunu test et.
- [ ] Snooze event senkronizasyonunu test et.
- [ ] Missed event senkronizasyonunu test et.
- [ ] Stock sync test et.
- [ ] Program/rule sync test et.
- [ ] Revoke/re-pair test et.
- [ ] Replay ve duplicate event test et.
- [ ] Wrong target ve publisher-topic mismatch security rejection testlerini yap.
- [ ] Offline → online reconciliation test et.

## Milestone 9 — CI/CD

- [ ] GitHub Actions macOS iOS workflow oluştur.
- [ ] Swift unit tests çalıştır.
- [ ] Protocol compatibility tests çalıştır.
- [ ] Xcode simulator build/test çalıştır.
- [ ] iOS build/archive validation ekle.
- [ ] Android veya iOS protocol değişikliğinde cross-platform compatibility testini zorunlu gate yap.
- [ ] CI artifact üretimini doğrula.

## Milestone 10 — Apple/TestFlight/App Store

- [ ] Apple Developer hesabı/App Store Connect hazırlığını tamamla.
- [ ] App ID oluştur.
- [ ] Signing certificate ve provisioning profile oluştur.
- [ ] APNs key/capability yapılandır.
- [ ] Notification capability ekle.
- [ ] Gerekiyorsa Background Modes capability ekle.
- [ ] QR kamera izni ve açıklamasını ekle.
- [ ] Privacy manifest'i hazırla.
- [ ] App Store privacy disclosure bilgilerini hazırla.
- [ ] Circle üzerinden hangi verilerin gönderildiğini açıkça belgele.
- [ ] İlk TestFlight build'ini çıkar.
- [ ] En az iki fiziksel cihazla final QA yap.
- [ ] App Store review build'ini hazırla.

## Sonraki faz — Opsiyonel

- [ ] HealthKit entegrasyonu değerlendir/ekle.
- [ ] Apple Watch companion app tasarla.
- [ ] Watch üzerinden Taken / Snooze aksiyonlarını destekle.
- [ ] Android için FCM wake-up ekleyerek APNs/FCM ortak push gateway'e geç.

## Definition of Done

iOS sürümü tamamlanmış sayılmadan önce:

- Android ve iOS aynı Dosefolk/Circle protokolünü kullanmalı.
- Android ↔ iPhone pairing ve çift yönlü event sync fiziksel cihazlarda çalışmalı.
- Taken/Snooze/Missed/Stock/Program/Revoke/Re-pair senaryoları deterministic olmalı.
- Duplicate/replay/wrong-target/publisher-topic güvenlik kontrolleri iki platformda aynı davranmalı.
- Background notification/sync davranışı gerçek iPhone'da doğrulanmalı.
- Hiçbir production secret public repoda bulunmamalı.
- iOS unit/compatibility testleri ve build CI'da green olmalı.
- TestFlight build gerçek cihaz QA'sından geçmiş olmalı.

## Tahmini çalışma planı

Mevcut Android mimarisi ve protokolü referans alınarak hedef: yaklaşık **1–2 yoğun geliştirme gününde çalışan iOS beta**, ardından fiziksel iPhone/APNs/TestFlight QA ve production hardening.
