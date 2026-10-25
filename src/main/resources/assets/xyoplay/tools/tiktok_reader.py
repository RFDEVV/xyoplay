#!/usr/bin/env python3
"""XYOPlay TikTok LIVE comment, follow, and gift reader with configurable TTS.
Supports:
  - Google TTS (Suara Perempuan Google) & Sistem offline (pyttsx3 / Windows SAPI)
  - Configurable TTS Volume (0-100)
  - Configurable Language (id, en, jv, su, ja, ko)
  - Reading all comments & commands
  - Thanking followers ("Terima kasih <nama> sudah mem-follow")
  - Thanking gifters ("Terima kasih <nama> telah mengirim <gift>")
  - Dynamic live configuration via stdin
"""
import asyncio
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def emit(kind, **fields):
    fields["type"] = kind
    try:
        sys.stdout.write(json.dumps(fields, ensure_ascii=True) + "\n")
        sys.stdout.flush()
    except Exception:
        pass


def status(text):
    emit("status", text=str(text))


# CLI arguments & environment fallbacks
username = (sys.argv[1] if len(sys.argv) > 1 else "").strip().lstrip("@")
tts_enabled = not (len(sys.argv) > 2 and sys.argv[2].strip().lower() == "notts")

def_vol = 90
if len(sys.argv) > 3:
    try:
        def_vol = int(sys.argv[3].strip())
    except Exception:
        pass
elif os.environ.get("XYOPLAY_TTS_VOL"):
    try:
        def_vol = int(os.environ.get("XYOPLAY_TTS_VOL"))
    except Exception:
        pass

def_voice = (sys.argv[4] if len(sys.argv) > 4 else os.environ.get("XYOPLAY_TTS_VOICE", "google")).strip().lower()
def_lang = (sys.argv[5] if len(sys.argv) > 5 else os.environ.get("XYOPLAY_TTS_LANG", "id")).strip().lower()

config = {
    "tts": tts_enabled,
    "volume": max(0, min(100, def_vol)),
    "voice": "google" if "google" in def_voice else "system",
    "lang": def_lang if def_lang in ("id", "en", "jv", "su", "ja", "ko") else "id"
}

api_key = os.environ.get("XYOPLAY_EULER_KEY", "").strip()

if not username:
    status("Masukkan username TikTok terlebih dahulu")
    sys.exit(2)

if sys.version_info < (3, 10):
    status("Python 3.10 atau lebih baru diperlukan (terdeteksi %d.%d)" % sys.version_info[:2])
    sys.exit(5)

# ---------------------------------------------------------------- text to speech

speech_queue = queue.Queue(maxsize=1000)
ffplay_path = shutil.which("ffplay")


def clean(text, limit=300):
    text = "".join(ch if ch.isprintable() else " " for ch in str(text or ""))
    text = " ".join(text.split())
    return text[:limit]


def clean_for_speech(text, limit=200):
    # Remove emojis and excessive repetition for smoother speech
    text = str(text or "")
    text = re.sub(r'https?://\S+', '', text)
    text = "".join(ch if (ch.isalnum() or ch in " .,!?'\"-~_/") else " " for ch in text)
    text = re.sub(r'([!?.,])\1+', r'\1', text)
    text = " ".join(text.split())
    return text[:limit].strip()


def play_with_ffplay(mp3_path, volume):
    if not ffplay_path:
        return False
    try:
        subprocess.run(
            [ffplay_path, "-nodisp", "-autoexit", "-loglevel", "quiet", "-volume", str(volume), mp3_path],
            timeout=30,
            creationflags=0x08000000 if os.name == "nt" else 0,
            check=True
        )
        return True
    except Exception:
        return False


