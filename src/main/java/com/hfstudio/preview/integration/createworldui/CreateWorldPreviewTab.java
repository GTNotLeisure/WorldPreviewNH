package com.hfstudio.preview.integration.createworldui;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.util.MathHelper;
import net.minecraft.util.StatCollector;
import net.minecraft.world.WorldType;

import com.hfstudio.preview.data.DimensionInfo;
import com.hfstudio.preview.gui.EmbeddedWorldPreview;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import decok.dfcdvadstf.catframe.ui.GuiGraphicsExtractor;
import decok.dfcdvadstf.catframe.ui.Text;
import decok.dfcdvadstf.catframe.ui.components.AbstractComponent;
import decok.dfcdvadstf.catframe.ui.components.Button;
import decok.dfcdvadstf.catframe.ui.components.CyclingButton;
import decok.dfcdvadstf.catframe.ui.components.events.GuiEventListener;
import decok.dfcdvadstf.catframe.ui.components.tab.Tab;
import decok.dfcdvadstf.catframe.ui.navigation.ScreenRectangle;
import decok.dfcdvadstf.createworldui.mixin.middle.access.IGuiCreateWorldAccess;

/**
 * Embedded world-preview tab for CreateWorldUI, built on the CatFrame component
 * system.
 *
 * <p>The host screen rebuilds every tab through {@code TabRegistry} on each
 * {@code initGui} (resize, sub-screen round-trip), so the preview instance and
 * its viewport state live in a static {@link SessionState} that survives
 * rebuilds while keeping the sampler cache warm.</p>
 */
@SideOnly(Side.CLIENT)
public class CreateWorldPreviewTab implements Tab {

    private static final int GOTO_WIDTH = 70;
    private static final int WORLD_TYPE_WIDTH = 100;
    private static final int DIMENSION_WIDTH = 108;
    private static final int CONTROL_ROW_HEIGHT = 20;
    private static final int CONTROL_SPACING = 4;
    private static final int PREVIEW_BOTTOM_PADDING = 6;

    private final GuiCreateWorld guiCreateWorld;
    private final IGuiCreateWorldAccess access;
    private final EmbeddedWorldPreview preview;
    private final List<DimensionInfo> dimensionList = DimensionInfo.getAllDimensions();

    private final PreviewComponent previewComponent = new PreviewComponent();
    private final Button gotoButton;
    private final CyclingButton<WorldType> worldTypeButton;
    private final CyclingButton<DimensionInfo> dimensionButton;

    public CreateWorldPreviewTab(GuiCreateWorld screen) {
        this.guiCreateWorld = screen;
        this.access = (IGuiCreateWorldAccess) screen;

        preview = SESSION.obtainPreview(screen, access);
        if (!SESSION.previewInitialized) {
            String seed = access.modernWorldCreatingUI$getSeed();
            SESSION.lastSeedText = seed != null ? seed : "";
            SESSION.lastWorldTypeIndex = access.modernWorldCreatingUI$getWorldTypeIndex();
        }

        gotoButton = Button.builder(
                Text.translatable("worldpreview.goto.title"),
                btn -> openGotoScreen())
            .width(GOTO_WIDTH)
            .height(CONTROL_ROW_HEIGHT)
            .useVanillaTexture(true)
            .build();

        worldTypeButton = CyclingButton.<WorldType>builder(
                wt -> StatCollector.translateToLocalFormatted(
                    "worldpreview.gui.worldtype",
                    StatCollector.translateToLocal("generator." + wt.getWorldTypeName())))
            .values(validWorldTypes())
            .initially(resolveWorldType(access.modernWorldCreatingUI$getWorldTypeIndex()))
            .useVanillaTexture(true)
            .build(0, 0, WORLD_TYPE_WIDTH, CONTROL_ROW_HEIGHT, (button, worldType) -> {
                for (int i = 0; i < WorldType.worldTypes.length; i++) {
                    if (WorldType.worldTypes[i] == worldType) {
                        access.modernWorldCreatingUI$setWorldTypeIndex(i);
                        SESSION.lastWorldTypeIndex = i;
                        updatePreviewContext(resolveSeedText(), i);
                        break;
                    }
                }
            });

        dimensionButton = CyclingButton.<DimensionInfo>builder(
                dim -> StatCollector.translateToLocalFormatted(
                    "worldpreview.gui.dimension",
                    dim.displayName()))
            .values(dimensionList)
            .initially(currentDimensionInfo())
            .useVanillaTexture(true)
            .build(0, 0, DIMENSION_WIDTH, CONTROL_ROW_HEIGHT, (button, dimension) -> {
                preview.selectDimensionById(dimension.dimensionId());
                SESSION.dimensionId = dimension.dimensionId();
            });
    }

    // ==== Tab contract ====

