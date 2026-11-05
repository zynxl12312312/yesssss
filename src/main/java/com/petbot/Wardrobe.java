package com.petbot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryOps;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Armor / held items for pets. They are only for show: the items are put on the client-side fake player,
 * nothing is ever sent to the server and nothing is added to your inventory.
 *
 * A gear "spec" is a short string: item|trimPattern|trimMaterial|glint  (e.g. minecraft:diamond_chestplate|sentry|gold|1)
 */
public final class Wardrobe {
    private Wardrobe() {}

    public static final String[] SLOT_NAMES = {"Head", "Chest", "Legs", "Feet", "Main hand", "Off hand"};
    public static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    public static final String[][] ITEMS = {
            // head
            {"minecraft:leather_helmet", "minecraft:chainmail_helmet", "minecraft:iron_helmet", "minecraft:golden_helmet",
                    "minecraft:diamond_helmet", "minecraft:netherite_helmet", "minecraft:turtle_helmet", "minecraft:carved_pumpkin",
                    "minecraft:skeleton_skull", "minecraft:zombie_head", "minecraft:creeper_head",
                    "minecraft:wither_skeleton_skull", "minecraft:piglin_head", "minecraft:dragon_head"},
            // chest
            {"minecraft:leather_chestplate", "minecraft:chainmail_chestplate", "minecraft:iron_chestplate",
                    "minecraft:golden_chestplate", "minecraft:diamond_chestplate", "minecraft:netherite_chestplate",
                    "minecraft:elytra"},
            // legs
            {"minecraft:leather_leggings", "minecraft:chainmail_leggings", "minecraft:iron_leggings",
                    "minecraft:golden_leggings", "minecraft:diamond_leggings", "minecraft:netherite_leggings"},
            // feet
            {"minecraft:leather_boots", "minecraft:chainmail_boots", "minecraft:iron_boots",
                    "minecraft:golden_boots", "minecraft:diamond_boots", "minecraft:netherite_boots"},
            // main hand
            {"minecraft:wooden_sword", "minecraft:stone_sword", "minecraft:iron_sword", "minecraft:golden_sword",
                    "minecraft:diamond_sword", "minecraft:netherite_sword", "minecraft:diamond_pickaxe",
                    "minecraft:diamond_axe", "minecraft:diamond_shovel", "minecraft:diamond_hoe", "minecraft:bow",
                    "minecraft:crossbow", "minecraft:trident", "minecraft:mace", "minecraft:fishing_rod",
                    "minecraft:stick", "minecraft:blaze_rod", "minecraft:torch", "minecraft:lantern",
                    "minecraft:golden_apple", "minecraft:apple", "minecraft:diamond", "minecraft:emerald",
                    "minecraft:nether_star", "minecraft:totem_of_undying", "minecraft:shield", "minecraft:poppy",
                    "minecraft:cake", "minecraft:bone"},
            // off hand
            {"minecraft:shield", "minecraft:totem_of_undying", "minecraft:torch", "minecraft:lantern",
                    "minecraft:golden_apple", "minecraft:apple", "minecraft:diamond", "minecraft:emerald",
                    "minecraft:nether_star", "minecraft:compass", "minecraft:clock", "minecraft:spyglass",
                    "minecraft:poppy", "minecraft:bone"}
    };

    public static final String[] PATTERNS = {
            "sentry", "dune", "coast", "wild", "ward", "eye", "vex", "tide", "snout",
            "rib", "spire", "wayfinder", "shaper", "silence", "raiser", "host", "flow", "bolt"
    };

    public static final String[] MATERIALS = {
            "quartz", "iron", "netherite", "redstone", "copper", "gold", "emerald",
            "diamond", "lapis", "amethyst", "resin"
    };

    public record Spec(String item, String pattern, String material, boolean glint) {}

    public static boolean isArmorSlot(int slot) {
        return slot >= 0 && slot < 4;
    }

    private static boolean contains(String[] arr, String s) {
        return s != null && Arrays.asList(arr).contains(s);
    }

    /** Validates a spec against the allowed items/trims. Returns null for empty or invalid specs. */
    public static Spec parse(int slot, String spec) {
        if (spec == null || spec.isBlank() || slot < 0 || slot >= ITEMS.length) return null;
        String[] p = spec.split("\\|", -1);
        String item = p[0];
        if (!contains(ITEMS[slot], item)) return null;
        String pattern = null;
        String material = null;
        if (isArmorSlot(slot) && p.length > 2) {
            String pat = p[1].toLowerCase(Locale.ROOT);
            String mat = p[2].toLowerCase(Locale.ROOT);
            if (contains(PATTERNS, pat) && contains(MATERIALS, mat)) {
                pattern = pat;
                material = mat;
            }
        }
        boolean glint = p.length > 3 && p[3].equals("1");
        return new Spec(item, pattern, material, glint);
    }

    public static String encode(Spec s) {
        if (s == null) return null;
        return s.item() + "|" + (s.pattern() == null ? "" : s.pattern()) + "|"
                + (s.material() == null ? "" : s.material()) + "|" + (s.glint() ? "1" : "0");
    }

    /** Builds the (for show only) item stack for a spec, or an empty stack. */
    public static ItemStack build(ClientWorld world, int slot, String spec) {
        Spec sp = parse(slot, spec);
        if (sp == null) return ItemStack.EMPTY;
        ItemStack stack = tryBuild(world, sp, true);
        if (stack.isEmpty() && sp.pattern() != null) {
            stack = tryBuild(world, sp, false); // trim not known to this version: show it untrimmed
        }
        return stack;
    }

    private static ItemStack tryBuild(ClientWorld world, Spec sp, boolean withTrim) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("id", sp.item());
            o.addProperty("count", 1);
            JsonObject comps = new JsonObject();
            if (withTrim && sp.pattern() != null && sp.material() != null) {
                JsonObject trim = new JsonObject();
                trim.addProperty("pattern", "minecraft:" + sp.pattern());
                trim.addProperty("material", "minecraft:" + sp.material());
                comps.add("minecraft:trim", trim);
            }
            if (sp.glint()) {
                comps.addProperty("minecraft:enchantment_glint_override", true);
            }
            if (comps.size() > 0) {
                o.add("components", comps);
            }
            RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, world.getRegistryManager());
            return ItemStack.CODEC.parse(ops, o).result().orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    public static List<String> items(int slot) {
        return Arrays.asList(ITEMS[slot]);
    }
}
