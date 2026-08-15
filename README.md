# Jasur Assistant 1.0

Kamoliddin uchun shaxsiy Kotlin Android ovozli AI yordamchi.

## Nimalar ishlaydi

- Foreground microphone service: ilovadan chiqqanda va ekran qulflanganda, xizmat oldindan ilova ichidan ishga tushirilgan bo'lsa, ishlashda davom etadi.
- Wake word: `Jasur` (Sozlamalarda o'zgartiriladi).
- Offline-first speech recognition: Android 12+ qurilmada on-device recognizer mavjud bo'lsa `createOnDeviceSpeechRecognizer()` ishlatiladi.
- Lokal buyruqlar:
  - `Onamga qo'ng'iroq qil` — kontaktni topib CALL_PHONE ruxsati bilan bevosita qo'ng'iroq qiladi.
  - `Telegramni och`, `YouTubeni och` va boshqa launcher ilovalarni nomi bilan ochish.
  - `Fonarni yoq/o'chir`.
  - `Soat nechchi?`.
  - `Telegram xabarlarini o'qi` — Notification Access orqali ushlangan so'nggi Telegram notification matnlarini o'qiydi.
- Online AI fallback:
  - API key, endpoint va model Sozlamalarda.
  - Default endpoint: `https://api.openai.com/v1/responses`
  - Default model: `gpt-5.6`
  - API key Android Keystore AES-GCM bilan shifrlab saqlanadi.
  - `store:false` yuboriladi.
- Incoming caller announcement:
  - Android Call Screening role yoqilganda kiruvchi call handle olinadi.
  - Kontakt bo'lsa: `Sizga <ism> telefon qilyapti`.
  - Kontakt bo'lmasa: raqam raqamlar bo'yicha o'zbekcha o'qiladi.

## Android xavfsizlik cheklovlari

1. Mikrofonli foreground service Android 14+ da ilova ko'rinib turganda foydalanuvchi tugmasi bilan ishga tushirilishi kerak. Reboot'dan keyin mikrofon xizmatini avtomatik boshlash bloklangan.
2. Android `SpeechRecognizer` uzluksiz wake-word engine sifatida tavsiya etilmaydi. Ushbu v1 on-device recognizer'ni restart-loop bilan ishlatadi. Maksimal ishonchlilik uchun keyingi bosqichda maxsus offline wake-word model (masalan, Vosk/Porcupine custom keyword) qo'shilishi kerak.
3. Telegram to'liq chat tarixiga kira olmaydi. Bu loyiha faqat foydalanuvchi alohida bergan Notification Access orqali kelgan notification matnlarini o'qiydi.
4. Caller ID uchun foydalanuvchi Call Screening rolini tasdiqlashi shart. Android yashirilgan/unknown raqamlarni service'ga bermasligi mumkin.
5. API key'ni mobil ilovada saqlash serverdagi secret kabi mutlaq himoya emas. Bu shaxsiy sideload ilova uchun Keystore bilan himoyalangan, ammo juda yuqori xavfsizlik talab qilinsa backend proxy ishlating.

## Android Studio'da APK olish

1. Android Studio'ni oching.
2. `JasurAssistantPro` papkasini Open qiling.
3. Gradle Sync tugashini kuting.
4. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`.
5. APK:
   `app/build/outputs/apk/debug/app-debug.apk`

Release uchun:
`Build > Generate Signed App Bundle or APK > APK` va o'zingizning keystore'ingiz bilan imzolang.

## Birinchi ishga tushirish

1. Ilovani oching.
2. `Mikrofon / Kontakt / Qo'ng'iroq ruxsatlari` tugmasidan ruxsatlarni bering.
3. `Caller ID / Call Screening rolini yoqish` tugmasini bosing va ro'lni tasdiqlang.
4. `Telegram uchun Notification Access` tugmasini bosing va Jasur'ga ruxsat bering.
5. AI kerak bo'lsa API key kiriting va `AI ulanishini tekshirish` tugmasini bosing.
6. `Ishga tushirish` tugmasini bosing.
7. Ekranni o'chirib: `Jasur` → `Eshitaman` → buyruq.

## GitHub Actions orqali APK

`.github/workflows/android.yml` mavjud. Repoga push qilinganda debug APK build bo'ladi va Actions artifact sifatida yuklab olinadi.
