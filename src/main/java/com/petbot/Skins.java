package com.petbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Downloads a player's skin by name and registers it as a texture (cached for the session). */
public final class Skins {
    private Skins() {}

    public record Loaded(Identifier id, PlayerSkinType model, String name) {}

    private static final Map<String, Loaded> CACHE = new HashMap<>();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** Callbacks run on the main thread. */
    public static void load(MinecraftClient mc, String name, Consumer<Loaded> ok, Consumer<String> fail) {
        String key = name.toLowerCase(Locale.ROOT);
        Loaded cached = CACHE.get(key);
        if (cached != null) {
            ok.accept(cached);
            return;
        }
        SkinLoader.fetch(name).whenComplete((res, err) -> mc.execute(() -> {
            if (err != null) {
                Throwable t = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
                fail.accept(String.valueOf(t.getMessage()));
                return;
            }
            try {
                Loaded again = CACHE.get(key);
                if (again != null) {
                    ok.accept(again);
                    return;
                }
                NativeImage img = NativeImage.read(new ByteArrayInputStream(res.png()));
                Identifier id = Identifier.of("petbot", "dynamic/skin_" + COUNTER.incrementAndGet());
                NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "petbot_skin", img);
                tex.upload();
                mc.getTextureManager().registerTexture(id, tex);
                Loaded loaded = new Loaded(id, res.slim() ? PlayerSkinType.SLIM : PlayerSkinType.WIDE, res.name());
                CACHE.put(key, loaded);
                CACHE.put(res.name().toLowerCase(Locale.ROOT), loaded);
                ok.accept(loaded);
            } catch (Exception e) {
                fail.accept("Couldn't read that skin: " + e.getMessage());
            }
        }));
    }
}
