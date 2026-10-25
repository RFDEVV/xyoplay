#!/usr/bin/env python3
"""XYOPlay TikTok LIVE comment reader (works with TikTokLive 6.x / 7.x).

Usage: tiktok_reader.py <username> [tts|notts]
Prints one JSON object per line on stdout:
  {"type": "status", "text": "..."}            progress / errors (human readable)
  {"type": "comment", "user": "...", "text": "..."}
  {"type": "missing", "text": "..."}           a Python package is not installed
Environment: XYOPLAY_EULER_KEY (optional Euler Stream API key that raises sign-server rate limits).
The reader keeps retrying (offline streamer, rate limits, blocks) until the Minecraft side stops it.
"""
import asyncio
import json
import os
import queue
import subprocess
import sys
import threading

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def emit(kind, **fields):
    fields["type"] = kind
    try:
        # ensure_ascii keeps the line pure ASCII, so emoji in comments can never crash a cp1252 console.
        sys.stdout.write(json.dumps(fields, ensure_ascii=True) + "\n")
        sys.stdout.flush()
    except Exception:
        pass


def status(text):
    emit("status", text=str(text))


username = (sys.argv[1] if len(sys.argv) > 1 else "").strip().lstrip("@")
tts_enabled = not (len(sys.argv) > 2 and sys.argv[2].strip().lower() == "notts")
api_key = os.environ.get("XYOPLAY_EULER_KEY", "").strip()

if not username:
    status("Masukkan username TikTok terlebih dahulu")
    sys.exit(2)

if sys.version_info < (3, 10):
    status("Python 3.10 atau lebih baru diperlukan (terdeteksi %d.%d)" % sys.version_info[:2])
    sys.exit(5)

# ---------------------------------------------------------------- text to speech

speech_queue = queue.Queue(maxsize=300)


def fallback_speak(text):
    env = dict(os.environ)
    env["XYO_TEXT"] = text  # passed through the environment: never interpolated into a command line
    try:
        if os.name == "nt":
            subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 "Add-Type -AssemblyName System.Speech; "
                 "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Speak($env:XYO_TEXT)"],
                env=env, timeout=60, creationflags=0x08000000)
        elif sys.platform == "darwin":
            subprocess.run(["say", text], timeout=60)
        else:
            for cmd in (["spd-say", "-w", text], ["espeak", text]):
                try:
                    subprocess.run(cmd, timeout=60)
                    return
                except FileNotFoundError:
                    continue
    except Exception:
        pass


def tts_worker():
    engine = None
    try:
        import pyttsx3
        engine = pyttsx3.init()
        engine.setProperty("rate", 175)
    except Exception:
        engine = None
        status("pyttsx3 tidak tersedia, memakai suara sistem bawaan untuk TTS")
    while True:
        text = speech_queue.get()
        if text is None:
            break
        done = False
        if engine is not None:
            try:
                engine.say(text)
                engine.runAndWait()
                done = True
            except Exception:
                engine = None
        if not done:
            fallback_speak(text)


if tts_enabled:
    threading.Thread(target=tts_worker, daemon=True).start()


def clean(text, limit):
    text = "".join(ch if ch.isprintable() else " " for ch in str(text))
    text = " ".join(text.split())
    return text[:limit]


# ------------------------------------------------------------------- connection

async def run_once():
    """One connection attempt. Returns normally when the stream disconnects."""
    try:
        from TikTokLive import TikTokLiveClient
        import TikTokLive.events as events
    except Exception as exc:  # ImportError and friends
        emit("missing", text="Paket TikTokLive belum terpasang (%s). Klik 'Instal paket' di tab LIVE." % exc)
        status("Paket TikTokLive belum terpasang. Klik 'Instal paket' di tab LIVE.")
        sys.exit(3)

    if api_key:
        try:
            from TikTokLive.client.web.web_settings import WebDefaults
            WebDefaults.tiktok_sign_api_key = api_key
        except Exception:
            status("API key Euler tidak bisa dipasang pada versi TikTokLive ini (diabaikan)")

    client = TikTokLiveClient(unique_id="@" + username)

    @client.on(events.ConnectEvent)
    async def on_connect(_event):
        status("Terhubung ke LIVE @%s. Menunggu komentar..." % username)

    @client.on(events.CommentEvent)
    async def on_comment(event):
        user = getattr(event, "user", None)
        nickname = clean(getattr(user, "nickname", "") or getattr(user, "unique_id", "") or "penonton", 40)
        comment = clean(getattr(event, "comment", ""), 300)
        if not comment:
            return
        emit("comment", user=nickname, text=comment)
        if tts_enabled:
            try:
                speech_queue.put_nowait("%s berkata: %s" % (nickname, comment[:200]))
            except queue.Full:
                pass

    @client.on(events.DisconnectEvent)
    async def on_disconnect(_event):
        status("Koneksi LIVE terputus, mencoba menyambung ulang...")

    live_end = getattr(events, "LiveEndEvent", None)
    if live_end is not None:
        @client.on(live_end)
        async def on_end(_event):
            status("LIVE @%s telah berakhir. Menunggu LIVE berikutnya..." % username)

    status("Menyambungkan ke LIVE @%s ..." % username)
    # TikTokLive 6+/7: start() connects and returns the background task; older versions block inside start().
    result = await client.start()
    if result is not None and hasattr(result, "__await__"):
        await result
    else:
        while getattr(client, "connected", False):
            await asyncio.sleep(1)


async def main():
    attempt = 0
    while True:
        attempt += 1
        wait = 5
        try:
            await run_once()
            wait = 5
        except (SystemExit, KeyboardInterrupt, asyncio.CancelledError):
            raise
        except BaseException as exc:  # noqa: BLE001 - we classify by class name so we never depend on import paths
            name = type(exc).__name__
            if name == "UserOfflineError":
                status("@%s belum LIVE. Menunggu dan mengecek ulang tiap 20 detik..." % username)
                wait = 20
            elif name == "UserNotFoundError":
                status("Akun @%s tidak ditemukan atau belum bisa LIVE. Periksa penulisan username." % username)
                sys.exit(6)
            elif name == "AgeRestrictedError":
                status("LIVE ini dibatasi umur dan butuh login TikTok (tidak didukung).")
                sys.exit(7)
            elif name == "SignatureRateLimitError":
                wait = int(getattr(exc, "retry_after", 30) or 30) + 1
                status("Sign server membatasi permintaan. Mencoba lagi dalam %d detik (isi API key Euler di Pengaturan agar lebih longgar)." % wait)
            elif name in ("SignAPIError", "UnexpectedSignatureError", "SignatureMissingTokensError", "PremiumEndpointError"):
                wait = 45
                status("Sign server bermasalah (%s). Mencoba lagi dalam %d detik. Isi API key Euler di Pengaturan bila berulang." % (name, wait))
            elif name in ("WebcastBlocked200Error", "InitialCursorMissingError", "WebsocketURLMissingError"):
                wait = 60
                status("TikTok memblokir koneksi (%s). Mencoba lagi dalam %d detik; coba ganti jaringan/VPN atau perbarui TikTokLive." % (name, wait))
            elif name == "AlreadyConnectedError":
                wait = 5
            else:
                wait = 30
                status("Gagal menyambung (%s: %s). Mencoba lagi dalam %d detik." % (name, clean(exc, 160), wait))
        await asyncio.sleep(wait)


try:
    asyncio.run(main())
except KeyboardInterrupt:
    pass
sys.exit(0)
