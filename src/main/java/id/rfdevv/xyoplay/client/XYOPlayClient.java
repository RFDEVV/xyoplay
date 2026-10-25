package id.rfdevv.xyoplay.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { LocalAudioPlayer.stop(); TikTokLiveService.stop(); });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("xyoplay", "now_playing"), (graphics, deltaTracker) -> Hud.render(graphics));

        // /xyoplay always works, even if another mod steals the X key.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("xyoplay").executes(context -> { pendingOpen = true; return 1; })));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (LocalAudioPlayer.takeCompletion()) Playback.onTrackEnded();
            if (client.player == null) return;

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
