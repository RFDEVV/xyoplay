# XYOPlay Client 2.6.0

**Mod client-only untuk Minecraft Java 26.1.2 + Fabric.** Pemutaran audio, pencarian, pengunduhan, dan pembaca TikTok LIVE berjalan di perangkat pemain, bukan di server Minecraft.

## Fitur

- GUI modern bertema gelap (aksen biru-ungu, sudut membulat) yang menyesuaikan ukuran jendela: sidebar jadi rail ikon di jendela sempit, semua daftar bisa di-scroll (roda mouse, panah, PageUp/PageDown, scrollbar bisa diseret).
- Tab: **Pustaka**, **Antrean**, **Favorit**, **Riwayat**, **Playlist**, **YouTube**, **TikTok**, **Spotify**, **Link**, **LIVE**, **Setelan**, **Alat**.
- **TikTok**: cari dengan kata kunci lewat API gratis tikwm.com (hasil bisa diurutkan Relevan / Suka / Tayangan), atau `@akun`, `#hashtag`, dan link video lewat yt-dlp. Klik hasil untuk mengunduh audionya langsung (yt-dlp hanya cadangan). API pihak ketiga bisa berubah atau membatasi permintaan (sekitar 1 permintaan/detik); pesan kesalahan tampil di bar bawah. Unduh hanya video yang memang boleh diunduh.
- **Spotify**: cari lagu (judul, artis, atau link `open.spotify.com/track/...`). Tanpa kunci, katalog iTunes dipakai sebagai gantinya. Untuk hasil Spotify, buat aplikasi gratis di developer.spotify.com, salin Client ID dan Client Secret, lalu klik **Tempel** pada kartunya di tab Setelan. Audio Spotify dilindungi DRM dan tidak diunduh dari Spotify: lagu dicocokkan dengan versi YouTube (durasi dan judul terdekat) lalu diunduh sebagai `Artis - Judul.mp3` dengan metadata. Perlu yt-dlp dan FFmpeg.
- Pemutar: play/pause, sebelumnya/berikutnya, stop, **seek dengan klik/seret progress bar**, volume (seret), tombol Acak/Ulang.
- Pustaka: hapus lagu lokal dengan konfirmasi dua klik, tandai favorit, antrekan lagu berikutnya, dan edit judul/artis tanpa mengubah file audio.
- Antrean lagu, playlist lokal, favorit, dan riwayat pemutaran disimpan di `config/xyoplay-library.json`.
- **HUD** kecil: mode Mati / Mini / Kartu, pilih sudut layar, opsi sembunyi otomatis. Atur di tab Setelan.
- Pembuka GUI: tombol **X** (ubah di Options > Controls), atau perintah **/xyoplay**. Tombol X tetap bekerja walau ada mod lain yang memakai tombol yang sama.
- Alat: nyalakan/matikan yt-dlp dan FFmpeg, serta tombol **Periksa** untuk mengecek ffmpeg, ffprobe, yt-dlp, dan python. Path alat bisa diisi manual di `config/xyoplay.properties` (`ffmpegPath`, `ytDlpPath`, `pythonPath`).
- TikTok LIVE: baca komentar, daftar komentar terbaru, TTS offline yang bisa dimatikan.
- Integrasi Mod Menu: tombol Configure membuka tab Pengaturan. Ikon mod diunduh dari favicon RFDEVV saat build (atau taruh `icon.png` sendiri di `src/main/resources/assets/xyoplay/`).

## Instalasi

1. Pasang Minecraft Java **26.1.2**, Java **25**, Fabric Loader yang cocok, dan Fabric API **0.155.3+26.1.2**.
2. Letakkan JAR hasil build di folder `mods` milik instance Minecraft.
3. Pasang Mod Menu versi yang kompatibel bila ingin tombol **Configure** pada daftar Mods.
4. Instal FFmpeg dan pastikan `ffmpeg` serta `ffprobe` bisa dijalankan dari terminal. FFmpeg dipakai untuk decoding dan ekstraksi audio; ffprobe digunakan untuk membaca durasi.
5. Instal yt-dlp dan pastikan `yt-dlp --version` berfungsi.
6. Tekan **X** untuk membuka GUI. Ubah shortcut di Options → Controls → Key Binds → XYOPlay.

