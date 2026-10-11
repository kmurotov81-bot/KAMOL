# 2v2 Football (LibGDX + Kotlin)

Modullar: `shared` (fizika + protokol), `server` (UDP, headless, ko'p xonali), `android` (client).

## Dizayn
Dizayn maketi: `tools/source/design_mock.png`, ikonka: `tools/source/app_icon.png`.
Sprite'lar `python3 tools/generate_assets.py` bilan yaratiladi (placeholder, kod bilan chizilgan);
ikonkalar va do'kon rasmlari `python3 tools/generate_store_assets.py` bilan. Boshqarish: chapda joystick,
o'ngda PASS (yaqin sherikka/jostick yo'nalishiga uzatma) va SHOOT (kuchli zarba), yuqorida pauza.

## Select Teams
Xonaga kirgach **SELECT TEAMS** oynasi chiqadi: TEAM 1 (ko'k) yoki TEAM 2 (qizil) ni tanlang, keyin START.
Har jamoada ko'pi bilan **2 o'yinchi** (`C.TEAM_SIZE`): jamoa to'lsa, boshqalar uni tanlay olmaydi
(server tekshiradi, "FULL" ko'rinadi). Match hamma START bosgach va ikkala jamoada kamida 1 kishi bo'lganda boshlanadi.
O'yin paytida jamoa almashtirib bo'lmaydi; match tugagach hamma qaytadan tanlaydi.

## O'ynash rejimlari (serversiz ham bo'ladi)
- **PLAY VS BOTS** - offline: server va 3 bot shu telefonda (loopback), 2v2. Internet kerak emas.
- **HOST ON THIS PHONE** - server shu telefonda, do'stlar **bitta Wi-Fi** (yoki hotspot) orqali ulanadi:
  ularning SERVER tugmasiga host ekranida ko'rsatilgan IP'ni kiritib, QUICK MATCH bosish kifoya.
  Host menyuga qaytsa, server to'xtaydi. Host ilovasi fonga o'tsa, Android serverni sekinlashtirishi mumkin.
- QUICK MATCH / CREATE / JOIN + SERVER - alohida (VPS) serverga ulanish.

## Lokal ishga tushirish
    ./gradlew :server:run                       # UDP 7777
    ./gradlew :server:run --args="--bots=3"    # + 3 ta test boti
Android Studio'da `android` modulini qurilmaga o'rnating. Emulator uchun IP `10.0.2.2`.
(Gradle wrapper yo'q: Android Studio yaratadi yoki `gradle wrapper --gradle-version 8.5`.)

## 7.1 Tarmoq stress testi (qurilmasiz, server tomonida)
    ./gradlew :server:run --args="--loss=5 --latency=100 --jitter=30"
`--latency` har yo'nalishga (RTT ~ 2x). Tekshirish ro'yxati:
- 5% loss + 100ms + 30ms jitter: rubber-banding bo'lmasligi, o'z harakatingiz darhol seziladi
- 15% loss: o'yin davom etishi, hisob/state to'g'ri yangilanishi
- 300ms: raqiblar silliq, to'p sakramasligi (INTERP_MS ni sozlang, `Constants.kt`)
- Wi-Fi o'chirib-yoqish va ilovani fonga o'tkazib qaytish: o'sha o'rnga qaytishi (15s ichida)
Server logida "violation" chiqsa, simulyatsiya anti-cheat'ni noto'g'ri ishga tushirayotgan bo'lishi mumkin.

## 7.2 Qurilmada test (qo'lda)
Past quvvatli telefon (Snapdragon 4xx/6xx): FPS > 55, RAM < 150MB, batareya < 10%/soat.
Android Studio Profiler (CPU/Memory) va `adb shell dumpsys batterystats` ishlating.

## 7.4 Build pipeline
`.github/workflows/ci.yml`: server build + Android debug APK + release AAB.
GitHub Secrets: `KEYSTORE_B64` (base64 -w0 release.jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
`PLAY_SERVICE_ACCOUNT_JSON` (ixtiyoriy). `v*` tag push qilsangiz AAB Play Console Internal testing'ga yuklanadi.
versionCode = CI run raqami. Release'da R8 yoqilgan: **release buildni qurilmada sinab ko'ring.**

## Serverni joylash
    docker build -t soccer-server .
    docker run -d --restart=always -p 7777:7777/udp soccer-server
UDP 7777 portini firewall'da oching. Android client'dagi SERVER tugmasida IP/domen kiriting.

## 7.5 Do'kon materiallari
    python3 tools/generate_store_assets.py
`store/icon-512.png`, `store/feature-1024x500.png`, mipmap ikonkalar (placeholder).
Screenshot'larni qurilmadan oling. `store/PRIVACY_POLICY.md` - shablon, joylashtirib URL bering.
