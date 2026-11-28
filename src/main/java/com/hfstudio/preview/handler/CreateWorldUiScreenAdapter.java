package com.hfstudio.preview.handler;

import java.lang.reflect.Field;

import net.minecraft.client.gui.GuiCreateWorld;

import cpw.mods.fml.common.Loader;

/**
 * Detects whether a create-world screen belongs to CreateWorldUI's
 * component-based tab layout.
 *
 * <p>When CreateWorldUI is present, the preview entry point is the tab
 * registered through {@code CreateWorldUiCompat} (see the
 * {@code integration.createworldui} package), so no vanilla button injection
 * happens on those screens.</p>
 */
public class CreateWorldUiScreenAdapter {

    private static final String MOD_ID = "createworldui";
    private static final String TAB_MANAGER_FIELD_NAME = "modernWorldCreatingUI$tabManager";

    public boolean isAvailable() {
        return Loader.isModLoaded(MOD_ID);
    }

    public boolean shouldHandle(GuiCreateWorld screen) {
        return isAvailable() && readField(screen, TAB_MANAGER_FIELD_NAME) != null;
    }

    private Object readField(Object target, String fieldName) {
        Class<?> currentClass = target.getClass();
        while (currentClass != null) {
            try {
                Field field = currentClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                currentClass = currentClass.getSuperclass();
            }
        }
        return null;
    }
}