Folder musik lokal: `.minecraft/xyoplay/music/` (atau folder game yang dipakai launcher). Letakkan file audio di sana dan tekan **Pindai**.

Di tab **Pustaka**, tombol **Fav** menyimpan favorit, **+** memasukkan lagu ke antrean, **Meta** membuka editor `Judul | Artis`, dan **Hapus** meminta konfirmasi kedua. Setelah lagu selesai (kecuali **Ulang** aktif), antrean diputar sebelum mode acak/urutan biasa. Untuk playlist, buka **Playlist**, buat nama playlist, pilih playlist itu, lalu kembali ke **Pustaka** dan tekan **Pl** pada lagu yang ingin ditambahkan. Riwayat diisi otomatis saat pemutaran dimulai.

### Instalasi alat di Windows

```powershell
py -m pip install -U yt-dlp
py -m pip install TikTokLive pyttsx3
```

Instal FFmpeg untuk Windows dari sumber tepercaya dan tambahkan folder `bin` ke PATH. Setelah mengubah PATH, mulai ulang launcher Minecraft.

### Pembaca TikTok LIVE

- Perlu Python 3.10+ dan paket `TikTokLive` + `pyttsx3`: klik **Instal paket** di tab LIVE (jalankan pip otomatis), lalu **Periksa** di tab Alat untuk memastikan.
- Buka tab **LIVE**, masukkan username akun yang sedang live, lalu klik **Simpan** dan **Mulai LIVE**. Bila streamer belum live, pembaca menunggu dan mengecek ulang tiap 20 detik; koneksi yang putus disambung ulang otomatis. Semua komentar masuk ke daftar dan dibacakan TTS (bila aktif).
- Bila sign server membatasi permintaan, isi **Euler Stream API key** (opsional) di tab Setelan. Log lengkap ada di `config/xyoplay/tiktok.log`. Komentar baru akan muncul di daftar tab LIVE; jika TTS aktif, komentar juga dibacakan lewat suara sistem lokal.
- Reader memerlukan Python serta paket `TikTokLive` dan `pyttsx3`. TTS menggunakan suara sistem secara offline; tidak memerlukan Google API key atau layanan berbayar.
- Klik **Stop LIVE** untuk mematikan koneksi dan TTS reader.
- Konektivitas TikTok Live dapat berubah jika layanan atau API pihak ketiga berubah. Fitur ini opsional dan tidak memengaruhi pemutar musik jika gagal tersambung.
- Gunakan dengan memperhatikan aturan platform, privasi penonton, dan izin yang berlaku.

## Build dari source

Memerlukan JDK 25 dan Gradle 9.4.0:

```bash
gradle --no-daemon clean build
```

JAR akan berada di `build/libs/`. Workflow GitHub Actions membangun proyek dan mengunggah JAR sebagai artifact.

## Batasan dan troubleshooting

- Mod ini client-only. Musik hanya terdengar pada komputer yang menjalankan mod; server Minecraft tidak mengirim audio.
- YouTube search/download memerlukan yt-dlp, FFmpeg, internet, dan konten yang memang boleh diunduh.
- Untuk TikTok, salin link video secara manual ke tab **Link**; mod belum mengambil daftar hasil pencarian/angka suka atau tayangan secara otomatis.
- Durasi/progres mungkin menampilkan `--:--` jika `ffprobe` tidak tersedia atau file tidak dapat dianalisis.
- TikTok LIVE memakai integrasi pihak ketiga yang tidak resmi dan bisa berubah. Jika gagal, cek pesan status di tab TikTok LIVE serta pastikan paket Python terpasang.
- Source ini belum bisa diuji dengan build penuh pada lingkungan penyusunan karena lingkungan tersebut hanya menyediakan Java 21, sedangkan proyek membutuhkan Java 25. Jalankan GitHub Actions sebelum memasang JAR.

## Identitas

- Mod ID: `xyoplay`
- Package: `id.rfdevv.xyoplay.client`
- Versi: `2.6.0-client`
- Target: Minecraft Java 26.1.2, Fabric, Java 25
