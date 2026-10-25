package id.rfdevv.xyoplay.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/** Optional Mod Menu integration: its Configure button opens XYOPlay settings. */
public final class XYOPlayModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return (Screen parent) -> new MusicScreen(parent, true);
    }
}
