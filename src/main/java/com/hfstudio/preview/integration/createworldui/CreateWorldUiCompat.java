package com.hfstudio.preview.integration.createworldui;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Optional;
import decok.dfcdvadstf.createworldui.api.tab.TabRegistry;

/**
 * Optional CreateWorldUI integration entry point.
 *
 * <p>ModernCreateWorldUI 7.x exposes external tabs through the CatFrame-based
 * {@link TabRegistry}: a tab factory receives the host {@code GuiCreateWorld}
 * screen and is instantiated on every screen rebuild. The preview tab is the
 * only integration surface here — no button injection into the World tab,
 * matching the modern component-based screen layout.</p>
 */
public class CreateWorldUiCompat {

    public static final String MOD_ID = "createworldui";

    /** Unique tab key; priority defaults to after all built-in tabs. */
    private static final String TAB_ID = "worldpreview:preview";

    public void registerPreviewTabIfAvailable() {
        if (!Loader.isModLoaded(MOD_ID)) {
            return;
        }
        registerPreviewTab();
    }

    @Optional.Method(modid = MOD_ID)
    protected void registerPreviewTab() {
        TabRegistry.registerTab(CreateWorldPreviewTab::new, TAB_ID);
    }
}
