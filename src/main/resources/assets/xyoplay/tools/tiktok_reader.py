#!/usr/bin/env python3
"""XYOPlay TikTok LIVE reader + TTS engine.

Modes
  tiktok_reader.py <username> [tts|notts] [volume] [voice] [lang]   -> connect to a TikTok LIVE and read it
  tiktok_reader.py __tts__                                            -> TTS only (used for "chat in Minecraft -> voice")

Protocol
  stdout: one JSON object per line  {"type": "status|comment|follow|gift|share|join|subscribe|spoke|missing", ...}
  stdin : one JSON object per line  {"cmd": "config|speak|set_badwords|set_banned|clear|quit", ...}

TTS pipeline: text cleanup -> provider (Edge neural / StreamElements / Google / system) -> ffmpeg (loudness + gain +
limiter) -> player.  The next sentence is synthesised while the current one is playing, so there are no gaps.
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
import unicodedata
import urllib.parse
import urllib.request
import wave

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

_emit_lock = threading.Lock()


def emit(etype, **fields):
    fields["type"] = etype
    try:
        with _emit_lock:
            sys.stdout.write(json.dumps(fields, ensure_ascii=True) + "\n")
            sys.stdout.flush()
    except Exception:
        pass


def status(text):
    emit("status", text=str(text))


# ------------------------------------------------------------------ configuration

TTS_ONLY = len(sys.argv) > 1 and sys.argv[1].strip() == "__tts__"
username = "" if TTS_ONLY else (sys.argv[1] if len(sys.argv) > 1 else "").strip().lstrip("@")
DRYRUN = os.environ.get("XYOPLAY_DRYRUN", "") == "1"
api_key = os.environ.get("XYOPLAY_EULER_KEY", "").strip()


def _int(value, default, lo, hi):
    try:
        return max(lo, min(hi, int(float(value))))
    except Exception:
        return default


LEGACY_VOICES = {
    "edge_gadis": "edge:id-ID-GadisNeural",
    "edge_ardi": "edge:id-ID-ArdiNeural",
    "edge_siti": "edge:jv-ID-SitiNeural",
    "edge_ava": "edge:en-US-AvaNeural",
}
LANG_FALLBACK_EDGE = {
    "id": "id-ID-GadisNeural", "en": "en-US-AvaNeural", "jv": "jv-ID-SitiNeural", "su": "su-ID-TutiNeural",
    "ja": "ja-JP-NanamiNeural", "ko": "ko-KR-SunHiNeural", "ms": "ms-MY-YasminNeural", "zh": "zh-CN-XiaoxiaoNeural",
}

config = {
    "tts": True,
    "volume": 100,          # 10..300 (percent)
    "voice": "edge:id-ID-GadisNeural",
    "lang": "id",
    "rate": 0,              # -50..+50 (percent speed offset)
    "pitch": 0,             # -50..+50 (Hz, Edge only)
    "read_names": True,
    "read_follow": True,
    "read_gift": True,
    "read_share": True,
    "read_join": False,
    "max_chars": 160,
    "skip_badword_comments": False,   # True: do not read a comment at all when it contains a badword
    "filter_on": True,
    "emit_join": False,
    "self_voice": "",       # voice for text typed in the Minecraft chat (empty = same as viewers)
    "self_volume": 100,
    "self_rate": 0,
    "self_pitch": 0,
    "cmd_words": [],        # viewer commands that trigger something in the game, e.g. "!zombie"
}
badwords = set()
banned_users = set()


def apply_config(msg):
    if "tts" in msg:
        config["tts"] = bool(msg["tts"])
    if "volume" in msg:
        config["volume"] = _int(msg["volume"], config["volume"], 10, 300)
    if "voice" in msg:
        v = str(msg["voice"]).strip()
        config["voice"] = LEGACY_VOICES.get(v.lower(), v) if v else config["voice"]
    if "lang" in msg:
        config["lang"] = str(msg["lang"]).strip().lower() or "id"
    if "rate" in msg:
        config["rate"] = _int(msg["rate"], 0, -50, 50)
    if "pitch" in msg:
        config["pitch"] = _int(msg["pitch"], 0, -50, 50)
    if "self_voice" in msg:
        v = str(msg["self_voice"]).strip()
        config["self_voice"] = LEGACY_VOICES.get(v.lower(), v)
    if "self_volume" in msg:
        config["self_volume"] = _int(msg["self_volume"], 100, 10, 300)
    if "self_rate" in msg:
        config["self_rate"] = _int(msg["self_rate"], 0, -50, 50)
    if "self_pitch" in msg:
        config["self_pitch"] = _int(msg["self_pitch"], 0, -50, 50)
    if "cmd_words" in msg:
        config["cmd_words"] = [str(w).strip().lower() for w in (msg["cmd_words"] or []) if str(w).strip()]
    if "max_chars" in msg:
        config["max_chars"] = _int(msg["max_chars"], 160, 40, 300)
    for key in ("read_names", "read_follow", "read_gift", "read_share", "read_join",
                "skip_badword_comments", "filter_on", "emit_join"):
        if key in msg:
            config[key] = bool(msg[key])


def set_badwords(words):
    badwords.clear()
    for w in words or []:
        w = " ".join(str(w).strip().lower().split())
        if w:
            badwords.add(w)
    _rebuild_badword_index()


def set_banned(users):
    banned_users.clear()
    for u in users or []:
        u = str(u).strip().lower().lstrip("@")
        if u:
            banned_users.add(u)


# CLI arguments (older Java builds pass these), then the JSON config from the environment.
if not TTS_ONLY:
    config["tts"] = not (len(sys.argv) > 2 and sys.argv[2].strip().lower() == "notts")
    if len(sys.argv) > 3:
        config["volume"] = _int(sys.argv[3], 100, 10, 300)
    if len(sys.argv) > 4 and sys.argv[4].strip():
        config["voice"] = LEGACY_VOICES.get(sys.argv[4].strip().lower(), sys.argv[4].strip())
    if len(sys.argv) > 5 and sys.argv[5].strip():
        config["lang"] = sys.argv[5].strip().lower()
try:
    _env_cfg = json.loads(os.environ.get("XYOPLAY_CFG", "") or "{}")
except Exception:
    _env_cfg = {}
apply_config(_env_cfg)

if not TTS_ONLY and not username:
    status("Masukkan username TikTok terlebih dahulu")
    sys.exit(2)

if sys.version_info < (3, 10):
    status("Python 3.10 atau lebih baru diperlukan (terdeteksi %d.%d)" % sys.version_info[:2])
    sys.exit(5)

NO_WINDOW = 0x08000000 if os.name == "nt" else 0

# ------------------------------------------------------------------ text cleanup

SLANG_MAP = {
    "yg": "yang", "dgn": "dengan", "dg": "dengan", "sy": "saya", "km": "kamu", "kmu": "kamu", "lg": "lagi",
    "udh": "sudah", "udah": "sudah", "sdh": "sudah", "blm": "belum", "bgt": "banget", "bngt": "banget",
    "klo": "kalau", "kl": "kalau", "klu": "kalau", "gk": "nggak", "ga": "nggak", "gak": "nggak",
    "tdk": "tidak", "bs": "bisa", "mksh": "terima kasih", "makasih": "terima kasih", "tq": "terima kasih",
    "thx": "makasih", "pls": "tolong", "plis": "tolong", "gmn": "bagaimana", "gimana": "bagaimana",
    "knp": "kenapa", "tp": "tapi", "sm": "sama", "org": "orang", "trs": "terus", "brp": "berapa",
    "dmn": "dimana", "skrg": "sekarang", "bntr": "sebentar", "gt": "gitu", "bgtu": "begitu", "jd": "jadi",
    "gw": "gue", "gue": "gue", "lo": "lu", "emg": "emang", "emng": "emang", "tau": "tahu", "udh": "sudah",
    "lgi": "lagi", "dr": "dari", "utk": "untuk", "untk": "untuk", "krn": "karena", "karna": "karena",
    "jgn": "jangan", "jng": "jangan", "hrs": "harus", "sbnrnya": "sebenarnya", "tlg": "tolong",
    "slm": "salam", "asw": "assalamualaikum", "ass": "assalamualaikum", "gpp": "nggak apa apa",
    "gapapa": "nggak apa apa", "mantul": "mantap betul", "mantab": "mantap", "kuy": "ayo", "yuk": "yuk",
    "btw": "ngomong ngomong", "dm": "pesan pribadi", "fyp": "efyipi", "ootd": "otd", "wa": "wa",
    "sob": "sob", "bro": "bro", "sis": "sis", "min": "admin", "kak": "kak", "bang": "bang",
    "hbd": "selamat ulang tahun", "gws": "semoga cepat sembuh", "jg": "juga", "aja": "aja", "ajh": "aja",
    "sih": "sih", "dong": "dong", "kok": "kok", "tuh": "itu", "nih": "ini", "lah": "lah",
    "rt": "ar ti", "ok": "oke", "okey": "oke", "oks": "oke", "sip": "sip", "mntp": "mantap",
    "anjay": "anjay", "wkwk": "hahaha", "wkwkwk": "hahaha", "awokawok": "hahaha", "awok": "hahaha",
    "ngakak": "ngakak", "xixi": "hihi", "kwkw": "hahaha", "haha": "hahaha", "hehe": "hehe",
}
LAUGH_RE = re.compile(r"^(?:(?:w+k+)+w*|a+w+o+k+(?:a+w+o+k+)*|(?:k+w+){2,}k*|x+i+x+i+(?:x+i+)*|(?:h+a+){2,}h*|(?:h+e+){2,}h*)$", re.I)
URL_RE = re.compile(r"(?:https?://|www\.)\S+", re.I)
TAG_RE = re.compile(r"[#]\w+", re.U)
MENTION_RE = re.compile(r"@([\w.]+)", re.U)
THOUSAND_RE = re.compile(r"\b(\d+(?:[.,]\d+)?)\s?(rb|ribu|k)\b", re.I)
MILLION_RE = re.compile(r"\b(\d+(?:[.,]\d+)?)\s?(jt|juta)\b", re.I)
LEET = str.maketrans({"0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "8": "b", "@": "a", "$": "s"})

GIFT_SPOKEN = {
    "rose": "mawar", "heart me": "hati", "ice cream cone": "es krim", "doughnut": "donat", "perfume": "parfum",
    "hand hearts": "hati tangan", "sunglasses": "kacamata hitam", "paper crane": "bangau kertas", "lion": "singa",
    "universe": "alam semesta", "tiktok universe": "alam semesta tiktok", "sports car": "mobil balap",
    "train": "kereta", "castle": "istana", "yacht": "kapal pesiar", "swan": "angsa", "money gun": "pistol uang",
    "whale diving": "paus menyelam", "diamond": "berlian", "heart": "hati", "love you": "aku cinta kamu",
    "galaxy": "galaksi", "gem gun": "pistol permata", "phoenix": "burung phoenix", "fire phoenix": "phoenix api",
    "diamond flight": "pesawat berlian", "concert": "konser", "falcon": "elang", "lion crown": "mahkota singa",
}


def clean(text, limit=300):
    text = "".join(ch if ch.isprintable() else " " for ch in str(text or ""))
    text = " ".join(text.split())
    return text[:limit]


def fold_unicode(text):
    """Fancy fonts (bold/italic/full-width letters) -> plain letters, drop emoji, symbols and control characters."""
    text = unicodedata.normalize("NFKC", str(text or ""))
    out = []
    for ch in text:
        cat = unicodedata.category(ch)
        if cat[0] in ("L", "N"):
            out.append(ch)
        elif cat[0] == "M":
            out.append(ch)  # keep combining marks of real scripts
        elif ch in " .,!?'\"-:;()/&%+":
            out.append(ch)
        elif ch in "\n\t":
            out.append(" ")
        elif cat[0] == "Z":
            out.append(" ")
        elif ch in "_~*":
            out.append(" ")
        # emoji (So), modifiers (Sk), format chars (Cf), surrogates etc. are dropped
    return "".join(out)


def _collapse(s):
    return re.sub(r"(.)\1+", r"\1", s)


_single_keys = {}      # collapsed key -> original badword
_phrase_res = []       # compiled regex for badwords containing spaces


def _bad_key(word):
    return _collapse(re.sub(r"[^a-z0-9]", "", word.lower().translate(LEET)))


def _rebuild_badword_index():
    _single_keys.clear()
    _phrase_res.clear()
    for w in badwords:
        if " " in w:
            parts = [re.escape(p) for p in w.split() if p]
            if parts:
                _phrase_res.append(re.compile(r"(?<!\w)" + r"[\W_]+".join(parts) + r"(?!\w)", re.I))
        else:
            key = _bad_key(w)
            if key:
                _single_keys[key] = w


# innocent words that merely contain a bad word
SAFE_WORDS = {"cockpit", "cocktail", "cockatoo", "peacock", "dickens", "pelerin", "setanggi", "ngeweb", "scunthorpe", "assistant", "classic",
              "bassist", "bokeh", "analisis", "analisa", "kimaki", "sexton", "essex", "middlesex", "titanic", "titian"}
# endings people really add to a bad word ("anjingnya", "kontolku", "bangsatlah")
_BAD_SUFFIXES = ("", "nya", "ku", "mu", "lu", "an", "in", "kan", "lah", "kah", "s", "es", "ers", "er", "ing", "ed", "y")


def is_bad_token(token):
    if not _single_keys:
        return False
    key = _bad_key(token)
    if not key:
        return False
    if token.lower() in SAFE_WORDS or key in SAFE_WORDS:
        return False
    if key in _single_keys:
        return True
    for bad_key in _single_keys:
        if len(bad_key) < 4 or bad_key not in key:
            continue
        if len(bad_key) >= 6:
            return True                       # long words also match inside a word
        # 4-5 letter words only with a normal ending (so "tetesan" or "cockpit" stay untouched)
        if key.startswith(bad_key) and key[len(bad_key):] in _BAD_SUFFIXES:
            return True
        if key.endswith(bad_key) and len(key) - len(bad_key) <= 2 and key[:len(key) - len(bad_key)] in ("ng", "me", "di", "ke", "se"):
            return True
    return False


def censor(text):
    """Return (text with badwords replaced by ***, hit)."""
    text = str(text or "")
    if not config.get("filter_on", True) or not badwords:
        return text, False
    hit = False
    for rx in _phrase_res:
        text, n = rx.subn("***", text)
        if n:
            hit = True
    tokens = text.split(" ")
    for i, tok in enumerate(tokens):
        if tok != "***" and tok and is_bad_token(tok):
            tokens[i] = "***"
            hit = True
    return " ".join(tokens), hit


COLLAPSE_TO = r"\1"


def speakable(text, limit=None):
    """Turn a chat message into text that a TTS voice reads smoothly."""
    limit = limit or int(config.get("max_chars", 160))
    global COLLAPSE_TO
    COLLAPSE_TO = r"\1" if config.get("lang", "id") in ("id", "jv", "su", "ms") else r"\1\1"
    text = str(text or "")
    text = URL_RE.sub(" ", text)
    text = TAG_RE.sub(" ", text)
    text = MENTION_RE.sub(lambda m: m.group(1).replace(".", " ").replace("_", " "), text)
    text = fold_unicode(text)
    text = THOUSAND_RE.sub(lambda m: m.group(1) + " ribu", text)
    text = MILLION_RE.sub(lambda m: m.group(1) + " juta", text)
    words = []
    for w in text.split():
        bare = w.strip(".,!?'\"-:;()/&%+")
        if not bare:
            continue
        low = bare.lower()
        if bare == "***":
            continue
        if LAUGH_RE.match(low):
            repl = "hahaha"
        elif low in SLANG_MAP:
            repl = SLANG_MAP[low]
        else:
            # "haloooo" -> "halo", "mantaaaap" -> "mantap" (3+ identical letters collapse to one)
            repl = re.sub(r"([^\W\d_])\1{2,}", COLLAPSE_TO, bare, flags=re.U)
        if repl:
            words.append(repl)
    # collapse the same word repeated 3+ times in a row
    out = []
    for w in words:
        if len(out) >= 2 and out[-1].lower() == w.lower() and out[-2].lower() == w.lower():
            continue
        out.append(w)
    res = " ".join(out)
    res = re.sub(r"([!?.,])\1+", r"\1", res)
    res = " ".join(res.split())
    if len(res) > limit:
        cut = res[:limit]
        sp = cut.rfind(" ")
        res = (cut[:sp] if sp > limit * 0.5 else cut).rstrip(" ,.;:-")
    return res.strip()


def self_speakable(text):
    """What the player types himself: keep it natural, only strip emoji / links and cut very long text."""
    text = URL_RE.sub(" ", str(text or ""))
    text = fold_unicode(text)
    text = " ".join(text.split())
    return text[:300].strip()


def clean_name(name):
    name = fold_unicode(name)
    name = re.sub(r"[\d_]+", " ", name)
    name = " ".join(name.split())
    if not re.search(r"[^\W\d_]", name, flags=re.U):
        return "Penonton"
    name, hit = censor(name)
    if hit or "***" in name:
        return "Penonton"
    return name[:22].strip() or "Penonton"


# ------------------------------------------------------------------ audio tools

def _tool(name):
    ff = os.environ.get("XYOPLAY_FFMPEG", "").strip()
    exe = name + (".exe" if os.name == "nt" else "")
    if ff:
        folder = os.path.dirname(ff)
        if folder and os.path.isfile(os.path.join(folder, exe)):
            return os.path.join(folder, exe)
        if name == "ffmpeg" and os.path.isfile(ff):
            return ff
    return shutil.which(name)


FFMPEG = _tool("ffmpeg")
FFPLAY = _tool("ffplay")
FAKE_MP3 = os.environ.get("XYOPLAY_FAKE_MP3", "")      # tests only: pretend this file is what Edge returned
STREAMING = bool(FFPLAY) and os.environ.get("XYOPLAY_NOSTREAM", "") != "1"

_play_lock = threading.Lock()
_play_proc = None
_epoch = 0   # incremented by "clear": stale items are dropped
BOOST = 1.6  # 100% in the game = this much gain; ffmpeg's limiter keeps it from clipping


def _run(cmd, timeout=30):
    return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout,
                          creationflags=NO_WINDOW)


def filter_chain(volume, tempo=0):
    parts = []
    if tempo and abs(tempo) >= 3:
        parts.append("atempo=%.3f" % max(0.5, min(2.0, 1.0 + tempo / 100.0)))
    parts.append("volume=%.2f" % (max(0.1, volume / 100.0) * BOOST))
    parts.append("alimiter=limit=0.97:level=0")
    return ",".join(parts)


def process_audio(src, volume, tempo):
    """Any audio file -> louder wav (used only when streaming through ffplay is not possible)."""
    if not FFMPEG:
        return None
    dst = src + ".wav"
    for chain in (filter_chain(volume, tempo), "volume=%.2f" % (max(0.1, volume / 100.0) * BOOST)):
        try:
            r = _run([FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-i", src, "-af", chain,
                      "-ar", "44100", "-ac", "2", dst])
            if r.returncode == 0 and os.path.exists(dst) and os.path.getsize(dst) > 200:
                return dst
        except Exception:
            pass
    return None


def _spawn_player(cmd, stdin=None, env=None):
    global _play_proc
    with _play_lock:
        proc = subprocess.Popen(cmd, stdin=stdin, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                creationflags=NO_WINDOW, env=env)
        _play_proc = proc
    return proc


def _release_player(proc):
    global _play_proc
    with _play_lock:
        if _play_proc is proc:
            _play_proc = None


def play_wav(path):
    """Blocks until the file has been played. Returns True on success."""
    if DRYRUN:
        time.sleep(0.03)
        return True
    cmd, env = None, None
    if FFPLAY:
        cmd = [FFPLAY, "-nodisp", "-autoexit", "-loglevel", "quiet", path]
    elif os.name == "nt":
        env = dict(os.environ)
        env["XYO_WAV"] = path
        cmd = ["powershell", "-NoProfile", "-Command", "(New-Object Media.SoundPlayer $env:XYO_WAV).PlaySync()"]
    elif sys.platform == "darwin":
        cmd = ["afplay", path]
    else:
        for name in ("paplay", "aplay", "play"):
            exe = shutil.which(name)
            if exe:
                cmd = [exe, path]
                break
    if not cmd:
        return False
    proc = None
    try:
        proc = _spawn_player(cmd, env=env)
        proc.wait(timeout=90)
        return proc.returncode in (0, None, -9, -15)
    except Exception:
        return False
    finally:
        if proc is not None:
            _release_player(proc)


def play_mp3_windows_fallback(path, volume):
    """Used only when ffmpeg is missing: Windows MediaPlayer (volume is capped at 100%)."""
    if os.name != "nt":
        return False
    try:
        vol = max(0.1, min(1.0, volume / 100.0))
        uri = path.replace("\\", "/")
        ps = ("Add-Type -AssemblyName presentationCore; $p = New-Object System.Windows.Media.MediaPlayer; "
              "$p.Open([System.Uri]'file:///%s'); $p.Volume = %s; $p.Play(); Start-Sleep -Milliseconds 700; "
              "while ($p.NaturalDuration.HasTimeSpan -and ($p.Position -lt $p.NaturalDuration.TimeSpan)) "
              "{ Start-Sleep -Milliseconds 120 }" % (uri, vol))
        subprocess.run(["powershell", "-NoProfile", "-Command", ps], timeout=60, creationflags=NO_WINDOW)
        return True
    except Exception:
        return False


def stop_playback():
    with _play_lock:
        proc = _play_proc
    if proc is not None:
        try:
            proc.kill()
        except Exception:
            pass


def _silent_wav(path, seconds=0.05):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\x00\x00" * int(16000 * seconds))


# ------------------------------------------------------------------ providers

_warned = set()
_loop_local = threading.local()


def warn_once(key, text):
    if key not in _warned:
        _warned.add(key)
        status(text)


def _http_get(url, headers=None, timeout=10):
    req = urllib.request.Request(url, headers=headers or {"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def _loop():
    loop = getattr(_loop_local, "loop", None)
    if loop is None or loop.is_closed():
        loop = asyncio.new_event_loop()
        _loop_local.loop = loop
    return loop


def stream_edge(sink, text, voice, rate, pitch, epoch):
    """Calls sink(bytes) for every mp3 chunk Microsoft Edge TTS returns. Returns True when audio arrived."""
    if FAKE_MP3:
        with open(FAKE_MP3, "rb") as f:
            data = f.read()
        step = max(1, len(data) // 3)
        for i in range(0, len(data), step):
            sink(data[i:i + step])
            time.sleep(0.02)
        return True
    try:
        import edge_tts
    except Exception:
        warn_once("noedge", "Suara Edge butuh paket edge-tts. Klik 'Instal paket' di tab LIVE. Memakai suara cadangan.")
        return False
    rate_s = "%+d%%" % rate
    pitch_s = "%+dHz" % pitch
    got = [False]

    async def go():
        comm = edge_tts.Communicate(text, voice, rate=rate_s, pitch=pitch_s)
        async for chunk in comm.stream():
            if epoch != _epoch:
                return
            if chunk.get("type") == "audio" and chunk.get("data"):
                got[0] = True
                sink(chunk["data"])

    for attempt in range(2):
        try:
            _loop().run_until_complete(asyncio.wait_for(go(), timeout=25))
            if got[0]:
                return True
        except Exception as exc:
            if got[0]:
                return True        # partial audio is better than none
            if attempt == 1:
                warn_once("edgefail", "Edge TTS gagal (%s: %s). Coba 'Instal paket' untuk memperbarui edge-tts. Memakai cadangan."
                          % (type(exc).__name__, clean(exc, 80)))
            time.sleep(0.2)
    return got[0]


def bytes_streamelements(text, voice):
    try:
        url = "https://api.streamelements.com/kappa/v2/speech?voice=%s&text=%s" % (
            urllib.parse.quote(voice), urllib.parse.quote(text[:300]))
        data = _http_get(url)
        return data if len(data) > 800 else b""
    except Exception:
        return b""


def _chunks(text, size=170):
    parts, cur = [], ""
    for word in text.split():
        if cur and len(cur) + 1 + len(word) > size:
            parts.append(cur)
            cur = word
        else:
            cur = (cur + " " + word).strip()
    if cur:
        parts.append(cur)
    return parts or [text]


def bytes_google(text, lang):
    try:
        data = b""
        for part in _chunks(text):
            q = urllib.parse.quote(part)
            url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=%s&client=tw-ob&q=%s" % (lang, q)
            data += _http_get(url, {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                                  "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
                                    "Referer": "https://translate.google.com/"}, 8)
        return data if len(data) > 800 else b""
    except Exception:
        return b""


_pyttsx = None
_pyttsx_tried = False


def synth_system(text, dst_wav):
    """Offline voice (pyttsx3 / SAPI) rendered to a wav file so the same loudness chain applies."""
    global _pyttsx, _pyttsx_tried
    if not _pyttsx_tried:
        _pyttsx_tried = True
        try:
            import pyttsx3
            _pyttsx = pyttsx3.init()
            _pyttsx.setProperty("rate", 165)
        except Exception:
            _pyttsx = None
    if _pyttsx is None:
        return False
    try:
        _pyttsx.save_to_file(text, dst_wav)
        _pyttsx.runAndWait()
        return os.path.exists(dst_wav) and os.path.getsize(dst_wav) > 200
    except Exception:
        return False


def speak_system_direct(text, volume):
    """Last resort when nothing could be rendered to a file."""
    env = dict(os.environ)
    env["XYO_TEXT"] = text
    env["XYO_VOL"] = str(max(10, min(100, volume)))
    try:
        if os.name == "nt":
            subprocess.run(["powershell", "-NoProfile", "-Command",
                            "Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                            "$s.Volume = [int]$env:XYO_VOL; $s.Speak($env:XYO_TEXT)"],
                           env=env, timeout=40, creationflags=NO_WINDOW)
        elif sys.platform == "darwin":
            subprocess.run(["say", text], timeout=40)
        else:
            for cmd in (["spd-say", "-w", text], ["espeak", text]):
                if shutil.which(cmd[0]):
                    subprocess.run(cmd, timeout=40)
                    return True
            return False
        return True
    except Exception:
        return False


def provider_order(opts):
    voice = str(opts.get("voice", "")).strip() or "edge:id-ID-GadisNeural"
    lang = opts.get("lang", "id")
    order = []
    if voice.startswith("edge:"):
        order.append(("edge", voice[5:]))
    elif voice.startswith("se:"):
        order.append(("se", voice[3:]))
    elif voice == "system":
        order.append(("system", ""))
    else:
        order.append(("google", ""))
    for extra in (("edge", LANG_FALLBACK_EDGE.get(lang, "id-ID-GadisNeural")), ("google", ""), ("system", "")):
        if extra not in order:
            order.append(extra)
    return order


def render_file(text, opts):
    """text -> (path of a playable loud wav/mp3, kind) using files only. Used without ffplay and as the last fallback."""
    lang = opts.get("lang", "id")
    rate, pitch, volume = int(opts.get("rate", 0)), int(opts.get("pitch", 0)), int(opts.get("volume", 100))
    base = os.path.join(tempfile.gettempdir(), "xyoplay_tts_%d_%d" % (os.getpid(), int(time.time() * 1000) % 10_000_000))
    if DRYRUN:
        p = base + ".wav"
        _silent_wav(p)
        return p, "dry"
    for kind, arg in provider_order(opts):
        mp3, wav_src = base + ".mp3", base + "_sys.wav"
        src = None
        try:
            tempo = 0
            if kind == "edge":
                buf = bytearray()
                if stream_edge(buf.extend, text, arg, rate, pitch, _epoch) and buf:
                    with open(mp3, "wb") as f:
                        f.write(bytes(buf))
                    src = mp3
            elif kind == "se":
                data = bytes_streamelements(text, arg)
                if data:
                    with open(mp3, "wb") as f:
                        f.write(data)
                    src, tempo = mp3, rate
            elif kind == "google":
                data = bytes_google(text, lang if lang in ("id", "en", "jv", "su", "ja", "ko", "ms", "zh") else "id")
                if data:
                    with open(mp3, "wb") as f:
                        f.write(data)
                    src, tempo = mp3, rate
            elif kind == "system":
                if synth_system(text, wav_src):
                    src, tempo = wav_src, rate
            if not src:
                continue
            out = process_audio(src, volume, tempo)
            if out:
                return out, kind
            keep = src + ".keep"
            os.replace(src, keep)
            return keep, kind + "-raw"
        finally:
            for p in (mp3, wav_src):
                if os.path.exists(p):
                    try:
                        os.remove(p)
                    except Exception:
                        pass
    return None, ""


# ------------------------------------------------------------------ speech queue

class Item:
    __slots__ = ("text", "epoch", "opts", "prio", "born", "chunks", "path", "kind", "done", "stream")

    def __init__(self, text, epoch, opts, prio):
        self.text, self.epoch, self.opts, self.prio = text, epoch, opts, prio
        self.born = time.time()
        self.chunks = queue.Queue()
        self.path, self.kind = None, ""
        self.done = threading.Event()
        self.stream = STREAMING


_seq = 0
_seq_lock = threading.Lock()
pending = queue.PriorityQueue()
ready = queue.Queue(maxsize=2)
MAX_PENDING = 8
STALE_COMMENT_SECONDS = 12      # a chat line that waited longer than this is skipped instead of read late


def viewer_opts():
    return {"voice": config["voice"], "lang": config["lang"], "rate": config["rate"], "pitch": config["pitch"],
            "volume": config["volume"]}


def self_opts():
    return {"voice": config.get("self_voice") or config["voice"], "lang": config["lang"],
            "rate": config.get("self_rate", config["rate"]), "pitch": config.get("self_pitch", config["pitch"]),
            "volume": config.get("self_volume", config["volume"])}


def enqueue_speech(text, prio=5, force=False, opts=None):
    """prio: 0 = the streamer himself, 1-2 = commands/gifts/follows, 5 = comments. force ignores the comment-TTS switch."""
    global _seq
    if not text or (not force and not config.get("tts", True)):
        return
    with _seq_lock:
        _seq += 1
        seq = _seq
    if pending.qsize() >= MAX_PENDING:
        # backlog: drop the oldest low-priority item instead of falling minutes behind the chat
        items = []
        try:
            while True:
                items.append(pending.get_nowait())
        except queue.Empty:
            pass
        items.sort()
        low = [i for i in items if i[0] >= 5]
        if low:
            items.remove(low[0])
        for i in items:
            pending.put(i)
        if not low and prio >= 5:
            return
    pending.put((prio, seq, _epoch, time.time(), text, opts or viewer_opts()))


def fill_item(item):
    """Produces the audio of one item: mp3 chunks for ffplay (fast path) or a finished file (fallback path)."""
    opts, text = item.opts, item.text
    if DRYRUN:
        item.path, item.kind = render_file(text, opts)
        item.stream = False
        return
    if item.stream:
        rate, pitch = int(opts.get("rate", 0)), int(opts.get("pitch", 0))
        for kind, arg in provider_order(opts):
            if item.epoch != _epoch:
                return
            try:
                if kind == "edge":
                    if stream_edge(item.chunks.put, text, arg, rate, pitch, item.epoch):
                        return
                elif kind == "se":
                    data = bytes_streamelements(text, arg)
                    if data:
                        item.chunks.put(data)
                        item.kind = "se"
                        return
                elif kind == "google":
                    lang = opts.get("lang", "id")
                    data = bytes_google(text, lang if lang in ("id", "en", "jv", "su", "ja", "ko", "ms", "zh") else "id")
                    if data:
                        item.chunks.put(data)
                        item.kind = "google"
                        return
                else:
                    break      # system voice produces a file, handled below
            except Exception as exc:
                warn_once("streamexc", "TTS error: %s" % clean(exc, 100))
        item.stream = False
    path, kind = render_file(text, opts)
    item.path, item.kind = path, kind


def synth_worker():
    try:
        import edge_tts  # noqa: F401  (import now: the first sentence should not pay for it)
    except Exception:
        pass
    while True:
        prio, seq, epoch, born, text, opts = pending.get()
        if epoch != _epoch:
            continue
        if prio >= 5 and time.time() - born > STALE_COMMENT_SECONDS:
            continue
        item = Item(text, epoch, opts, prio)
        ready.put(item)
        try:
            fill_item(item)
        except Exception as exc:
            warn_once("renderexc", "TTS error: %s" % clean(exc, 100))
            item.stream = False
        finally:
            item.chunks.put(None)
            item.done.set()


def _remove(path):
    try:
        if path and os.path.exists(path):
            os.remove(path)
    except Exception:
        pass


def play_stream(item):
    """Feeds mp3 chunks to ffplay while Edge is still synthesising. Returns True when audio was played."""
    opts = item.opts
    tempo = 0 if item.kind in ("", "edge") else int(opts.get("rate", 0))
    cmd = [FFPLAY, "-nodisp", "-autoexit", "-loglevel", "quiet", "-probesize", "16384",
           "-analyzeduration", "0", "-f", "mp3", "-af", filter_chain(int(opts.get("volume", 100)), tempo), "-i", "pipe:0"]
    try:
        proc = _spawn_player(cmd, stdin=subprocess.PIPE)
    except Exception:
        return False
    wrote = False
    try:
        while True:
            try:
                chunk = item.chunks.get(timeout=30)
            except queue.Empty:
                break
            if chunk is None or item.epoch != _epoch:
                break
            try:
                proc.stdin.write(chunk)
                proc.stdin.flush()
                wrote = True
                if item.kind == "":
                    item.kind = "edge"
            except Exception:
                break
        try:
            proc.stdin.close()
        except Exception:
            pass
        if wrote and item.epoch == _epoch:
            try:
                proc.wait(timeout=90)
            except Exception:
                proc.kill()
        else:
            proc.kill()
    finally:
        _release_player(proc)
    return wrote


def play_worker():
    while True:
        item = ready.get()
        if item.epoch != _epoch:
            continue
        if item.prio >= 5 and time.time() - item.born > STALE_COMMENT_SECONDS + 6:
            continue
        played = False
        try:
            if item.stream and not DRYRUN:
                played = play_stream(item)
            if not played and item.epoch == _epoch:
                item.done.wait(timeout=40)
                if item.path:
                    if item.kind.endswith("-raw"):
                        play_mp3_windows_fallback(item.path, int(item.opts.get("volume", 100))) or play_wav(item.path)
                    else:
                        play_wav(item.path)
                    played = True
                elif not item.stream and item.epoch == _epoch:
                    if not speak_system_direct(item.text, int(item.opts.get("volume", 100))):
                        warn_once("nottsout", "Tidak ada suara TTS yang bisa dipakai. Pasang FFmpeg dan paket edge-tts (tab LIVE > Instal paket).")
            if DRYRUN and played:
                emit("spoke", text=item.text, kind=item.kind, voice=item.opts.get("voice"), volume=item.opts.get("volume"))
        finally:
            _remove(item.path)
            if item.path:
                _remove(item.path + ".wav")


def clear_speech():
    global _epoch
    _epoch += 1
    try:
        while True:
            pending.get_nowait()
    except queue.Empty:
        pass
    try:
        while True:
            ready.get_nowait()
    except queue.Empty:
        pass
    stop_playback()


threading.Thread(target=synth_worker, daemon=True, name="tts-synth").start()
threading.Thread(target=play_worker, daemon=True, name="tts-play").start()

# ------------------------------------------------------------------ speaking helpers

_last_speaker = ("", 0.0)
_recent_user_text = {}   # (uid, text) -> timestamp
_recent_text_times = {}  # text -> [timestamps]


def is_duplicate(uid, text):
    """True for the same user repeating a message within 25 s, or the same text from many users (spam wave)."""
    now = time.time()
    low = text.lower()
    for key in [k for k, t in _recent_user_text.items() if now - t > 25]:
        _recent_user_text.pop(key, None)
    if (uid, low) in _recent_user_text:
        return True
    _recent_user_text[(uid, low)] = now
    times = [t for t in _recent_text_times.get(low, []) if now - t < 15]
    times.append(now)
    _recent_text_times[low] = times
    if len(_recent_text_times) > 300:
        _recent_text_times.clear()
    return len(times) > 3


def say_comment(uid, nickname, comment):
    global _last_speaker
    if not config.get("tts", True):
        return
    shown, hit = censor(comment)
    if hit and config.get("skip_badword_comments"):
        return
    name = clean_name(nickname)
    lang_id = config.get("lang", "id") == "id"
    first = comment.strip().split(" ", 1)[0].lower() if comment.strip() else ""
    if first and first in config.get("cmd_words", []):
        # a viewer command that does something in the game: announce it right away, short and before normal chat
        word = speakable(first.lstrip("!"), 30) or first.lstrip("!")
        enqueue_speech(("%s memanggil %s" if lang_id else "%s calls %s") % (name, word), 1)
        return
    body = speakable(shown)
    if not body or not re.search(r"[^\W_]", body, flags=re.U):
        return
    if is_duplicate(uid, body):
        return
    if comment.startswith("!") or comment.startswith("/"):
        body = "perintah " + body.lstrip("!/")
    now = time.time()
    backlog = pending.qsize() > 3
    if config.get("read_names", True) and not backlog:
        same = _last_speaker[0] == uid and now - _last_speaker[1] < 12
        text = body if same else ("%s bilang, %s" % (name, body) if lang_id else "%s says, %s" % (name, body))
    else:
        text = body
    _last_speaker = (uid, now)
    enqueue_speech(text, 5)


def gift_spoken_name(name):
    low = name.lower().strip()
    return GIFT_SPOKEN.get(low, name) if config.get("lang", "id") == "id" else name


# ------------------------------------------------------------------ stdin commands

def handle_command(msg):
    cmd = msg.get("cmd")
    if cmd == "config":
        apply_config(msg)
    elif cmd == "speak":
        text = str(msg.get("text", "")).strip()
        if text:
            shown, _hit = censor(text)
            if msg.get("profile") == "self":
                spoken = self_speakable(shown)
                opts = self_opts()
            else:
                spoken = speakable(shown, 300)
                opts = viewer_opts()
            if spoken:
                enqueue_speech(spoken, int(msg.get("prio", 0)), force=True, opts=opts)
    elif cmd == "set_badwords":
        set_badwords(msg.get("words", []))
    elif cmd == "set_banned":
        set_banned(msg.get("users", []))
    elif cmd == "clear":
        clear_speech()
    elif cmd == "quit":
        clear_speech()
        os._exit(0)


def stdin_listener():
    try:
        for raw in sys.stdin:
            line = raw.strip()
            if not line:
                continue
            try:
                handle_command(json.loads(line))
            except Exception as exc:
                emit("status", text="Perintah tidak valid: %s" % clean(exc, 80))
    except Exception:
        pass
    # stdin closed -> the game exited
    os._exit(0)


set_badwords(_env_cfg.get("badwords", []))
set_banned(_env_cfg.get("banned", []))
if __name__ == "__main__":
    threading.Thread(target=stdin_listener, daemon=True, name="stdin").start()
    if TTS_ONLY:
        status("TTS siap")
        while True:
            time.sleep(3600)

# ------------------------------------------------------------------ TikTok connection


def user_fields(event):
    user = getattr(event, "user", None)
    uid = clean(getattr(user, "unique_id", "") or "", 40).lower()
    nick = clean(getattr(user, "nickname", "") or getattr(user, "unique_id", "") or "Penonton", 40)
    return uid, nick


def event_id(event, suffix=""):
    """A stable id of one real TikTok event (message id), or "" when the library does not give one."""
    for chain in (("msg_id",), ("log_id",), ("message_id",), ("base_message", "message_id"), ("common", "msg_id"),
                  ("base_message", "msg_id")):
        try:
            value = event
            for part in chain:
                value = getattr(value, part)
            if value:
                return "%s%s" % (value, suffix)
        except Exception:
            continue
    return ""


AVATAR_SIZE = 24
avatar_done = set()
avatar_lock = threading.Lock()


def avatar_urls(user):
    urls = []
    for attr in ("avatar_thumb", "avatar_medium", "avatar_large"):
        node = getattr(user, attr, None)
        if node is None:
            continue
        for key in ("m_urls", "urls", "url_list"):
            try:
                found = getattr(node, key, None)
            except Exception:
                found = None
            if found:
                urls.extend(str(u) for u in found if u)
    direct = getattr(user, "avatar_url", None)
    if direct:
        urls.append(str(direct))
    return [u for u in urls if u.startswith("http")]


def _avatar_worker(uid, urls):
    try:
        data = None
        for url in urls[:3]:
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=6) as resp:
                    data = resp.read(600000)
                if data:
                    break
            except Exception:
                continue
        if not data or not FFMPEG:
            return
        cmd = [FFMPEG, "-v", "quiet", "-i", "pipe:0", "-vf", "scale=%d:%d:flags=area" % (AVATAR_SIZE, AVATAR_SIZE),
               "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"]
        proc = subprocess.run(cmd, input=data, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=12)
        raw = proc.stdout
        if len(raw) >= AVATAR_SIZE * AVATAR_SIZE * 3:
            import base64
            emit("avatar", uid=uid, size=AVATAR_SIZE, px=base64.b64encode(raw[:AVATAR_SIZE * AVATAR_SIZE * 3]).decode("ascii"))
    except Exception:
        with avatar_lock:
            avatar_done.discard(uid)


def request_avatar(event, uid):
    """Downloads the profile picture once per viewer in the background (never delays the event itself)."""
    try:
        if not uid or os.environ.get("XYOPLAY_NOAVATAR"):
            return
        with avatar_lock:
            if uid in avatar_done:
                return
            if len(avatar_done) > 500:
                avatar_done.clear()
            avatar_done.add(uid)
        urls = avatar_urls(getattr(event, "user", None))
        if urls:
            threading.Thread(target=_avatar_worker, args=(uid, urls), daemon=True).start()
    except Exception:
        pass


def is_banned(uid, nick):
    return (uid and uid in banned_users) or (nick and nick.lower() in banned_users)


def gift_is_streaking(event):
    try:
        value = getattr(event, "streaking", None)
        if value is not None:
            return bool(value)
    except Exception:
        pass
    try:
        gift = getattr(event, "gift", None)
        streakable = bool(getattr(gift, "streakable", False))
        return streakable and not bool(getattr(event, "repeat_end", 0))
    except Exception:
        return False


_FOLLOW_RE = [re.compile(r'"followerCount"\s*:\s*(\d+)'), re.compile(r'"follower_count"\s*:\s*(\d+)'), re.compile(r'"fans"\s*:\s*(\d+)')]
_follower_thread = None
_last_followers = [None]


def parse_followers(text):
    """Follower total from a TikTok profile page (JSON blobs inside the HTML). None when absent."""
    for rx in _FOLLOW_RE:
        m = rx.search(text or "")
        if m:
            return int(m.group(1))
    return None


def fetch_followers(uname):
    """Follower total of the account that goes LIVE, read from its public profile page. None on failure."""
    try:
        raw = _http_get("https://www.tiktok.com/@%s" % urllib.parse.quote(uname.lstrip("@")), headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            "Accept-Language": "en-US,en;q=0.9"}, timeout=12)
        return parse_followers(raw.decode("utf-8", "ignore"))
    except Exception:
        return None


def report_followers(n, source):
    if n is None or n < 0:
        return
    if _last_followers[0] == n:
        return
    _last_followers[0] = n
    emit("followers", count=int(n), source=source)


def follower_loop():
    """Reads the real follower total right away and then every minute (the number in the goal bar)."""
    first = True
    while True:
        try:
            report_followers(fetch_followers(username), "profil")
        except Exception:
            pass
        time.sleep(20 if first and _last_followers[0] is None else 60)
        first = False


def start_follower_loop():
    global _follower_thread
    if _follower_thread is None and username:
        _follower_thread = threading.Thread(target=follower_loop, daemon=True)
        _follower_thread.start()


def followers_from_client(client):
    """The room info that TikTokLive already fetched usually contains the host's follower count."""
    try:
        info = getattr(client, "room_info", None) or {}
        owner = info.get("owner") or {}
        n = (owner.get("follow_info") or {}).get("follower_count")
        if n is not None:
            report_followers(int(n), "room")
    except Exception:
        pass


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
    start_follower_loop()

    @client.on(events.ConnectEvent)
    async def on_connect(_event):
        status("Terhubung ke LIVE @%s. Menunggu komentar, follow, & gift..." % username)
        followers_from_client(client)

    @client.on(events.CommentEvent)
    async def on_comment(event):
        try:
            uid, nick = user_fields(event)
            if is_banned(uid, nick):
                return
            comment = clean(getattr(event, "comment", ""), 300)
            if not comment:
                return
            shown, hit = censor(comment)
            emit("comment", user=nick, uid=uid, text=shown, flagged=hit)
            say_comment(uid or nick.lower(), nick, comment)
        except Exception:
            pass

    cls = getattr(events, "FollowEvent", None)
    if cls is not None:
        @client.on(cls)
        async def on_follow(event):
            try:
                uid, nick = user_fields(event)
                if is_banned(uid, nick):
                    return
                total = getattr(event, "follow_count", None)
                if isinstance(total, int) and total > 0:
                    report_followers(total, "event")
                emit("follow", user=nick, uid=uid, eid=event_id(event), text="[FOLLOW] Sudah mem-follow!")
                request_avatar(event, uid)
                if config.get("read_follow", True):
                    name = clean_name(nick)
                    enqueue_speech("Terima kasih %s sudah follow" % name if config.get("lang", "id") == "id"
                                   else "Thanks %s for following" % name, 2)
            except Exception:
                pass

    cls = getattr(events, "ShareEvent", None)
    if cls is not None:
        @client.on(cls)
        async def on_share(event):
            try:
                uid, nick = user_fields(event)
                if is_banned(uid, nick):
                    return
                emit("share", user=nick, uid=uid, eid=event_id(event), text="[SHARE] Membagikan LIVE!")
                request_avatar(event, uid)
                if config.get("read_share", True):
                    name = clean_name(nick)
                    enqueue_speech("Terima kasih %s sudah share" % name if config.get("lang", "id") == "id"
                                   else "Thanks %s for sharing" % name, 2)
            except Exception:
                pass

    cls = getattr(events, "JoinEvent", None)
    if cls is not None:
        @client.on(cls)
        async def on_join(event):
            try:
                if not config.get("emit_join") and not config.get("read_join"):
                    return
                uid, nick = user_fields(event)
                if is_banned(uid, nick):
                    return
                if config.get("emit_join"):
                    emit("join", user=nick, uid=uid, text="[JOIN] Masuk ke LIVE")
                if config.get("read_join"):
                    name = clean_name(nick)
                    enqueue_speech(("Selamat datang %s" if config.get("lang", "id") == "id" else "Welcome %s") % name, 3)
            except Exception:
                pass

    cls = getattr(events, "SubscribeEvent", None)
    if cls is not None:
        @client.on(cls)
        async def on_subscribe(event):
            try:
                uid, nick = user_fields(event)
                if is_banned(uid, nick):
                    return
                emit("subscribe", user=nick, uid=uid, eid=event_id(event), text="[SUBSCRIBE] Berlangganan!")
                request_avatar(event, uid)
                enqueue_speech("Terima kasih %s sudah berlangganan" % clean_name(nick), 2)
            except Exception:
                pass

    cls = getattr(events, "LikeEvent", None)
    if cls is not None:
        like_state = {"pending": 0, "total": 0, "uid": "", "nick": "", "timer": None}

        def flush_likes():
            like_state["timer"] = None
            count = like_state["pending"]
            like_state["pending"] = 0
            if count > 0:
                emit("like", user=like_state["nick"], uid=like_state["uid"], count=count, total=like_state["total"])

        @client.on(cls)
        async def on_like(event):
            try:
                uid, nick = user_fields(event)
                if is_banned(uid, nick):
                    return
                try:
                    count = int(getattr(event, "count", None) or getattr(event, "like_count", None) or 1)
                except Exception:
                    count = 1
                try:
                    total = int(getattr(event, "total", None) or getattr(event, "total_likes", None) or 0)
                except Exception:
                    total = 0
                like_state["pending"] += max(1, count)
                like_state["total"] = max(like_state["total"], total)
                like_state["uid"], like_state["nick"] = uid, nick
                # many taps arrive per second: report them together every 0.3 s
                if like_state["timer"] is None:
                    like_state["timer"] = asyncio.get_running_loop().call_later(0.3, flush_likes)
            except Exception:
                pass

    cls = getattr(events, "GiftEvent", None)
    if cls is not None:
        streaks = {}
        seen_eids = set()

        @client.on(cls)
        async def on_gift(event):
            try:
                uid, nick = user_fields(event)
                if is_banned(uid, nick):
                    return
                gift = getattr(event, "gift", None)
                gift_name = clean(getattr(gift, "name", "") or getattr(gift, "describe", "") or "hadiah", 40)
                try:
                    gift_id = int(getattr(gift, "id", 0) or 0)
                except Exception:
                    gift_id = 0
                try:
                    total = int(getattr(event, "repeat_count", 1) or getattr(event, "combo_count", 1) or 1)
                except Exception:
                    total = 1
                total = max(1, total)
                eid = event_id(event, ":%d" % total)
                if eid:
                    if eid in seen_eids:
                        return                     # the same TikTok message delivered twice
                    seen_eids.add(eid)
                    if len(seen_eids) > 800:
                        seen_eids.clear()
                try:
                    unit = int(getattr(gift, "diamond_count", 0) or 0)
                except Exception:
                    unit = 0
                streaking = gift_is_streaking(event)
                key = (uid, gift_id or gift_name, getattr(event, "group_id", None))
                last = streaks.get(key, 0)
                delta = total - last if total >= last else total
                if streaking:
                    streaks[key] = total
                    if delta <= 0:
                        return
                else:
                    streaks.pop(key, None)
                    if len(streaks) > 300:
                        streaks.clear()
                # every new piece of a combo is reported at once (delta) so the game reacts without waiting for the combo to end;
                # the list entry and the thank-you voice come once, when the combo is over
                desc = "%dx %s" % (total, gift_name) if total > 1 else gift_name
                request_avatar(event, uid)
                emit("gift", user=nick, uid=uid, eid=eid, gift=gift_name, gift_id=gift_id, count=max(0, delta), total=total,
                     final=not streaking, diamonds=unit * max(0, delta), unit=unit, text="[GIFT] Mengirim %s" % desc)
                if not streaking and config.get("read_gift", True):
                    name = clean_name(nick)
                    spoken = gift_spoken_name(gift_name)
                    if config.get("lang", "id") == "id":
                        phrase = ("Terima kasih %s sudah kirim %d %s" % (name, total, spoken)) if total > 1 \
                            else ("Terima kasih %s sudah kirim %s" % (name, spoken))
                    else:
                        phrase = "Thank you %s for %s" % (name, ("%d %s" % (total, gift_name)) if total > 1 else gift_name)
                    enqueue_speech(phrase, 2)
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


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
    sys.exit(0)
