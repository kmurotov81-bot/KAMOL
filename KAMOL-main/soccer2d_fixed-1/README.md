# 2v2 Football (LibGDX + Kotlin)

Modullar: `shared` (fizika + protokol), `server` (UDP, headless, ko'p xonali), `android` (client).

## Dizayn
Dizayn maketi: `tools/source/design_mock.png`, ikonka: `tools/source/app_icon.png`.
Sprite'lar `python3 tools/generate_assets.py` bilan yaratiladi (placeholder, kod bilan chizilgan);
ikonkalar va do'kon rasmlari `python3 tools/generate_store_assets.py` bilan. Boshqarish: chapda joystick,
o'ngda PASS (jostick yo'nalishidagi sherikka uzatma) va SHOOT (zarba), yuqorida pauza. PASS/SHOOT ni bosib turing:
qancha uzoq ushlasangiz, zarba/pas shuncha kuchli (qisqa bosish - yengil). Qo'yib yuborilganda amal bajariladi;
nishon chizig'i yo'nalishni va (PASS da) qabul qiluvchi sherikni ko'rsatadi.

## Select Teams
Xonaga kirgach **SELECT TEAMS** oynasi chiqadi: har jamoada 2 o'rin bor. Bo'sh o'rinni bosib o'yinchi (o'rin) tanlang;
band o'rinni olib bo'lmaydi. Keyin START.
Har jamoada ko'pi bilan **2 o'yinchi** (`C.TEAM_SIZE`): jamoa to'lsa, boshqalar uni tanlay olmaydi
(server tekshiradi, "FULL" ko'rinadi). Match hamma START bosgach va ikkala jamoada kamida 1 kishi bo'lganda boshlanadi.
O'yin paytida jamoa almashtirib bo'lmaydi; match tugagach hamma qaytadan tanlaydi.

## O'ynash rejimlari (serversiz ham bo'ladi)
- **PLAY VS BOTS** - offline: server va 3 bot shu telefonda (loopback), 2v2. Internet kerak emas.
- **HOST ON THIS PHONE** - server shu telefonda, do'stlar **bitta Wi-Fi** (yoki hotspot) orqali ulanadi:
  ularning SERVER tugmasiga host ekranida ko'rsatilgan IP'ni kiritib, QUICK MATCH bosish kifoya.
  Host menyuga qaytsa, server to'xtaydi. Host ilovasi fonga o'tsa, Android serverni sekinlashtirishi mumkin.
- Select Teams (har jamoada <= 2 odam, to'lgan jamoani tanlab bo'lmaydi) va START: o'yinchilar kam bo'lsa, bo'sh o'rinlarni
  server botlari to'ldiradi (bot: to'pga eng yaqin - hujumchi, qolgani - himoyachi). Bot UDP client'lari (`--bots=N`) faqat stress test uchun.
- QUICK MATCH / CREATE / JOIN + SERVER - alohida (VPS) serverga ulanish.

## Bot AI (BotBrain.kt) - realistik, muvozanatli
- **Olib yurish:** bot to'pga tegsa uni oldida "ushlab" olib yuradi; rakib (bot yoki odam) tanasiga/to'pga tegsa yoki to'pga
  tashqaridan zarba bo'lsa to'p bo'shaydi.
- **Zarba yoki pas:** zarba bahosi (masofa, darvoza burchagi, to'siqlar). Gol ura olsa o'zi uradi, aks holda eng yaxshi joydagi
  jamoadoshga (bot yoki ODAM) pas beradi; sherigi ancha yaxshi vaziyatda bo'lsa assist. Oldinga (lead) paslar.
- **To'psiz:** jamoada to'p bo'lsa ochiq joyga chiqadi; to'p yo'qolsa eng yaqin bosim qiladi, ikkinchisi darvoza tomonda yopadi.
- **Insoniy xatolar:** reaksiya kechikishi, zarba/pas aniqligi, yomon pas, og'ir teginish, ikkilanish, joylashuv xatosi, bot-botga xarakter farqi.
- Sozlash: `BotBrain.kt` boshidagi konstantalar (`SHOOT_Q`, `PASS_T`) va `topSpeed`, `aimSd`, `passSd`.

## O'yin oqimi
- **Bosh menyu:** faqat MULTIPLAYER va VS BOTS.
- **MULTIPLAYER:** chapda CREATE MATCH (telefon host bo'ladi, lobby ochiladi, START bosilmaguncha o'yin boshlanmaydi,
  host IP'si lobby va pauza oynasida ko'rinadi); o'ngda JOIN BY IP ADDRESS (oldin kiritilgan va Wi-Fi'da topilgan manzillar variant sifatida).
- **START** faqat host (xonani yaratgan) bosadi: bundan keyin yangi o'yinchi kira olmaydi ("MATCH ALREADY STARTED"),
  bo'sh o'rinlarni botlar to'ldiradi. O'yin vaqti (1-15 daqiqa) lobbida host tomonidan o'zgartiriladi.
- **O'yin tugagach:** katta hisob, **PLAY AGAIN** (bosganlar yangi o'yinni boshlaydi, bosmaganlar chiqariladi)
  va **HOME**.

## Ulanish (muammolarni hal qilish)
- **Bir Wi-Fi'dagi telefon:** bir telefonda HOST ON THIS PHONE, boshqasida PLAY > FIND ON WI-FI: IP yozish shart emas.
  Topilmasa: bir xil Wi-Fi'da ekaniga, router "AP/client isolation" o'chiqligiga ishonch hosil qiling (mehmon Wi-Fi'larda ko'pincha yoqiq).
- **IP bilan:** SERVER tugmasida `192.168.1.5` yoki `192.168.1.5:7777` kiriting. Host ekranida IP ko'rsatiladi.
- **Internet orqali:** telefon-host faqat bir Wi-Fi'da ishlaydi (mobil internetda CGNAT bor). Internet uchun VPS server
  (Dockerfile) va uning firewall'ida **UDP 7777** ochiq bo'lishi kerak.
- Ulanish ekrani sababini ko'rsatadi: manzil noto'g'ri / server javob bermayapti / xona topilmadi / to'la / versiya mos emas.
  Hamma telefonda ilovaning bir xil versiyasi bo'lishi shart (`C.PROTOCOL`).
- O'yin paytida aloqa 5 soniya uzilsa, ilova o'zi qayta ulanadi (o'sha o'rin saqlanadi).

## Log (xatolarni qayd etish)
Ilova hamma xatolarni va muhim hodisalarni (ulanish, server, ilova to'xtashi/crash) `log.txt` ga yozadi:
`Android/data/com.soccer.game/files/log.txt` (256 KB dan oshsa `log.old.txt` ga o'tadi).
Menyu > SETTINGS: **SHARE LOG** (Telegram/pochta orqali yuborish), **COPY LOG**, **CLEAR LOG**.
Kodda: `AppLog.e/w/i(tag, msg, throwable)` yoki shared kodda `SLog.e(...)`. Desktop server logni konsolga yozadi.
Native (Box2D/OpenGL) qulashlari Java logiga tushmaydi - ularni `adb logcat` (teg `2v2/...`) ko'rsatadi.

## Lokal ishga tushirish
    ./gradlew :server:run                       # UDP 7777
    ./gradlew :server:run --args="--bots=3"    # + 3 ta test boti
Android Studio'da `android` modulini qurilmaga o'rnating. Emulator uchun IP `10.0.2.2`.
(Gradle wrapper yo'q: Android Studio yaratadi yoki `gradle wrapper --gradle-version 8.11.1`.)

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
