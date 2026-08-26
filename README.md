# Multi Auto Clicker — Build & Usage Guide

## Project Location
```
C:\Users\fayaa\.gemini\antigravity-ide\scratch\multi-auto-clicker\
```

---

## Cara Buka di Android Studio

1. Open **Android Studio**
2. File → **Open** → pilih folder `multi-auto-clicker`
3. Tunggu Gradle sync selesai
4. Pastikan `local.properties` sudah punya path SDK yang benar (auto-generated)

---

## Build & Install

```bash
# Dari terminal di folder project
.\gradlew assembleDebug
.\gradlew installDebug
```

Atau klik **Run ▶** di Android Studio langsung.

---

## Setup Setelah Install (WAJIB)

Ada **2 permission wajib grant manual**:

### 1. Overlay Permission
- Tap **"Grant Overlay Permission"** → aktifkan di Settings

### 2. Accessibility Service
- Tap **"Enable Accessibility Service"** → cari **"Multi Auto Clicker"** → aktifkan

---

## Cara Pakai

1. Tap **"▶ Launch Auto Clicker"** — floating panel muncul
2. Tap **"+ Add"** untuk tambah titik klik (bubble bisa di-drag ke mana aja)
3. Atur **Loop**: ∞ Infinite atau Fixed Count
4. Atur **Delay** (ms) antar klik
5. Tap **"▶ START"**

| Aksi | Cara |
|------|------|
| Hide panel | Tap **—** di header |
| Tampilkan kembali | Tap icon ⚡ kecil |
| Stop | Tap **■ STOP** |
| Hapus titik | Tap **✕** pada bubble |
| Notifikasi selesai | Toggle di ⚙ atau di MainActivity |

> **Default: Silent** — tidak ada notifikasi saat selesai, kecuali diaktifkan.

> **Test di device fisik** — emulator tidak support Accessibility gesture.

---

## Struktur File

```
multi-auto-clicker/app/src/main/
├── java/com/fayaa/autoclicker/
│   ├── MainActivity.kt
│   ├── model/ClickPoint.kt
│   ├── model/MacroConfig.kt
│   ├── service/AutoClickService.kt   ← simulasi tap
│   ├── service/OverlayService.kt     ← floating UI + macro loop
│   └── utils/PrefsManager.kt
└── res/
    ├── layout/ (5 XML layouts)
    ├── values/ (colors, strings, themes)
    └── xml/accessibility_service_config.xml
```
