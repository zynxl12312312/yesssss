package com.petbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /petwardrobe : pick armor, trims, glint and held items for your pets (for show only). */
public class WardrobeScreen extends Screen {
    private int sel = 0;
    private boolean applyAll = false;
    private final ItemStack[] icons = new ItemStack[6];

    /** Is Shift held? (Screen.hasShiftDown() was removed in 1.21.9+, so ask GLFW directly.) */
    private static boolean shiftDown() {
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    public WardrobeScreen() {
        super(Text.literal("Pet wardrobe (for show only)"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static String pretty(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private List<PetData> targets() {
        List<PetData> t = new ArrayList<>();
        if (Pets.LIST.isEmpty()) return t;
        if (applyAll) t.addAll(Pets.LIST);
        else t.add(Pets.LIST.get(sel));
        return t;
    }

    private void setSlot(int slot, Wardrobe.Spec spec) {
        String enc = Wardrobe.encode(spec);
        for (PetData d : targets()) {
            d.setGear(slot, enc);
        }
        PetSync.markDirty();
        this.clearAndInit();
    }

    @Override
    protected void init() {
        int n = Pets.LIST.size();
        if (n == 0) {
            this.close();
            return;
        }
        if (sel >= n) sel = n - 1;
        PetData pet = Pets.LIST.get(sel);

        int cx = this.width / 2;
        int x0 = cx - 203;

        // pet selector
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
            sel = (sel + Pets.LIST.size() - 1) % Pets.LIST.size();
            this.clearAndInit();
        }).dimensions(x0, 22, 20, 20).build());
        ButtonWidget label = ButtonWidget.builder(Text.literal("Pet #" + (sel + 1) + " of " + n + "  -  " + pet.nameRaw), b -> { })
                .dimensions(x0 + 24, 22, 358, 20).build();
        label.active = false;
        addDrawableChild(label);
        addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
            sel = (sel + 1) % Pets.LIST.size();
            this.clearAndInit();
        }).dimensions(x0 + 386, 22, 20, 20).build());

        // apply to this pet / all pets, clear gear
        addDrawableChild(ButtonWidget.builder(Text.literal("Edit: " + (applyAll ? "ALL pets" : "this pet")), b -> {
            applyAll = !applyAll;
            this.clearAndInit();
        }).dimensions(x0, 46, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Copy this pet's gear to all"), b -> {
            for (PetData d : Pets.LIST) {
                if (d == pet) continue;
                for (int i = 0; i < 6; i++) d.setGear(i, pet.gear[i]);
            }
            PetSync.markDirty();
        }).dimensions(x0 + 154, 46, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear gear"), b -> {
            for (PetData d : targets()) {
                for (int i = 0; i < 6; i++) d.setGear(i, null);
            }
            PetSync.markDirty();
            this.clearAndInit();
        }).dimensions(x0 + 308, 46, 98, 20).build());

        // one row per slot
        MinecraftClient mc = MinecraftClient.getInstance();
        for (int s = 0; s < 6; s++) {
            final int slot = s;
            int y = 76 + s * 26;
            Wardrobe.Spec spec = Wardrobe.parse(slot, pet.gear[slot]);
            icons[slot] = mc.world == null ? ItemStack.EMPTY : Wardrobe.build(mc.world, slot, pet.gear[slot]);

            // item (click = next, shift-click = previous)
            Text itemText = icons[slot].isEmpty() ? Text.literal("None") : icons[slot].getName();
            addDrawableChild(ButtonWidget.builder(itemText, b -> {
                int dir = shiftDown() ? -1 : 1;
                String[] list = Wardrobe.ITEMS[slot];
                int idx = -1;
                if (spec != null) {
                    for (int i = 0; i < list.length; i++) if (list[i].equals(spec.item())) idx = i;
                }
                int next = idx + dir;
                if (next < -1) next = list.length - 1;
                if (next >= list.length) next = -1;
                if (next == -1) {
                    setSlot(slot, null);
                } else {
                    setSlot(slot, new Wardrobe.Spec(list[next],
                            spec == null ? null : spec.pattern(), spec == null ? null : spec.material(),
                            spec != null && spec.glint()));
                }
            }).dimensions(x0 + 80, y, 120, 20).build());

            if (spec != null) {
                // enchant glint
                addDrawableChild(ButtonWidget.builder(Text.literal(spec.glint() ? "Glint: on" : "Glint: off"), b ->
                        setSlot(slot, new Wardrobe.Spec(spec.item(), spec.pattern(), spec.material(), !spec.glint()))
                ).dimensions(x0 + 362, y, 44, 20).build());

                if (Wardrobe.isArmorSlot(slot)) {
                    // trim pattern
                    addDrawableChild(ButtonWidget.builder(
                            Text.literal(spec.pattern() == null ? "Trim: none" : pretty(spec.pattern())), b -> {
                                int dir = shiftDown() ? -1 : 1;
                                int idx = -1;
                                for (int i = 0; i < Wardrobe.PATTERNS.length; i++) {
                                    if (Wardrobe.PATTERNS[i].equals(spec.pattern())) idx = i;
                                }
                                int next = idx + dir;
                                if (next < -1) next = Wardrobe.PATTERNS.length - 1;
                                if (next >= Wardrobe.PATTERNS.length) next = -1;
                                if (next == -1) {
                                    setSlot(slot, new Wardrobe.Spec(spec.item(), null, null, spec.glint()));
                                } else {
                                    String mat = spec.material() == null ? "gold" : spec.material();
                                    setSlot(slot, new Wardrobe.Spec(spec.item(), Wardrobe.PATTERNS[next], mat, spec.glint()));
                                }
                            }).dimensions(x0 + 204, y, 80, 20).build());

                    // trim material (only once a pattern is picked)
                    ButtonWidget mat = ButtonWidget.builder(
                            Text.literal(spec.material() == null ? "-" : pretty(spec.material())), b -> {
                                int dir = shiftDown() ? -1 : 1;
                                int idx = 0;
                                for (int i = 0; i < Wardrobe.MATERIALS.length; i++) {
                                    if (Wardrobe.MATERIALS[i].equals(spec.material())) idx = i;
                                }
                                int next = (idx + dir + Wardrobe.MATERIALS.length) % Wardrobe.MATERIALS.length;
                                setSlot(slot, new Wardrobe.Spec(spec.item(), spec.pattern(), Wardrobe.MATERIALS[next], spec.glint()));
                            }).dimensions(x0 + 288, y, 70, 20).build();
                    mat.active = spec.pattern() != null;
                    addDrawableChild(mat);
                }
            }
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx - 50, 76 + 6 * 26 + 8, 100, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int x0 = cx - 203;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 8, 0xFFFFFFFF);
        for (int s = 0; s < 6; s++) {
            int y = 76 + s * 26;
            context.drawTextWithShadow(this.textRenderer, Text.literal(Wardrobe.SLOT_NAMES[s]), x0, y + 6, 0xFFFFFFFF);
            if (icons[s] != null && !icons[s].isEmpty()) {
                context.drawItem(icons[s], x0 + 60, y + 2);
            }
        }
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Click = next, Shift+click = previous. Trims need a pattern first."),
                cx, 76 + 6 * 26 + 34, 0xFFAAAAAA);
    }
}