def play_with_powershell_media(mp3_path, volume):
    if os.name != "nt":
        return False
    try:
        vol_float = max(0.0, min(1.0, volume / 100.0))
        uri_path = mp3_path.replace("\\", "/")
        ps_cmd = (
            f"Add-Type -AssemblyName presentationCore; "
            f"$p = New-Object System.Windows.Media.MediaPlayer; "
            f"$p.Open([System.Uri]'file:///{uri_path}'); "
            f"$p.Volume = {vol_float}; "
            f"$p.Play(); "
            f"Start-Sleep -Milliseconds 500; "
            f"while ($p.NaturalDuration.HasTimeSpan -and ($p.Position -lt $p.NaturalDuration.TimeSpan)) {{ Start-Sleep -Milliseconds 150 }}"
        )
        subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_cmd],
            timeout=30,
            creationflags=0x08000000
        )
        return True
    except Exception:
        return False


def speak_google(text, lang, volume):
    clean_txt = clean_for_speech(text, 180)
    if not clean_txt:
        return True
    q = urllib.parse.quote(clean_txt)
    url = f"https://translate.google.com/translate_tts?ie=UTF-8&tl={lang}&client=tw-ob&q={q}"
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Referer": "https://translate.google.com/"
    })
    tmp_path = None
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            audio_bytes = resp.read()
        if not audio_bytes:
            return False
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
            f.write(audio_bytes)
            tmp_path = f.name
        
        # Try ffplay first
        if play_with_ffplay(tmp_path, volume):
            return True
        # Try powershell MediaPlayer
        if play_with_powershell_media(tmp_path, volume):
            return True
        return False
    except Exception:
        return False
    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass


def speak_system_pyttsx3(engine, text, volume):
    clean_txt = clean_for_speech(text, 250)
    if not clean_txt:
        return True
    if engine is None:
        return False
    try:
        engine.setProperty("volume", max(0.05, min(1.0, volume / 100.0)))
        engine.say(clean_txt)
        engine.runAndWait()
        return True
    except Exception:
        return False


def speak_system_fallback(text, volume):
    clean_txt = clean_for_speech(text, 250)
    if not clean_txt:
        return
    env = dict(os.environ)
    env["XYO_TEXT"] = clean_txt
    env["XYO_VOL"] = str(max(1, min(100, volume)))
    try:
        if os.name == "nt":
            subprocess.run(
                ["powershell", "-NoProfile", "-Command",
                 "Add-Type -AssemblyName System.Speech; "
                 "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                 "$s.Volume = [int]$env:XYO_VOL; "
                 "$s.Speak($env:XYO_TEXT)"],
                env=env, timeout=40, creationflags=0x08000000)
        elif sys.platform == "darwin":
            subprocess.run(["say", clean_txt], timeout=40)
        else:
            for cmd in (["spd-say", "-w", clean_txt], ["espeak", clean_txt]):
                try:
                    subprocess.run(cmd, timeout=40)
                    return
                except FileNotFoundError:
                    continue
    except Exception:
        pass


def tts_worker():
    pyttsx3_engine = None
    try:
        import pyttsx3
        pyttsx3_engine = pyttsx3.init()
        pyttsx3_engine.setProperty("rate", 175)
    except Exception:
        pyttsx3_engine = None

    while True:
        item = speech_queue.get()
        if item is None:
            break
        text = str(item).strip()
        if not text or not config.get("tts", True):
            continue

        vol = config.get("volume", 90)
        voice_mode = config.get("voice", "google")
        lang = config.get("lang", "id")

        played = False
        if voice_mode == "google":
            # Attempt Google TTS online
            played = speak_google(text, lang, vol)
            if not played and pyttsx3_engine is not None:
                # Fallback to pyttsx3
                played = speak_system_pyttsx3(pyttsx3_engine, text, vol)
            if not played:
                speak_system_fallback(text, vol)
        else:
            # System offline mode
            if pyttsx3_engine is not None:
                played = speak_system_pyttsx3(pyttsx3_engine, text, vol)
            if not played:
                speak_system_fallback(text, vol)


threading.Thread(target=tts_worker, daemon=True).start()


