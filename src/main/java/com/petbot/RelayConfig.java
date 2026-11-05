package com.petbot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Address of the (optional) relay used to see other players' pets. Stored in config/petbot.json.
 * Put your relay address in DEFAULT_RELAY before building if you want friends to get it without typing /petrelay.
 */
public final class RelayConfig {
    private RelayConfig() {}

    public static final String DEFAULT_RELAY = "";

    public static volatile String relay = DEFAULT_RELAY;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("petbot.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                if (o.has("relay")) relay = o.get("relay").getAsString();
            }
        } catch (Exception ignored) {
            // bad config: keep the default
        }
    }

    private static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("relay", relay);
            Files.writeString(file(), o.toString());
        } catch (Exception ignored) {
            // can't write the config: the setting just won't survive a restart
        }
    }

    /** Returns false if the address isn't a valid http(s) URL. "off" or empty clears it. */
    public static boolean set(String input) {
        String u = input.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty() || u.equalsIgnoreCase("off")) {
            relay = "";
            save();
            return true;
        }
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        try {
            URI.create(u);
        } catch (Exception e) {
            return false;
        }
        relay = u;
        save();
        return true;
    }
}
