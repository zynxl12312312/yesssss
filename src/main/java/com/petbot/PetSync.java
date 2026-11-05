package com.petbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lets Petbot users see each other's pets WITHOUT any server mod: your pet settings are uploaded to a small
 * relay (see the relay/ folder) under your UUID, and the settings of nearby players are downloaded from it.
 * Nothing is uploaded unless a relay address is set (/petrelay), and you only download when /petvisible is on.
 */
public final class PetSync {
    private PetSync() {}

    public record Entry(String skin, String name, boolean hideName, boolean stopped, boolean jump,
                        boolean noJump, boolean circle, String formation, double range, String[] gear) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Pattern SKIN_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static boolean dirty = true;
    private static boolean published = false;
    private static int publishTimer = 0;
    private static int fetchTimer = 0;
    private static volatile boolean fetching = false;

    /** Call whenever one of your pets changes. */
    public static void markDirty() {
        dirty = true;
    }

    public static boolean enabled() {
        return !RelayConfig.relay.isEmpty();
    }

    public static void tick(MinecraftClient mc) {
        if (!enabled() || mc.player == null || mc.world == null) return;
        publishTimer++;
        fetchTimer++;

        boolean has = !Pets.LIST.isEmpty();
        if ((dirty && publishTimer >= 20) || publishTimer >= 400) { // after changes, and every 20s as a keep-alive
            publishTimer = 0;
            dirty = false;
            if (has || published) {
                publish(mc);
                published = has;
            }
        }

        if (Pets.visibleOthers && fetchTimer >= 60 && !fetching) { // every 3s
            fetchTimer = 0;
            fetch(mc);
        }
    }

    private static void publish(MinecraftClient mc) {
        try {
            String url = RelayConfig.relay + "/pets/" + mc.player.getUuid();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(encode()))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // relay unreachable: try again next time
        }
    }

    private static void fetch(MinecraftClient mc) {
        try {
            UUID self = mc.player.getUuid();
            List<String> ids = new ArrayList<>();
            for (PlayerEntity pl : mc.world.getPlayers()) {
                if (pl instanceof PetPlayer) continue; // our own fake players
                if (pl.getUuid().equals(self)) continue;
                if (ids.size() >= 100) break;
                ids.add(pl.getUuid().toString());
            }
            if (ids.isEmpty()) {
                for (UUID u : new ArrayList<>(Pets.REMOTE.keySet())) {
                    Pets.removeOwner(mc, u);
                }
                return;
            }
            fetching = true;
            String url = RelayConfig.relay + "/pets?ids=" + String.join(",", ids);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> mc.execute(() -> {
                fetching = false;
                if (err != null || res == null || res.statusCode() != 200) return;
                try {
                    JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
                    for (String id : ids) {
                        UUID u = UUID.fromString(id);
                        if (root.has(id)) {
                            Pets.updateRemote(mc, u, decode(root.getAsJsonObject(id)));
                        } else {
                            Pets.removeOwner(mc, u);
                        }
                    }
                } catch (Exception ignored) {
                    // malformed answer: ignore it
                }
            }));
        } catch (Exception e) {
            fetching = false;
        }
    }

    private static String encode() {
        JsonArray arr = new JsonArray();
        for (PetData d : Pets.LIST) {
            JsonObject o = new JsonObject();
            if (d.skinName != null) o.addProperty("skin", d.skinName);
            o.addProperty("name", d.nameRaw);
            o.addProperty("hideName", d.hideName);
            o.addProperty("stopped", d.stopped);
            o.addProperty("jump", d.jumpAlways);
            o.addProperty("noJump", d.noJump);
            o.addProperty("circle", d.circle);
            if (d.formation != null) o.addProperty("formation", d.formation);
            o.addProperty("range", d.range);
            JsonArray gear = new JsonArray();
            for (int i = 0; i < 6; i++) gear.add(d.gear[i] == null ? "" : d.gear[i]);
            o.add("gear", gear);
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("pets", arr);
        return root.toString();
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }

    /** Parses (and sanitizes) another player's pets. */
    private static List<Entry> decode(JsonObject root) {
        List<Entry> out = new ArrayList<>();
        JsonArray arr = root.getAsJsonArray("pets");
        for (JsonElement el : arr) {
            if (out.size() >= Pets.MAX) break;
            JsonObject o = el.getAsJsonObject();
            String skin = o.has("skin") ? o.get("skin").getAsString() : null;
            if (skin != null && !SKIN_NAME.matcher(skin).matches()) skin = null;
            String name = o.has("name") ? o.get("name").getAsString() : PetData.DEFAULT_NAME;
            if (name.length() > 48) name = name.substring(0, 48);
            double range = o.has("range") ? o.get("range").getAsDouble() : 1.5;
            range = MathHelper.clamp(range, 0.5, 10.0);

            String[] gear = new String[6];
            if (o.has("gear")) {
                JsonArray g = o.getAsJsonArray("gear");
                for (int i = 0; i < 6 && i < g.size(); i++) {
                    gear[i] = Wardrobe.encode(Wardrobe.parse(i, g.get(i).getAsString()));
                }
            }
            String formation = o.has("formation") ? o.get("formation").getAsString() : null;
            if (Formation.byName(formation) == null) formation = null;
            out.add(new Entry(skin, name, bool(o, "hideName"), bool(o, "stopped"), bool(o, "jump"),
                    bool(o, "noJump"), bool(o, "circle"), formation, range, gear));
        }
        return out;
    }
}