    @Override
    public Text getTabTitle() {
        return Text.translatable("worldpreview.tab.preview");
    }

    @Override
    public Text getTabExtraNarration() {
        return Text.literal("");
    }

    @Override
    public void visitChildren(Consumer<GuiEventListener> childrenConsumer) {
        // Buttons first: the host resolves clicks against the first matching
        // child, so the control row must win hit-testing over the full-area
        // preview component.
        childrenConsumer.accept(gotoButton);
        childrenConsumer.accept(worldTypeButton);
        childrenConsumer.accept(dimensionButton);
        childrenConsumer.accept(previewComponent);
    }

    @Override
    public void doLayout(ScreenRectangle screenRectangle) {
        int controlRowTop = screenRectangle.y + screenRectangle.height - CONTROL_ROW_HEIGHT;
        int previewBottom = controlRowTop - PREVIEW_BOTTOM_PADDING;

        // Control row, centered as a group
        int totalWidth = GOTO_WIDTH + CONTROL_SPACING + WORLD_TYPE_WIDTH + CONTROL_SPACING + DIMENSION_WIDTH;
        int startX = screenRectangle.x + Math.max(0, (screenRectangle.width - totalWidth) / 2);

        gotoButton.setX(startX);
        gotoButton.setY(controlRowTop);
        worldTypeButton.setX(startX + GOTO_WIDTH + CONTROL_SPACING);
        worldTypeButton.setY(controlRowTop);
        dimensionButton.setX(startX + GOTO_WIDTH + CONTROL_SPACING + WORLD_TYPE_WIDTH + CONTROL_SPACING);
        dimensionButton.setY(controlRowTop);

        // Preview fills everything above the control row
        int previewWidth = screenRectangle.width;
        int previewHeight = Math.max(10, previewBottom - screenRectangle.y);
        boolean sizeChanged = previewComponent.getWidth() != previewWidth
            || previewComponent.getHeight() != previewHeight;
        previewComponent.setX(screenRectangle.x);
        previewComponent.setY(screenRectangle.y);
        previewComponent.setSize(previewWidth, previewHeight);

        // Lazy-init the embedded preview with the concrete bounds; re-init only on
        // a size change so the sampler cache survives tab rebuilds.
        if (!SESSION.previewInitialized || sizeChanged || SESSION.needsViewportRestore) {
            initEmbeddedPreview();
        }
    }

    // ==== Preview wiring ====

    private void initEmbeddedPreview() {
        int left = previewComponent.getX();
        int top = previewComponent.getY();
        int right = left + previewComponent.getWidth();
        int bottom = top + previewComponent.getHeight();
        preview.init(Minecraft.getMinecraft(), guiCreateWorld.width, guiCreateWorld.height, left, top, right, bottom);
        preview.setEmbeddedReturnScreen(guiCreateWorld);
        preview.setEmbeddedShowBottomControls(false);
        if (SESSION.needsViewportRestore) {
            preview.jumpToCoordinates(SESSION.centerBlockX, SESSION.centerBlockZ);
            if (SESSION.dimensionId != 0) {
                preview.selectDimensionById(SESSION.dimensionId);
            }
            SESSION.needsViewportRestore = false;
        }
        SESSION.previewInitialized = true;
    }

    private void refreshPreviewIfNeeded() {
        String seedText = resolveSeedText();
        int worldTypeIndex = access.modernWorldCreatingUI$getWorldTypeIndex();
        boolean seedChanged = !seedText.equals(SESSION.lastSeedText);
        boolean worldTypeChanged = worldTypeIndex != SESSION.lastWorldTypeIndex;
        if (!seedChanged && !worldTypeChanged) {
            return;
        }
        if (worldTypeChanged) {
            WorldType hostType = resolveWorldType(worldTypeIndex);
            if (worldTypeButton.getValue() != hostType) {
                worldTypeButton.setValue(hostType);
            }
        }
        SESSION.lastSeedText = seedText;
        SESSION.lastWorldTypeIndex = worldTypeIndex;
        updatePreviewContext(seedText, worldTypeIndex);
    }

    private void updatePreviewContext(String seedText, int worldTypeIndex) {
        long seed = parseSeed(seedText);
        WorldType worldType = resolveWorldType(worldTypeIndex);
        preview.applyContext(seed, seedText, worldType, "");
    }

    private String resolveSeedText() {
        String seedText = access.modernWorldCreatingUI$getSeed();
        if (!MathHelper.stringNullOrLengthZero(seedText)) {
            return seedText;
        }
        if (preview != null) {
            String currentSeedText = preview.getSeedText();
            if (!MathHelper.stringNullOrLengthZero(currentSeedText)) {
                return currentSeedText;
            }
        }
        return SESSION.lastSeedText;
    }