def stdin_listener():
    """Listens for dynamic configuration from XYOPlay Java client."""
    for raw in sys.stdin:
        line = raw.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
            cmd = msg.get("cmd")
            if cmd == "config":
                if "volume" in msg:
                    config["volume"] = max(0, min(100, int(msg["volume"])))
                if "voice" in msg:
                    config["voice"] = "google" if "google" in str(msg["voice"]).lower() else "system"
                if "lang" in msg:
                    config["lang"] = str(msg["lang"]).lower()
                if "tts" in msg:
                    config["tts"] = bool(msg["tts"])
            elif cmd == "clear":
                while not speech_queue.empty():
                    try:
                        speech_queue.get_nowait()
                    except Exception:
                        break
        except Exception:
            pass


threading.Thread(target=stdin_listener, daemon=True).start()


# ------------------------------------------------------------------- connection

async def run_once():
    """One connection attempt. Returns normally when the stream disconnects."""
    try:
        from TikTokLive import TikTokLiveClient
        import TikTokLive.events as events
    except Exception as exc:
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
        status("Terhubung ke LIVE @%s. Menunggu komentar, follow, & gift..." % username)

    @client.on(events.CommentEvent)
    async def on_comment(event):
        try:
            user = getattr(event, "user", None)
            nickname = clean(getattr(user, "nickname", "") or getattr(user, "unique_id", "") or "Penonton", 40)
            comment = clean(getattr(event, "comment", ""), 300)
            if not comment:
                return
            emit("comment", user=nickname, text=comment)
            if config.get("tts", True):
                try:
                    # Clean command format
                    if comment.startswith("!") or comment.startswith("/"):
                        spoken = f"{nickname} command: {comment}"
                    else:
                        spoken = f"{nickname} berkata: {comment}"
                    speech_queue.put_nowait(spoken)
                except queue.Full:
                    pass
        except Exception:
            pass

    # Follow Event handler
    follow_event_cls = getattr(events, "FollowEvent", None)
    if follow_event_cls is not None:
        @client.on(follow_event_cls)
        async def on_follow(event):
            try:
                user = getattr(event, "user", None)
                nickname = clean(getattr(user, "nickname", "") or getattr(user, "unique_id", "") or "Seseorang", 40)
                emit("follow", user=nickname, text="[FOLLOW] Sudah mem-follow!")
                if config.get("tts", True):
                    try:
                        speech_queue.put_nowait(f"Terima kasih {nickname} sudah mem-follow")
                    except queue.Full:
                        pass
            except Exception:
                pass

    # Gift Event handler
    gift_event_cls = getattr(events, "GiftEvent", None)
    if gift_event_cls is not None:
        @client.on(gift_event_cls)
        async def on_gift(event):
            try:
                user = getattr(event, "user", None)
                nickname = clean(getattr(user, "nickname", "") or getattr(user, "unique_id", "") or "Seseorang", 40)
                gift = getattr(event, "gift", None)
                gift_name = clean(getattr(gift, "name", "") or getattr(gift, "describe", "") or "hadiah", 40)
                
                repeat_count = int(getattr(event, "repeat_count", 1) or getattr(event, "combo_count", 1) or 1)
                repeat_end = int(getattr(event, "repeat_end", 1) or 1)
                streaking = bool(getattr(event, "streaking", False))

                # If streaking, announce only at the end of combo or single gift
                if streaking and repeat_end == 0:
                    return

                count_desc = f"{repeat_count}x {gift_name}" if repeat_count > 1 else gift_name
                emit("gift", user=nickname, gift=gift_name, count=repeat_count, text=f"[GIFT] Mengirim {count_desc}")

                if config.get("tts", True):
                    try:
                        speech_queue.put_nowait(f"Terima kasih {nickname} telah mengirim {count_desc}")
                    except queue.Full:
                        pass
            except Exception:
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
    result = await client.start()
    if result is not None and hasattr(result, "__await__"):
        await result
    else:
        while getattr(client, "connected", False):
            await asyncio.sleep(1)


async def main():
    while True:
        wait = 5
        try:
            await run_once()
            wait = 5
        except (SystemExit, KeyboardInterrupt, asyncio.CancelledError):
            raise
        except BaseException as exc:
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
