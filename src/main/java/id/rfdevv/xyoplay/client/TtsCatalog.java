package id.rfdevv.xyoplay.client;

import java.util.List;

/** Every free voice the reader can use. Key format: "edge:<ShortName>", "se:<Name>", "google" or "system". */
final class TtsCatalog {
    private TtsCatalog() {}

    record Voice(String key, String label, String group) {}

    static final List<Voice> VOICES = List.of(
            // Microsoft Edge neural voices (free, no account). Need the edge-tts package (installed from the LIVE tab).
            new Voice("edge:id-ID-GadisNeural", "Gadis · Indonesia (Perempuan)", "Microsoft Edge"),
            new Voice("edge:id-ID-ArdiNeural", "Ardi · Indonesia (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:jv-ID-SitiNeural", "Siti · Jawa (Perempuan)", "Microsoft Edge"),
            new Voice("edge:jv-ID-DimasNeural", "Dimas · Jawa (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:su-ID-TutiNeural", "Tuti · Sunda (Perempuan)", "Microsoft Edge"),
            new Voice("edge:su-ID-JajangNeural", "Jajang · Sunda (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:ms-MY-YasminNeural", "Yasmin · Melayu (Perempuan)", "Microsoft Edge"),
            new Voice("edge:ms-MY-OsmanNeural", "Osman · Melayu (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:fil-PH-BlessicaNeural", "Blessica · Filipino (Perempuan)", "Microsoft Edge"),
            new Voice("edge:en-US-AvaMultilingualNeural", "Ava · Multibahasa (Perempuan)", "Microsoft Edge"),
            new Voice("edge:en-US-AndrewMultilingualNeural", "Andrew · Multibahasa (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:en-US-AvaNeural", "Ava · Inggris AS (Perempuan)", "Microsoft Edge"),
            new Voice("edge:en-US-EmmaNeural", "Emma · Inggris AS (Perempuan)", "Microsoft Edge"),
            new Voice("edge:en-US-JennyNeural", "Jenny · Inggris AS (Perempuan)", "Microsoft Edge"),
            new Voice("edge:en-US-AriaNeural", "Aria · Inggris AS (Perempuan)", "Microsoft Edge"),
            new Voice("edge:en-US-AndrewNeural", "Andrew · Inggris AS (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:en-US-BrianNeural", "Brian · Inggris AS (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:en-US-GuyNeural", "Guy · Inggris AS (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:en-GB-SoniaNeural", "Sonia · Inggris UK (Perempuan)", "Microsoft Edge"),
            new Voice("edge:en-GB-RyanNeural", "Ryan · Inggris UK (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:en-AU-NatashaNeural", "Natasha · Inggris Australia (Perempuan)", "Microsoft Edge"),
            new Voice("edge:ja-JP-NanamiNeural", "Nanami · Jepang (Perempuan)", "Microsoft Edge"),
            new Voice("edge:ja-JP-KeitaNeural", "Keita · Jepang (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:ko-KR-SunHiNeural", "Sun-Hi · Korea (Perempuan)", "Microsoft Edge"),
            new Voice("edge:ko-KR-InJoonNeural", "InJoon · Korea (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:zh-CN-XiaoxiaoNeural", "Xiaoxiao · Mandarin (Perempuan)", "Microsoft Edge"),
            new Voice("edge:zh-CN-YunxiNeural", "Yunxi · Mandarin (Laki-laki)", "Microsoft Edge"),
            new Voice("edge:hi-IN-SwaraNeural", "Swara · Hindi (Perempuan)", "Microsoft Edge"),
            new Voice("edge:th-TH-PremwadeeNeural", "Premwadee · Thai (Perempuan)", "Microsoft Edge"),
            new Voice("edge:vi-VN-HoaiMyNeural", "HoaiMy · Vietnam (Perempuan)", "Microsoft Edge"),
            new Voice("edge:ar-SA-ZariyahNeural", "Zariyah · Arab (Perempuan)", "Microsoft Edge"),
            new Voice("edge:es-ES-ElviraNeural", "Elvira · Spanyol (Perempuan)", "Microsoft Edge"),
            new Voice("edge:fr-FR-DeniseNeural", "Denise · Prancis (Perempuan)", "Microsoft Edge"),
            new Voice("edge:de-DE-KatjaNeural", "Katja · Jerman (Perempuan)", "Microsoft Edge"),
            new Voice("edge:pt-BR-FranciscaNeural", "Francisca · Portugis Brasil (Perempuan)", "Microsoft Edge"),
            // StreamElements (Amazon Polly voices, free, English)
            new Voice("se:Brian", "Brian · Inggris UK (Laki-laki)", "StreamElements"),
            new Voice("se:Amy", "Amy · Inggris UK (Perempuan)", "StreamElements"),
            new Voice("se:Emma", "Emma · Inggris UK (Perempuan)", "StreamElements"),
            new Voice("se:Joanna", "Joanna · Inggris AS (Perempuan)", "StreamElements"),
            new Voice("se:Matthew", "Matthew · Inggris AS (Laki-laki)", "StreamElements"),
            new Voice("se:Justin", "Justin · Inggris AS (anak laki-laki)", "StreamElements"),
            new Voice("se:Ivy", "Ivy · Inggris AS (anak perempuan)", "StreamElements"),
            // Google Translate (free) and the operating system voice (offline)
            new Voice("google", "Google Translate · ikut bahasa TTS", "Google"),
            new Voice("system", "Suara sistem (offline)", "Sistem"));

    static final String DEFAULT_KEY = "edge:id-ID-GadisNeural";

    static int indexOf(String key) {
        for (int i = 0; i < VOICES.size(); i++) if (VOICES.get(i).key().equals(key)) return i;
        return 0;
    }

    static Voice byKey(String key) { return VOICES.get(indexOf(key)); }

    /** Old configs stored "google" / "system" or a numeric index; anything unknown becomes the default voice. */
    static String normalize(String key) {
        if (key == null || key.isBlank()) return DEFAULT_KEY;
        for (Voice v : VOICES) if (v.key().equals(key)) return key;
        return DEFAULT_KEY;
    }
}