    private void openGotoScreen() {
        SESSION.centerBlockX = preview.getCenterBlockX();
        SESSION.centerBlockZ = preview.getCenterBlockZ();
        Minecraft.getMinecraft()
            .displayGuiScreen(
                new CreateWorldPreviewGotoScreen(
                    guiCreateWorld,
                    preview,
                    preview.getCenterBlockX(),
                    preview.getCenterBlockZ()));
    }

    // ==== Helpers ====

    private List<WorldType> validWorldTypes() {
        List<WorldType> result = new ArrayList<>();
        for (WorldType worldType : WorldType.worldTypes) {
            if (worldType != null) {
                result.add(worldType);
            }
        }
        return result;
    }

    private DimensionInfo currentDimensionInfo() {
        for (DimensionInfo dimension : dimensionList) {
            if (dimension.dimensionId() == SESSION.dimensionId) {
                return dimension;
            }
        }
        return dimensionList.isEmpty() ? new DimensionInfo(0, "Overworld") : dimensionList.get(0);
    }

    private static long parseSeed(String seedText) {
        if (MathHelper.stringNullOrLengthZero(seedText)) {
            return new Random().nextLong();
        }
        try {
            return Long.parseLong(seedText);
        } catch (NumberFormatException e) {
            return seedText.hashCode();
        }
    }

    private static WorldType resolveWorldType(int worldTypeIndex) {
        if (worldTypeIndex >= 0 && worldTypeIndex < WorldType.worldTypes.length) {
            WorldType worldType = WorldType.worldTypes[worldTypeIndex];
            if (worldType != null) {
                return worldType;
            }
        }
        return WorldType.DEFAULT;
    }

    // ==== Preview component ====

    /**
     * Full-area component that renders the embedded preview and forwards the
     * component-system input events to it.
     */
    private class PreviewComponent extends AbstractComponent {

        @Override
        protected void renderWidget(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
            refreshPreviewIfNeeded();
            if (preview == null) {
                return;
            }
            preview.update();
            preview.draw(mouseX, mouseY, partialTicks);
            // Persist the viewport so a tab rebuild restores the same view
            SESSION.centerBlockX = preview.getCenterBlockX();
            SESSION.centerBlockZ = preview.getCenterBlockZ();
            SESSION.dimensionId = preview.getCurrentDimensionId();
        }

        @Override
        public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
            if (preview != null) {
                preview.mouseClicked(mouseX, mouseY, mouseButton);
            }
        }

        @Override
        public void keyTyped(char typedChar, int keyCode) {
            if (preview != null) {
                preview.keyTyped(typedChar, keyCode);
            }
        }

        @Override
        public void mouseScrolled(int delta) {
            if (preview != null) {
                preview.handleScroll(delta);
            }
        }

        @Override
        public void mouseDrag(int mouseX, int mouseY, int mouseButton, long timeSinceLastClick) {
            if (preview != null) {
                preview.mouseClickMove(mouseX, mouseY, mouseButton, timeSinceLastClick);
            }
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY, int mouseButton) {
            if (preview != null) {
                preview.mouseReleased(mouseX, mouseY, mouseButton);
            }
        }
    }

    // ==== Session state ====

    /**
     * Static state shared by all tab instances of the same create-world screen
     * session. The tab factory runs on every host {@code initGui}, so without
     * this the preview (sampler threads, texture, viewport) would be rebuilt and
     * lose its position on every resize or sub-screen round-trip.
     */
    private static final class SessionState {

        private EmbeddedWorldPreview preview;
        private GuiCreateWorld hostScreen;
        private boolean previewInitialized;
        private boolean needsViewportRestore;
        private String lastSeedText = "";
        private int lastWorldTypeIndex = Integer.MIN_VALUE;
        private int centerBlockX;
        private int centerBlockZ;
        private int dimensionId;

        private EmbeddedWorldPreview obtainPreview(GuiCreateWorld screen, IGuiCreateWorldAccess access) {
            if (preview != null && hostScreen != screen) {
                // A new create-world session: retire the old preview and its sampler
                preview.close();
                preview = null;
            }
            if (preview == null) {
                String seedText = access.modernWorldCreatingUI$getSeed();
                long seed = parseSeed(seedText);
                WorldType worldType = resolveWorldType(access.modernWorldCreatingUI$getWorldTypeIndex());
                preview = new EmbeddedWorldPreview(screen, seed, seedText, worldType, "");
                preview.setEmbeddedReturnScreen(screen);
                preview.setEmbeddedShowBottomControls(false);
                hostScreen = screen;
                previewInitialized = false;
                needsViewportRestore = true;
                // Fresh session starts at the origin in the overworld
                centerBlockX = 0;
                centerBlockZ = 0;
                dimensionId = 0;
            }
            return preview;
        }
    }

    private static final SessionState SESSION = new SessionState();
}
