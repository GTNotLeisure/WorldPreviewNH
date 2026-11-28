package com.hfstudio.preview.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.StatCollector;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.GuiScreenEvent;

import com.hfstudio.preview.gui.GuiPreviewButton;
import com.hfstudio.preview.gui.GuiWorldPreview;
import com.hfstudio.preview.network.ServerSeedData;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Forge event handler that injects preview buttons into:
 * 1. GuiCreateWorld (vanilla layout) - 20x20 icon button next to "World Type";
 *    skipped when CreateWorldUI is present because its registered preview tab
 *    replaces the button
 * 2. GuiIngameMenu - 20x20 icon button next to Statistics (singleplayer only)
 */
public class CreateWorldHandler {

    private static final int PREVIEW_BUTTON_ID = -161520;
    private static final int INGAME_PREVIEW_BUTTON_ID = -161521;

    private GuiButton worldTypeButton;
    private GuiPreviewButton previewButton;
    private GuiButton ingamePreviewButton;
    private final CreateWorldScreenAdapter vanillaAdapter = new VanillaCreateWorldScreenAdapter();
    private final CreateWorldUiScreenAdapter createWorldUiAdapter = new CreateWorldUiScreenAdapter();

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiCreateWorld) {
            initCreateWorldButton(event);
        } else if (event.gui instanceof GuiIngameMenu) {
            initIngameMenuButton(event);
        }
    }

    @SuppressWarnings("unchecked")
    private void initCreateWorldButton(GuiScreenEvent.InitGuiEvent.Post event) {
        worldTypeButton = null;
        previewButton = null;
        GuiCreateWorld screen = (GuiCreateWorld) event.gui;
        // CreateWorldUI provides its own preview tab; skip vanilla button injection
        if (createWorldUiAdapter.shouldHandle(screen)) {
            return;
        }
        GuiButton anchorButton = vanillaAdapter.findAnchorButton(screen, event.buttonList);
        if (anchorButton != null) {
            worldTypeButton = anchorButton;
            previewButton = new GuiPreviewButton(PREVIEW_BUTTON_ID, anchorButton.xPosition, anchorButton.yPosition);
            vanillaAdapter.updatePreviewButton(screen, previewButton, event.buttonList);
            event.buttonList.add(previewButton);
        }
    }

    @SuppressWarnings("unchecked")
    private void initIngameMenuButton(GuiScreenEvent.InitGuiEvent.Post event) {
        ingamePreviewButton = null;
        Minecraft mc = Minecraft.getMinecraft();

        // Only show in singleplayer or when server has the mod installed
        if (!mc.isSingleplayer() && !ServerSeedData.isAvailable()) return;

        // Find the Statistics button (ID 7)
        GuiButton statsButton = null;
        for (Object obj : event.buttonList) {
            GuiButton btn = (GuiButton) obj;
            if (btn.id == 7) {
                statsButton = btn;
                break;
            }
        }

        if (statsButton != null) {
            // Place icon button to the right of statistics button
            int btnX = statsButton.xPosition + statsButton.width + 4;
            int btnY = statsButton.yPosition + (statsButton.height - 20) / 2;
            ingamePreviewButton = new GuiPreviewButton(INGAME_PREVIEW_BUTTON_ID, btnX, btnY);
            event.buttonList.add(ingamePreviewButton);
        }
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (!(event.gui instanceof GuiCreateWorld screen)) return;
        if (createWorldUiAdapter.shouldHandle(screen)) {
            return;
        }

        if (worldTypeButton != null && previewButton != null) {
            previewButton.xPosition = worldTypeButton.xPosition + worldTypeButton.width + 4;
            previewButton.yPosition = worldTypeButton.yPosition + (worldTypeButton.height - previewButton.height) / 2;
            previewButton.visible = worldTypeButton.visible;
            previewButton.enabled = worldTypeButton.enabled;
        }
    }

    @SubscribeEvent
    public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        String tooltip = StatCollector.translateToLocal("worldpreview.button.tooltip");

        if (event.gui instanceof GuiCreateWorld screen) {
            if (createWorldUiAdapter.shouldHandle(screen)) {
                return;
            }
            if (previewButton != null && previewButton.isHovered()) {
                GuiPreviewButton.drawVanillaTooltip(
                    mc.fontRenderer,
                    tooltip,
                    event.mouseX,
                    event.mouseY,
                    event.gui.width,
                    event.gui.height);
            }
        } else if (event.gui instanceof GuiIngameMenu) {
            if (ingamePreviewButton instanceof GuiPreviewButton btn && btn.isHovered()) {
                GuiPreviewButton.drawVanillaTooltip(
                    mc.fontRenderer,
                    tooltip,
                    event.mouseX,
                    event.mouseY,
                    event.gui.width,
                    event.gui.height);
            }
        }
    }

    @SubscribeEvent
    public void onActionPerformed(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (event.gui instanceof GuiCreateWorld && event.button.id == PREVIEW_BUTTON_ID) {
            handleCreateWorldPreview((GuiCreateWorld) event.gui);
        } else if (event.gui instanceof GuiIngameMenu && event.button.id == INGAME_PREVIEW_BUTTON_ID) {
            handleIngamePreview(event.gui);
        }
    }

    private void handleCreateWorldPreview(GuiCreateWorld screen) {
        CreateWorldPreviewContext context = vanillaAdapter.createPreviewContext(screen);
        Minecraft.getMinecraft()
            .displayGuiScreen(
                new GuiWorldPreview(
                    screen,
                    context.seed(),
                    context.displaySeed(),
                    context.worldType(),
                    context.generatorOptions()));
    }

    private void handleIngamePreview(net.minecraft.client.gui.GuiScreen pauseMenu) {
        Minecraft mc = Minecraft.getMinecraft();

        long seed;
        WorldType worldType;
        String genOptions;

        if (mc.isSingleplayer()) {
            IntegratedServer server = mc.getIntegratedServer();
            if (server == null || server.worldServers == null || server.worldServers.length == 0) return;

            seed = server.worldServers[0].getSeed();
            worldType = server.worldServers[0].getWorldInfo()
                .getTerrainType();
            genOptions = server.worldServers[0].getWorldInfo()
                .getGeneratorOptions();
        } else if (ServerSeedData.isAvailable()) {
            seed = ServerSeedData.getSeed();
            worldType = ServerSeedData.getWorldType();
            genOptions = ServerSeedData.getGeneratorOptions();
        } else {
            return;
        }

        if (worldType == null) {
            worldType = WorldType.DEFAULT;
        }

        // Center on player position and match player dimension
        int playerX = MathHelper.floor_double(mc.thePlayer.posX);
        int playerZ = MathHelper.floor_double(mc.thePlayer.posZ);
        int playerDim = mc.thePlayer.dimension;

        mc.displayGuiScreen(
            new GuiWorldPreview(
                pauseMenu,
                seed,
                String.valueOf(seed),
                worldType,
                genOptions,
                playerX,
                playerZ,
                playerDim));
    }
}
