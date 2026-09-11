# Dosefolk APNs Wake Gateway

## Amaç

iOS arka planda uzun ömürlü ntfy stream'ine güvenemez. APNs yalnız uygulamayı uyandıran bir sinyal taşır; Dosefolk/Circle için source of truth self-hosted ntfy ve v9/v2 protokol kayıtlarıdır.

## Güvenlik sınırı

- APNs payload'ına ilaç adı, doz, saat, stock veya Circle event gövdesi koyma.
- Push yalnız `aps.content-available = 1` ve gerekirse opak bir wake nonce taşır.
- iOS uyandığında mevcut `CirclePollSync.pullOnce()` çalışır; publisher-topic, target, replay, revoke ve protocol-version kontrolleri aynen uygulanır.
- Public uygulamaya gateway shared secret gömme.
- APNs `.p8` private key yalnız sunucuda secret/env/credential store içinde tutulur; public GitHub'a girmez.

## APNs gönderim parametreleri

Background wake için provider isteği:

- `apns-push-type: background`
- `apns-priority: 5`
- topic: `com.ozkanmut.dosefolk`
- payload: `{ "aps": { "content-available": 1 } }`

Background push teslimatı garanti değildir. BGAppRefresh reconciliation fallback olarak tutulur.

## Provisioning sözleşmesi

Cihaz APNs token'ı her kayıt/güncelleme callback'inde güncel haliyle gateway'e iletilir; token kalıcı uygulama verisi olarak cache edilmez.

Gateway kaydı en az şunları içerir:

- install kimliği
- APNs device token
- sandbox/production environment
- bundle id
- kullanıcının publisher topic kimliği
- erişmesi gereken Circle publisher topic'leri

Registration yalnız server-provisioned install credential ile kabul edilir. Topic adı tek başına kimlik doğrulama sayılmaz.

## Wake akışı

1. Circle event ntfy'ye publisher topic üzerinden yazılır.
2. Gateway ilgili iOS subscriber install'larını belirler.
3. Gateway her install'a payload içermeyen silent APNs wake gönderir.
4. iOS `DosefolkAppDelegate` wake'i kabul eder.
5. `DosefolkRuntime.backgroundPullOnce()` → `CirclePollSync.pullOnce()` çağrılır.
6. ntfy eventleri normal güvenlik/idempotency zincirinden geçer.

## VDS bileşenleri

Planlanan servis: `/opt/dosefolk-push-gateway`.

Gateway production'a açılmadan önce gerekli env/secrets:

- `APNS_TEAM_ID`
- `APNS_KEY_ID`
- `APNS_KEY_PATH` veya secret mount
- `APNS_BUNDLE_ID=com.ozkanmut.dosefolk`
- install provisioning/verifier credential
- durable registration database bağlantısı

Gerçek APNs credential ve install provisioning olmadan public register/wake endpoint açılmaz.
