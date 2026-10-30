package id.rfdevv.xyoplay.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class XYOPlayClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("xyoplay", "main"));
    private static final KeyMapping OPEN_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.xyoplay.open", InputConstants.Type.KEYSYM, InputConstants.KEY_X, CATEGORY));

    /** Set by /xyoplay; consumed once no other screen (e.g. chat) is open. */
    private static volatile boolean pendingOpen;
    private static boolean fallbackWasDown;
    private static long cachedWindowHandle;

    @Override
    public void onInitializeClient() {
        ClientSettings.load();
        MusicLibrary.initialize();
        LocalAudioPlayer.initialize();
        LiveFilter.ensureLoaded();
        LiveActions.ensureLoaded();
        Waypoints.ensureLoaded();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { LocalAudioPlayer.stop(); TikTokLiveService.stop(); });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("xyoplay", "now_playing"), (graphics, deltaTracker) -> Hud.render(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("xyoplay", "live_banner"), (graphics, deltaTracker) -> LiveBanner.render(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("xyoplay", "waypoint_marker"), (graphics, deltaTracker) -> Hud.renderMarker(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("xyoplay", "live_goal"), (graphics, deltaTracker) -> LiveGoal.render(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("xyoplay", "bot_afk"), (graphics, deltaTracker) -> Bots.renderHud(graphics));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("xyoplay", "info_line"), (graphics, deltaTracker) -> Hud.renderInfo(graphics));

        // Chat -> voice: while "Chat jadi suara" is on, what you type in the Minecraft chat is spoken (and, by default, not sent).
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!ClientSettings.chatTts || message == null || message.isBlank()) return true;
            TikTokLiveService.speak(message, 0, true);
            McCompat.localMessage("§b[Suara]§f " + message);
            return ClientSettings.chatTtsSend;
        });

        registerCommands();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (LocalAudioPlayer.takeCompletion()) Playback.onTrackEnded();
            if (client.player == null) { worldWarmed = false; Bots.onLeave(); return; }
            if (!worldWarmed) { worldWarmed = true; if (ClientSettings.chatTts) TikTokLiveService.warmUp(); }
            McCompat.tickRemote();
            LiveActions.tick();
            Waypoints.tick();
            try { Bots.tick(); } catch (Throwable t) { Bots.problem = "bot error: " + t.getClass().getSimpleName(); }

            boolean fired = false;
            while (OPEN_KEY.consumeClick()) fired = true;

            // Fallback: when another mod is bound to the same key, vanilla only delivers the press to ONE of them.
            // Read the physical key directly (only while the binding is still the default X).
            boolean down = defaultKeyDown(client);
            if (!fired && down && !fallbackWasDown && client.screen == null) fired = true;
            fallbackWasDown = down;

            if (pendingOpen && client.screen == null) { pendingOpen = false; client.setScreen(new MusicScreen()); return; }
            if (fired) {
                if (client.screen == null) client.setScreen(new MusicScreen());
                else if (client.screen instanceof MusicScreen) client.setScreen(null);
            }
        });
    }

    private static void registerCommands() {
        // /xyoplay always works, even if another mod steals the X key.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("xyoplay")
                        .executes(context -> { pendingOpen = true; return 1; })
                        .then(ClientCommands.literal("say").then(ClientCommands.argument("teks", StringArgumentType.greedyString())
                                .executes(context -> {
                                    TikTokLiveService.speak(StringArgumentType.getString(context, "teks"), 0, true);
                                    return 1;
                                })))
                        .then(ClientCommands.literal("tts")
                                .then(ClientCommands.literal("on").executes(context -> { setChatTts(true); return 1; }))
                                .then(ClientCommands.literal("off").executes(context -> { setChatTts(false); return 1; }))
                                .then(ClientCommands.literal("stop").executes(context -> {
                                    TikTokLiveService.stopSpeaking();
                                    McCompat.localMessage("§e[XYOPlay]§f Suara dihentikan.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("bot")
                                .executes(context -> { McCompat.localMessage("§b[Bot]§7 " + Bots.status()); return 1; })
                                .then(ClientCommands.argument("perintah", StringArgumentType.greedyString()).executes(context -> {
                                    McCompat.localMessage("§b[Bot]§7 " + Bots.handle(StringArgumentType.getString(context, "perintah")));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("afk")
                                .executes(context -> { McCompat.localMessage("§b[AFK]§7 " + Bots.handle("afk")); return 1; })
                                .then(ClientCommands.literal("on").executes(context -> { McCompat.localMessage("§b[AFK]§7 " + Bots.handle("afk on")); return 1; }))
                                .then(ClientCommands.literal("off").executes(context -> { McCompat.localMessage("§b[AFK]§7 " + Bots.handle("afk off")); return 1; })))
                        .then(ClientCommands.literal("spawn").then(ClientCommands.argument("mob", StringArgumentType.word())
                                .executes(context -> spawnTest(StringArgumentType.getString(context, "mob"), 1))
                                .then(ClientCommands.argument("jumlah", IntegerArgumentType.integer(1, 20))
                                        .executes(context -> spawnTest(StringArgumentType.getString(context, "mob"), IntegerArgumentType.getInteger(context, "jumlah"))))))
                        .then(ClientCommands.literal("sim")
                                .then(ClientCommands.literal("follow").executes(context -> { TikTokLiveService.simulate("follow", "Budi", "", 1, 0); return 1; }))
                                .then(ClientCommands.literal("share").executes(context -> { TikTokLiveService.simulate("share", "Budi", "", 1, 0); return 1; }))
                                .then(ClientCommands.literal("gift").then(ClientCommands.argument("nama", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String gift = GiftCatalog.canonical(StringArgumentType.getString(context, "nama"));
                                            int price = GiftBook.priceOf(gift);
                                            TikTokLiveService.simulate("gift", "Siti", gift, 1, price > 0 ? price : 1);
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("koin").then(ClientCommands.argument("harga", IntegerArgumentType.integer(1, 100000))
                                        .executes(context -> {
                                            int price = IntegerArgumentType.getInteger(context, "harga");
                                            TikTokLiveService.simulate("gift", "Siti", "Gift Uji " + price, 1, price);
                                            return 1;
                                        })))
                                .then(ClientCommands.literal("komentar").then(ClientCommands.argument("teks", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            TikTokLiveService.simulate("comment", "Andi", StringArgumentType.getString(context, "teks"), 1, 0);
                                            return 1;
                                        }))))
                        .then(ClientCommands.literal("ban").then(ClientCommands.argument("akun", StringArgumentType.word())
                                .executes(context -> {
                                    String user = StringArgumentType.getString(context, "akun");
                                    McCompat.localMessage(LiveFilter.ban(user) ? "§e[XYOPlay]§f Akun diblokir: " + user : "§e[XYOPlay]§f Akun tidak valid / sudah diblokir.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("unban").then(ClientCommands.argument("akun", StringArgumentType.word())
                                .executes(context -> {
                                    String user = StringArgumentType.getString(context, "akun");
                                    McCompat.localMessage(LiveFilter.unban(user) ? "§e[XYOPlay]§f Blokir dibuka: " + user : "§e[XYOPlay]§f Akun itu tidak ada di daftar blokir.");
                                    return 1;
                                })))
                        .then(ClientCommands.literal("titik").then(ClientCommands.argument("nama", StringArgumentType.greedyString())
                                .executes(context -> {
                                    Waypoints.Point p = Waypoints.addHere(StringArgumentType.getString(context, "nama"));
                                    McCompat.localMessage(p == null ? "§e[XYOPlay]§f Masuk ke dunia dulu." : "§e[XYOPlay]§f Titik \"" + p.name() + "\" disimpan di " + Waypoints.coords(p));
                                    return 1;
                                })))));
    }

    private static boolean worldWarmed;

    static void setChatTts(boolean on) {
        ClientSettings.chatTts = on;
        ClientSettings.save();
        if (on) TikTokLiveService.speak("Mode chat jadi suara aktif", 0, true);
        McCompat.localMessage(on
                ? "§e[XYOPlay]§f Chat jadi suara §aAKTIF§f. Apa pun yang kamu ketik di chat akan dibacakan" + (ClientSettings.chatTtsSend ? " dan juga dikirim ke chat." : " (tidak dikirim ke chat).")
                : "§e[XYOPlay]§f Chat jadi suara §cMATI§f.");
    }

    private static int spawnTest(String mob, int count) {
        if (LiveActions.resolveMob(mob) == null) { McCompat.localMessage("§e[XYOPlay]§f Nama mob tidak dikenal: " + mob); return 0; }
        for (int i = 0; i < count; i++) LiveActions.spawn(mob, "XYOPlay");
        return 1;
    }

    /** Human readable key currently bound to the open shortcut, e.g. "X". */
    static String keyName() {
        try { return OPEN_KEY.isUnbound() ? "BELUM DIATUR" : OPEN_KEY.getTranslatedKeyMessage().getString(); }
        catch (Throwable t) { return "X"; }
    }

    private static boolean defaultKeyDown(Minecraft client) {
        try {
            if (!"key.keyboard.x".equals(OPEN_KEY.saveString())) return false;
            long handle = windowHandle(client);
            return handle != 0 && GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_X) == GLFW.GLFW_PRESS;
        } catch (Throwable t) { return false; }
    }

    /** Window#handle() in newer versions, Window#getWindow() in older ones; resolved by name so both compile. */
    private static long windowHandle(Minecraft client) {
        if (cachedWindowHandle != 0) return cachedWindowHandle;
        Object window = client.getWindow();
        for (String name : new String[] {"handle", "getWindow"}) {
            try {
                Object value = window.getClass().getMethod(name).invoke(window);
                if (value instanceof Long l && l != 0) { cachedWindowHandle = l; return l; }
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    static String trim(String value, int max) {
        if (value == null) return "Tidak ada lagu";
        return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)) + "…";
    }
}
