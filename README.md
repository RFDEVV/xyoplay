<div align="center">

# 🎵 XYOPlay Client

**Pemutar musik + toolkit TikTok LIVE untuk Minecraft.**
Gift, follow, dan tap penonton berubah jadi mob, TNT, item, dan efek — lengkap dengan suara TTS dan foto profil di layar.

<p>
  <img src="https://img.shields.io/badge/versi-2.12.1-8B5CF6?style=for-the-badge" alt="versi 2.12.1">
  <img src="https://img.shields.io/badge/Minecraft-Java%2026.1.2-22C55E?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft 26.1.2">
  <img src="https://img.shields.io/badge/Fabric-client--only-38BDF8?style=for-the-badge" alt="Fabric client only">
  <img src="https://img.shields.io/badge/Java-25-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/lisensi-MIT-64748B?style=for-the-badge" alt="MIT">
</p>

[**Mulai cepat**](#-mulai-cepat-5-menit) ·
[**Chaos Mode**](#-chaos-mode--gift-jadi-aksi) ·
[**Instalasi dari nol**](#-instalasi-dari-nol) ·
[**Menu**](#-peta-menu) ·
[**Perintah**](#-perintah-chat) ·
[**Masalah umum**](#-troubleshooting)

</div>

---

## 📑 Daftar isi

- [Apa itu XYOPlay?](#-apa-itu-xyoplay)
- [Mulai cepat (5 menit)](#-mulai-cepat-5-menit)
- [Chaos Mode — gift jadi aksi](#-chaos-mode--gift-jadi-aksi)
- [Suara TTS](#-suara-tts)
- [Bot asisten & mode AFK](#-bot-asisten--mode-afk)
- [Instalasi dari nol](#-instalasi-dari-nol) (Windows · macOS · Linux)
- [Setup TikTok LIVE langkah demi langkah](#-setup-tiktok-live-langkah-demi-langkah)
- [Peta menu](#-peta-menu)
- [Perintah chat](#-perintah-chat)
- [Titik, kompas, dan penanda lokasi mati](#-titik-kompas-dan-penanda-lokasi-mati)
- [File & data](#-file--data)
- [Troubleshooting](#-troubleshooting)
- [FAQ](#-faq)
- [Status pengujian & batasan](#-status-pengujian--batasan)
- [Build dari source](#-build-dari-source)

---

## ✨ Apa itu XYOPlay?

XYOPlay adalah mod **Fabric client-only**. Ia punya dua sisi:

| 🎧 Pemutar musik | 🔴 Toolkit TikTok LIVE |
|---|---|
| Pustaka lokal, antrean, playlist, favorit, riwayat | Baca komentar LIVE dengan TTS gratis (Microsoft Edge dkk) |
| Cari & unduh dari YouTube, TikTok (10 teratas), Spotify, atau link | **Chaos Mode**: gift / follow / tap → mob, TNT, item, potion |
| HUD "sedang diputar" mini atau kartu | Banner event + **foto profil** pengirim di layar |
| Putar otomatis setelah unduh | Filter kata kasar (236 kata bawaan), ban akun |
| | Suara terpisah untuk penonton dan untuk chat Minecraft-mu |

Ditambah alat bantu bermain: titik koordinat, kompas, dan **pin penanda lokasi mati** yang terlihat dari jauh.

---

## 🚀 Mulai cepat (5 menit)

> Sudah pasang Minecraft + Fabric + Fabric API? Lompat ke langkah 2.

1. **Pasang** Java 25, Fabric Loader, Fabric API, lalu taruh `XYOPlay-Client-2.12.1-client.jar` di folder `mods` → [detail](#-instalasi-dari-nol).
2. Masuk ke dunia, tekan **`X`** (atau ketik `/xyoplay`) untuk membuka menu.
3. Tab **Alat → Periksa**. Pasang yang masih merah: Python, FFmpeg, yt-dlp.
4. Tab **LIVE → Instal paket** (memasang `TikTokLive` dan `edge-tts`).
5. Tab **LIVE**: isi username TikTok-mu → **Mulai LIVE**.
6. Tab **Aksi**: nyalakan **Aksi LIVE aktif**, lalu coba tombol uji **Rose**, **Perfume**, **Like**, dan **Mega**.
7. Selesai. Buka TikTok LIVE-mu dan biarkan penonton beraksi. 🎉

> [!TIP]
> Mau mencoba tanpa LIVE? Semua efek bisa diuji lewat tombol **Uji** di tab **Aksi** atau perintah `/xyoplay sim gift Rose` — tidak perlu gift sungguhan.

---

## 💥 Chaos Mode — gift jadi aksi

Tab **Aksi** mengubah event LIVE menjadi kejadian nyata di dunia. Semua aturan bisa diubah tanpa menyentuh source code.

### Hadiah bawaan (special gift)

| Gift | Harga acuan | Hadiah |
|---|---:|---|
| Follow | – | 1 Zombie |
| Rose / Mawar | 1 | 1 Creeper |
| Coffee / Kopi | 1 | 1 TNT |
| Orange Juice / Es Jeruk | 1 | 1 tools acak |
| GG | 1 | 1 item berguna acak |
| You're awesome / Kamu Hebat Banget | 1 | 64 kayu (oak log) |
| Finger Heart / Jari Hati | 5 | **tepat 5** Piglin Brute |
| Padang Rice / Nasi Padang | 5 | 1 Skeleton |
| Rosa | 10 | 1 Iron Golem |
| Perfume / Parfum | 20 | Zombie + Creeper + Skeleton + 4 TNT |
| Doughnut / Donat | 30 | 1 Warden |
| Hat and Mustache / Topi | 99 | **MEGA CHAOS** (Warden, 2 Ravager, 3 Creeper, 5 Skeleton, 5 Zombie, 2 Witch, 2 Piglin Brute, potion buruk, dengan hitung mundur) |
| **Tiap 200 tap per orang** | – | **Event acak** (`acak:chaos`), nama penonton tampil besar di layar |
| **Target follow tercapai** | – | Boss + bahaya ×3 + suara perayaan (`goal=`) |

> Harga hanyalah acuan desain tabel ini dan bisa berubah di TikTok. Harga yang dipakai mesin selalu harga **nyata dari event LIVE**.

### Gift lain: tier berdasarkan koin

Gift yang tidak punya aturan khusus dihitung dari harga satu gift:

| Koin | Tier | Hadiah bawaan |
|---:|---|---|
| 1–4 | Light Chaos | 1 efek ringan acak (bantuan atau gangguan) |
| 5–9 | Small Combo | **TNT sebanyak koin** (7 koin = 7 TNT) |
| 10–19 | Dangerous Challenge | 4 mob berbahaya acak |
| 20–29 | Combo Attack | **TNT sebanyak koin** (20 koin = 20 TNT) |
| 30–49 | Boss Challenge | 1 boss acak + 3 mob berbahaya |
| 50–98 | Extreme Chaos | 2 boss + 5 mob berbahaya + 8 TNT |
| 99 | Mega Chaos | event Mega |
| 100+ | Special Event | 3 gelombang + hadiah bantuan |
| harga tak diketahui | – | efek ringan acak (aturan `gift=`) |

**Urutan prioritas:** id gift → nama gift → tier koin → `gift=`. Aturan khusus selalu mengalahkan tier; keduanya tidak pernah jalan bersamaan.

### Fitur seru untuk penonton

| Fitur | Cara kerja |
|---|---|
| **Tap tap per orang** | Tiap penonton mencapai kelipatan 200 tap → satu event acak untuknya (nama + foto di layar). Angkanya diatur di tab Aksi. |
| **Judul besar** | Gift ≥ 10 koin, tap event, dan target follow memunculkan nama penonton besar di tengah layar (matikan di Setelan). |
| **Panel LIVE** | Top 3 gifter dan bar target follow di sisi kanan layar. |
| **Target follower** | Bar di layar memakai **jumlah follower asli** akunmu (dibaca dari profil TikTok saat LIVE mulai, diperbarui tiap menit, +1 tiap follow baru), bukan hanya follow sesi ini. Target = kelipatan berikutnya (bawaan 1000, mis. 873/1000 atau 5230/6000). Tercapai → banner, TTS, dan aturan `goal=`. Atur kelipatan di Setelan. Tanda `*` = angka asli belum terbaca (memakai follow sesi). |
| **Undian** | Penonton komentar `!ikut` (kata bisa diganti). Tab **Undian** → **Undi!** memilih pemenang; satu orang hanya menang sekali per ronde. |
| **Frasa cepat** | Tab **Frasa**: klik kalimat, dibacakan TTS dengan suara chat sendiri. |
| **Efek suara otomatis** | Follow, share, gift (kecil / besar / mega), tap event, target follow, dan pemenang undian masing-masing punya suara. 20 suara bawaan bergaya meme (air horn, ka-ching, boing, sad trombone, fanfare, boom) dibuat sendiri, bebas hak cipta. Atur di tab **Efek**. |
| **Sound meme sendiri** | Taruh file `wav` / `mp3` / `ogg` ke `config/xyoplay/sounds/<jenis>/` (mis. `sounds/follow/`). Beberapa file = dipilih acak. MP3/OGG dikonversi sekali dengan FFmpeg. Gunakan sound yang kamu berhak pakai. |
| **Token suara Minecraft** | Di aturan hadiah: `sound:levelup` `peringatan` `ledakan` `petir` `tepuk` `lonceng` `hore` `hantu` `boss`. |
| **Token judul** | `title:Halo {nama}` menampilkan judul layar; `{nama}` diganti nama penonton. Contoh: `Rose=creeper+title:Mawar dari {nama}+sound:tepuk`. |

### Cara aturan bekerja

Tulis `PEMICU=HADIAH` di kolom tab **Aksi**, lalu tekan **Tambah** (menimpa aturan yang sama). Tombol **Ubah** di tiap baris mengisi kolom untukmu.

| Pemicu | Arti |
|---|---|
| `follow` `share` `subscribe` `join` | event LIVE |
| `like` | tiap N tap **per orang** (N diatur di tab Aksi, bawaan 200) |
| `goal` | target follow tercapai (kelipatan diatur di Setelan) |
| `Rose`, `mawar`, `Finger Heart`… | gift berdasarkan nama atau julukan Indonesia |
| `id:5655` | gift berdasarkan ID TikTok (lihat tab **Gift**) |
| `diamond:20` | gift senilai ≥ 20 koin per buah (tier) |
| `gift` | gift yang harganya tidak diketahui |
| `!boom` | komentar penonton yang diawali `!boom` |

| Hadiah | Contoh |
|---|---|
| mob | `zombie`, `creeper*3`, `piglin_brute*5`, `warden` |
| gabungan `+` | `zombie+creeper+skeleton+tnt*4` |
| TNT = koin gift | `tnt*c` |
| item | `give:oak_log*64`, `give:diamond*3` |
| item/mob acak | `acak:tools`, `acak:berguna`, `acak:bahaya*4`, `acak:boss`, `acak:ringan`, `acak:potion`, `acak:chaos` |
| efek potion | `fx:speed`, `fx:slowness*2` (`*N` = level) |
| gelombang `\|` | `acak:bahaya*5\|acak:boss*2\|warden` |
| banner | `say:Awas ada boss!` |
| acara siap pakai | `mega`, `special` |

Contoh: `Rose=creeper*2`, `!kayu=give:oak_log*64`, `diamond:10=acak:bahaya*5`, `like=acak:chaos`.

Tombol di tab Aksi: **Paket XYOMC** (memulihkan semua aturan bawaan), **Contoh !command**, **Kosongkan antrean**, dan **Uji koin 1 / 5 / 10 / 20 / 30 / 50 / 99 / 100**.

### Keamanan dan kestabilan

- **Combo gift** diproses per potong: gift ×3 = 3× hadiah. Batas per event bisa diatur; jika dibatasi, **banner dan Monitor memberi tahu** (tidak diam-diam).
- Tidak ada event ganda: id event TikTok dicatat; follow/share ganda dalam 3 detik diabaikan.
- Entity muncul **satu per satu** (jeda bisa diatur), bukan semua di satu tick, dengan **batas entity per 30 detik**; sisanya menunggu.
- Event besar (Mega/Special) diantrekan satu per satu, maksimal 6 menunggu.
- **TNT mode aman** (bawaan): suara + partikel ledakan tanpa merusak blok. Matikan untuk TNT asli yang menyala dan merusak blok.
- Nama penonton dibersihkan sebelum masuk ke command.
- Tab **Monitor** menampilkan tiap event: berhasil, gagal (beserta alasan dari game), atau ditolak.
- `/give` yang tidak muat di inventori menjatuhkan item di dekat pemain.

### Tampilan di layar

Setiap follow, share, dan gift muncul sebagai **banner dengan foto profil** pengirim (bulat, diunduh sekali di latar belakang). Warna banner mengikuti tier. Bisa dimatikan di **Setelan**.

---

## 🔊 Suara TTS

- 44 suara gratis: Microsoft Edge neural (butuh `edge-tts`), StreamElements, Google, dan suara sistem.
- Streaming langsung: suara mulai berbunyi sekitar 0,2 detik setelah teks siap.
- **Dua profil suara terpisah** di tab **Suara**: *Penonton LIVE* dan *Chat sendiri*. Masing-masing punya suara, volume, kecepatan, dan nada sendiri.
- **Chat jadi suara**: ketik apa pun di chat Minecraft dan langsung dibacakan (opsional tidak dikirim ke server).
- Komentar basi (>12 detik) dibuang agar tidak ketinggalan; command `!` diumumkan lebih dulu.
- Filter kata kasar: 236 kata bawaan, bisa tambah/hapus satu per satu, ban akun dari daftar komentar.

---

## 🤖 Bot asisten & mode AFK

Tab **Bot** (dan perintah `/xyoplay bot …`) memanggil 1–5 asisten yang bermain seperti pemain biasa.

| Kemampuan | Cara kerja |
|---|---|
| **Ikut ke mana saja** | Mencari jalan sendiri (A*) di sekitarmu: melompat blok, turun maksimal 3 blok, menghindari lava, pohon, dan tembok. |
| **Naik gunung / keluar gua** | Menaruh blok (pilar & jembatan), atau menggali tangga bila boleh. Kalau tetap tersangkut 2 kali, bot dipindah ke titik aman di dekatmu — tidak pernah macet selamanya. |
| **Gali mengikutimu** | Menembus tanah dan batu alami; tidak menyentuh blok buatan (peti, kaca, pintu…) dan tidak membuka dinding di sebelah air/lava. |
| **Tebang pohon, tambang, cari balok** | Mode *Tambang*: kayu, batu, tanah, besi, berlian. |
| **Buat & upgrade alat sendiri** | Kayu → batu → besi → berlian (pickaxe, pedang, kapak) dan armor besi, dari bahan di tas bot. Meja kerja dan tungku dianggap tersedia, jadi bijih besi langsung jadi batangan. |
| **Buatkan untukmu** | `/xyoplay bot buat pickaxe besi` — hasilnya langsung masuk inventorimu. Bahan kurang = bot mencarinya dulu. |
| **Bentuk pemain** | Bot tampil sebagai pemain biasa (entitas *mannequin*) dengan 9 skin bawaan (Steve, Alex, Kai, Noor, Zuri, Sunny, Ari, Efe, Makena) atau skin akun Minecraft apa pun (ketik nama di kolom atas tab Bot lalu **Skin**; butuh internet). Pakaian hias: Petualang, Ninja, Ksatria, Raja. Bisa diganti jadi Zombie/Skeleton/Piglin/Villager/Iron Golem. Kalau game menolak bentuk pemain, bot otomatis memakai Zombie. |
| **Survival** | Bot bisa terluka (monster di dekatnya melukai), pulih pelan, dan **mati**: bahannya hilang separuh lalu muncul lagi 8 detik kemudian. Matikan di tab Bot bila ingin bot kebal. |
| **Bangun base sederhana** | Rumah 5×5, tinggi 3, pintu 1×2, meja kerja, tungku, peti, kasur, obor (butuh 72 blok di tas bot). |
| **Lawan mob** | Menyerang monster di dekatmu (creeper, zombie, skeleton, dll.). |
| **Ringan** | Pencarian jalan dibatasi per tick (Hemat / Normal / Kencang); bot yang diam tidak mengirim apa pun. |

**Perintah:** `/xyoplay bot aktif|mati|panggil|ikut|diam|otomatis|base|jumlah <1-5>|tambang <kayu|batu|tanah|besi|berlian>|buat <alat> [bahan]|tas|status`

### Mode AFK (autopilot)

`/xyoplay afk` (atau tab **Bot → AFK autopilot**): kamu cukup diam, karaktermu sendiri bermain — menebang pohon, menambang, membuat dan meng-upgrade alat, membangun base, dan melawan mob.

- **Mati** → tombol respawn ditekan otomatis → berjalan **kembali ke titik mati**. Kalau mati 3× di area yang sama dalam 5 menit, titik mati dilewati (area berbahaya).
- **Malam** → pulang ke base dan menunggu (bila sudah punya base).
- Rasa lapar diisi otomatis tiap 1 menit (bisa dimatikan).
- HUD kecil "AFK AUTOPILOT" menampilkan status. Matikan kapan saja dengan `/xyoplay afk off`.

> **Cara kerjanya:** bot adalah entitas tanpa AI. Di dunia sendiri bot dan karaktermu (saat AFK) dipindahkan langsung lewat server bawaan (bukan command `/tp`, jadi tidak ada spam chat dan tidak bentrok dengan mod lain); blok dan serangan memakai command senyap (`setblock`, `damage`). Bot yang hilang atau tidak sinkron dimunculkan ulang otomatis. Karena itu: di **dunia sendiri** semuanya langsung jalan; di **server** kamu perlu OP, dan server dengan anti-cheat bisa menolaknya. Tas bot bersifat virtual (alat buatan bot diberikan ke inventorimu lewat `/give`).

## 🧰 Instalasi dari nol

### Kebutuhan

| Komponen | Versi |
|---|---|
| Minecraft Java Edition | 26.1.2 |
| Java | 25 |
| Fabric Loader | ≥ 0.18.4 |
| Fabric API | 0.155.3+26.1.2 |
| Python (untuk LIVE & TTS) | 3.10 atau lebih baru |
| FFmpeg (`ffmpeg`, `ffplay`, `ffprobe`) | terbaru |
| yt-dlp (untuk unduh musik) | terbaru |

Mod ini **client-only**: tidak perlu dipasang di server.

### 🪟 Windows

1. **Java 25** — pasang JDK 25 (mis. [Adoptium](https://adoptium.net/)). Cek di PowerShell: `java -version`.
2. **Fabric** — unduh [Fabric Installer](https://fabricmc.net/use/installer/), pilih Minecraft `26.1.2`, pasang.
3. **Fabric API** — unduh dari [Modrinth](https://modrinth.com/mod/fabric-api) (versi `0.155.3+26.1.2`).
4. **XYOPlay** — taruh Fabric API dan `XYOPlay-Client-2.12.1-client.jar` di `%appdata%\.minecraft\mods`.
5. **Python** — pasang dari [python.org](https://www.python.org/downloads/) dan **centang "Add python.exe to PATH"**. Cek: `py --version`.
6. **FFmpeg** — `winget install Gyan.FFmpeg` (atau unduh dan tambahkan ke PATH). Cek: `ffmpeg -version`.
7. **yt-dlp** — `winget install yt-dlp.yt-dlp` atau `py -m pip install -U yt-dlp`.
8. **Paket LIVE & TTS** — cara mudah: tab **LIVE → Instal paket**. Cara manual:
   ```powershell
   py -m pip install --upgrade pip
   py -m pip install TikTokLive edge-tts pyttsx3
   ```
9. Jalankan Minecraft dengan profil Fabric → tekan `X` → tab **Alat → Periksa**.

### 🍎 macOS

```bash
brew install openjdk@25 python ffmpeg yt-dlp
python3 -m pip install --upgrade pip
python3 -m pip install TikTokLive edge-tts
```

Lalu pasang Fabric Installer (`.jar`) dan taruh mod di `~/Library/Application Support/minecraft/mods`.

### 🐧 Linux

```bash
# Debian / Ubuntu (sesuaikan paket Java 25 dengan distromu)
sudo apt install python3 python3-pip ffmpeg
python3 -m pip install --user --break-system-packages TikTokLive edge-tts yt-dlp
```

Taruh mod di `~/.minecraft/mods`.

### Cek hasil pemasangan

1. Buka menu (`X`).
2. Tab **Alat → Periksa**: Python, FFmpeg, ffprobe, yt-dlp harus hijau, plus baris `edge-tts`.
3. Tab **Suara → Tes suara** harus mengeluarkan suara.

> [!NOTE]
> Jika `X` bentrok dengan mod lain, ubah di *Options → Controls → Key Binds → XYOPlay*.

---

## 🔴 Setup TikTok LIVE langkah demi langkah

1. Pastikan akun TikTok-mu **sedang LIVE** (atau akan LIVE sebentar lagi).
2. Menu → tab **LIVE** → isi **username** (tanpa `@` juga boleh) → **Simpan**.
3. Klik **Mulai LIVE**. Status berubah menjadi *AKTIF*. Kalau pembaca terputus, ia mencoba tersambung ulang sendiri.
4. Tab **Aksi** → nyalakan **Aksi LIVE aktif** (kartu LIVE menampilkan `Aksi ON/OFF`).
5. Tab **Suara** → pilih suara penonton, volume, dan kecepatan; **Tes suara**.
6. Biarkan penonton berinteraksi. Pantau tab **Monitor** dan **Statistik**.

### Singleplayer vs server

| Situasi | Yang terjadi |
|---|---|
| Dunia sendiri (singleplayer) | Efek dijalankan lewat server bawaan tanpa perlu cheat. Hasil tiap command (berhasil/gagal) dicatat di Monitor. |
| Server multiplayer | Command dikirim seperti ketikan pemain, jadi akunmu harus **OP**. Hasilnya tidak bisa dibaca dari client, jadi Monitor hanya mencatat "terkirim". |

### Euler Stream key (opsional)

Jika koneksi LIVE sering ditolak, isi **Euler Stream API key** di tab **Setelan**.

---

## 🗺️ Peta menu

| Tab | Fungsi |
|---|---|
| **Pustaka** | Musik lokal, cari, ubah metadata |
| **YouTube / TikTok / Spotify / Link** | Cari dan unduh; tombol *Putar #1* memutar hasil teratas |
| **LIVE** | Username, Mulai/Stop, daftar komentar (dengan tombol Ban), Instal paket |
| **Suara** | Suara, volume, kecepatan, nada; target *Penonton* atau *Chat sendiri* |
| **Aksi** | Aturan Chaos, pengaturan batas, uji gift & koin, paket bawaan |
| **Gift** | Katalog gift: nama, ID, harga dari LIVE, hadiah tiap gift |
| **Undian** | Penonton komentar `!ikut` untuk ikut, tombol **Undi!** memilih pemenang (banner + judul besar + TTS) |
| **Frasa** | Kalimat siap pakai: sekali klik dibacakan TTS; bisa tambah kalimat sendiri |
| **Bot** | Bot & AFK: jumlah, perintah, tambang, buat alat, pengaturan, autopilot AFK |
| **Efek** | Nyalakan/matikan efek suara, volume, uji tiap suara, lokasi folder sound meme |
| **Monitor** | Log tiap event dan hasilnya |
| **Filter** | Kata kasar (tambah, hapus, muat bawaan) |
| **Ban** | Akun diblokir |
| **Statistik** | Pesan, follow, gift, like, peringkat pengirim gift |
| **Titik** | Simpan lokasi, pandu, teleport (butuh cheat), hapus |
| **Setelan** | HUD, TTS, banner, foto profil, pin penanda, info koordinat, panel LIVE, judul besar, target follow |
| **Alat** | Pemeriksa Python/FFmpeg/yt-dlp |
| **Antrean / Favorit / Riwayat / Playlist** | Manajemen musik |

---

## ⌨️ Perintah chat

| Perintah | Fungsi |
|---|---|
| `/xyoplay` | Buka menu |
| `/xyoplay say <teks>` | Bacakan teks dengan suara chat sendiri |
| `/xyoplay tts on\|off\|stop` | Chat jadi suara |
| `/xyoplay spawn <mob> [jumlah]` | Uji memunculkan mob |
| `/xyoplay sim follow\|share` | Simulasi follow / share |
| `/xyoplay sim gift <nama>` | Simulasi gift (pakai harga yang diketahui) |
| `/xyoplay sim koin <harga>` | Simulasi gift unik berharga N koin (uji tier) |
| `/xyoplay sim komentar <teks>` | Simulasi komentar (mis. `!zombie`) |
| `/xyoplay ban <akun>` · `unban <akun>` | Blokir / buka blokir |
| `/xyoplay titik <nama>` | Simpan lokasi sekarang |

---

## 📍 Titik, kompas, dan penanda lokasi mati

- **Kartu koordinat** (Setelan → *Info koordinat di layar*): X/Y/Z berwarna, kompas, dan — saat ada target — nama, jarak, serta **panah yang berputar** ke arah target. Sudutnya bisa dipilih.
- **Pin penanda**: titik yang dipandu (termasuk lokasi mati) digambar seperti pin peta di layar, dengan nama dan jarak. Di luar layar, pin menempel di tepi dengan panah.
- **Hilang otomatis** saat kamu sampai (≤ 5 blok); titik "Mati" ikut dihapus. Matikan di Setelan → *Hapus penanda saat sampai*, atau matikan pin sama sekali di *Penanda peta*.

---

## 🗂️ File & data

```text
.minecraft/
├── mods/XYOPlay-Client-2.12.1-client.jar
└── config/
    ├── xyoplay.properties            # pengaturan
    └── xyoplay/
        ├── live-actions.json         # aturan Chaos (cadangan otomatis: live-actions.old.json)
        ├── live-filter.json          # kata kasar & akun diblokir
        ├── gift-catalog.json         # gift yang pernah terlihat (nama, id, harga)
        ├── waypoints.json            # titik
        └── tiktok.log                # log pembaca LIVE
```

Jika `live-actions.json` rusak, mod memulihkan aturan bawaan dan menyimpan salinan rusaknya sebagai `live-actions.rusak.json`.

---

## 🧯 Troubleshooting

| Gejala | Penyebab & solusi |
|---|---|
| Gift masuk tapi tidak ada efek | Cek **Aksi LIVE aktif** menyala, kamu **berada di dunia**, dan lihat tab **Monitor** — alasan kegagalan tertulis di sana. |
| Efek gagal di server | Akunmu harus **OP**. Di dunia sendiri tidak perlu. |
| TNT tidak merusak blok | Itu **mode aman** (bawaan). Matikan *TNT mode aman* di tab Aksi untuk TNT asli. |
| Terputus: `Failed to decode packet chat_command` / dikick spam | Server menolak command lebih dari 256 karakter dan membatasi jumlah command per detik. Sejak v2.12.1 command yang terlalu panjang tidak dikirim (tercatat di Monitor) dan semua command ke server dikirim pelan-pelan (bawaan 2/detik; atur di Setelan → *Batas command di server*). Bot bergerak mulus hanya di dunia sendiri. |
| Jumlah mob lebih sedikit dari yang diharapkan | Lihat catatan "dibatasi" di Monitor: batas gift per event, batas TNT, atau batas entity per 30 detik. |
| Gift tidak dikenali namanya | Lihat tab **Gift**/Statistik untuk nama persis dan ID, lalu tulis aturan `id:NNNN=…`. |
| Suara delay | Pasang `edge-tts` (tab LIVE → Instal paket) dan nyalakan Chat jadi suara sebelum bicara; helper suara dipanaskan saat masuk dunia. Pastikan FFmpeg/`ffplay` ada. |
| Suara pecah / pelan | Naikkan volume di tab **Suara** (sampai 300%), turunkan kecepatan. |
| Foto profil hanya huruf | Foto diunduh di latar; muncul beberapa detik kemudian. Pastikan FFmpeg terpasang. |
| `X` tidak membuka menu | Bentrok keybind; ketik `/xyoplay` atau ubah di Controls. |
| "Python tidak ditemukan" | Pasang Python 3.10+ dan pastikan ada di PATH, lalu tab **Alat → Periksa**. |
| Komentar kasar tetap terbaca | Tab **Filter** → *Filter kata kasar* ON; tambahkan kata yang kurang. |

Log LIVE: `config/xyoplay/tiktok.log`. Log game: `logs/latest.log` (cari `[XYOPlay]`).

---

## ❓ FAQ

**Apakah ini bisa dipakai di server orang lain?**
Efek butuh OP. Musik dan TTS berjalan di client dan aman dipakai di mana saja.

**Apakah harga gift diambil dari internet?**
Tidak ditebak. Mod mencatat nama, ID, dan harga dari **event LIVE nyata** (disimpan untuk offline). Harga acuan di README hanyalah titik awal.

**Kenapa banner tidak memakai emoji?**
Font Minecraft tidak punya emoji; banner memakai teks dan warna.

**Bisa menambah gift/hadiah baru?**
Bisa: tulis aturan di tab **Aksi**, atau pilih gift di tab **Gift** → **Atur**.

**Bagaimana mengembalikan aturan bawaan?**
Tab **Aksi → Paket XYOMC** (mengganti semua aturan).

---

## 🧪 Status pengujian & batasan

Yang **sudah diuji** (di sandbox, dengan stub Minecraft + Java 21 karena Java 25 tidak tersedia di sana):

- 110 tes logika Chaos dan efek (termasuk undian, target follow, efek suara): seluruh gift khusus, tier koin (5→5 TNT, 20→20 TNT), Finger Heart tepat 5 Piglin Brute, Perfume combo, Mega, combo ×N, batas TNT/entity, anti-duplikat, cooldown command, nama aman, kegagalan tercatat, event like.
- 1500+ render GUI dan 1190+ klik tombol di 4 ukuran layar tanpa error.
- 39 skenario navigasi bot di dunia sintetis: bukit, tembok 6 blok (pilar), gunung (gali), lubang 12 blok (pilar / gali / pindah aman), jurang 9 blok (jembatan), hutan, danau lava, server lag, pemain yang mendaki, melawan mob, naik alat dari nol sampai pickaxe besi, membangun base. 15 tes AFK: mati → respawn → kembali ke titik mati, 3 bot mengikuti dengan CPU sangat kecil.
- Reader Python: pembersihan teks, filter, streaming TTS, protokol event, konversi foto profil.

Yang **belum bisa diuji di sini**: build penuh dengan Gradle/Loom di JDK 25 dan uji langsung di Minecraft 26.1.2 + TikTok LIVE nyata. Karena API Minecraft 26.1.2 sangat baru, bagian yang paling mungkin perlu penyesuaian bila build gagal adalah `ClientSendMessageEvents.ALLOW_CHAT` (chat jadi suara) di `XYOPlayClient.java`.

Batasan yang diketahui:

- Katalog gift tidak diambil dari situs katalog eksternal (halamannya tidak dapat diakses dari lingkungan pengembangan); harga datang dari event LIVE.
- Pilihan "hitung berdasarkan total transaksi streak" belum ada; efek dihitung per potong gift.
- TNT asli tidak punya mode "tanpa kerusakan blok"; tersedia mode aman (efek saja) atau TNT asli.
- Di server multiplayer, keberhasilan command tidak bisa diverifikasi.
- Bot/AFK membaca dunia lewat reflection pada API Minecraft; bila ada nama method yang berbeda di 26.1.2, tab Bot menampilkan alasannya dan bot berhenti (game tidak crash). Perilaku di dunia nyata (gerak, animasi, lag server) belum diuji di Minecraft sungguhan.
- Bot memakai entitas tanpa AI (zombie/skeleton/piglin/villager/iron golem) sehingga tidak berjalan dengan animasi kaki; posisinya dipindahkan tiap 2 tick.
- Bot tidak menjelajah jauh ke bawah tanah untuk mencari bijih; ia mengambil bijih dalam ±12 blok (opsi deteksi tersembunyi) dan berjalan mencari bahan baru bila tidak ada.

---

## 🏗️ Build dari source

Butuh **JDK 25**.

```bash
# Linux / macOS
./gradlew --no-daemon clean build

# Windows
.\gradlew.bat --no-daemon clean build
```

JAR ada di `build/libs/` (gunakan yang tanpa `-sources`).

| Properti | Nilai |
|---|---|
| Mod ID | `xyoplay` |
| Versi | `2.12.1-client` |
| Package | `id.rfdevv.xyoplay.client` |
| Loader | Fabric (client-only) |
| Pembaca LIVE | Python helper `assets/xyoplay/tools/tiktok_reader.py` |

---

## 🛡️ Privasi & penggunaan bertanggung jawab

Komentar LIVE diproses lokal di komputermu; hanya teks yang dibacakan yang dikirim ke penyedia TTS yang kamu pilih (Edge/StreamElements/Google). Unduh musik hanya untuk konten yang kamu berhak mengunduhnya. Patuhi aturan TikTok, YouTube, Spotify, dan server Minecraft yang kamu pakai.

## 🧑‍💻 Developer & lisensi

Dibuat oleh **RFDEVV** · Lisensi MIT (lihat `LICENSE`).

<div align="center">
<sub>Play your world. Enjoy your music. Let your chat break it. 😈</sub>
</div>
