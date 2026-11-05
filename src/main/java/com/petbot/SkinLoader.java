package com.petbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/** Looks up a player's skin by username straight from Mojang's public APIs (off the main thread). */
public final class SkinLoader {
    private SkinLoader() {}

    public record Result(byte[] png, boolean slim, String name) {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static CompletableFuture<Result> fetch(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchBlocking(name);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Couldn't reach Mojang: " + e.getMessage(), e);
            }
        });
    }

    private static HttpResponse<byte[]> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "petbot-mod")
                .GET()
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Result fetchBlocking(String name) throws Exception {
        // 1) name -> uuid
        HttpResponse<byte[]> r1 = get("https://api.mojang.com/users/profiles/minecraft/" + name);
        if (r1.statusCode() == 404 || r1.statusCode() == 204) {
            throw new IllegalStateException("No player named " + name + " exists.");
        }
        if (r1.statusCode() == 429) {
            throw new IllegalStateException("Mojang is rate limiting skin lookups, try again in a minute.");
        }
        if (r1.statusCode() != 200) {
            throw new IllegalStateException("Mojang profile lookup failed (HTTP " + r1.statusCode() + ").");
        }
        JsonObject prof = JsonParser.parseString(new String(r1.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        String id = prof.get("id").getAsString();
        String canonical = prof.has("name") ? prof.get("name").getAsString() : name;

        // 2) uuid -> textures property
        HttpResponse<byte[]> r2 = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
        if (r2.statusCode() == 429) {
            throw new IllegalStateException("Mojang is rate limiting skin lookups, try again in a minute.");
        }
        if (r2.statusCode() != 200) {
            throw new IllegalStateException("Mojang session lookup failed (HTTP " + r2.statusCode() + ").");
        }
        JsonObject session = JsonParser.parseString(new String(r2.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        String value = null;
        JsonArray props = session.getAsJsonArray("properties");
        if (props != null) {
            for (JsonElement el : props) {
                JsonObject o = el.getAsJsonObject();
                if ("textures".equals(o.get("name").getAsString())) {
                    value = o.get("value").getAsString();
                }
            }
        }
        if (value == null) {
            throw new IllegalStateException(canonical + " has no skin data.");
        }

        // 3) textures json -> skin url + model
        JsonObject tex = JsonParser.parseString(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("textures");
        if (tex == null || !tex.has("SKIN")) {
            throw new IllegalStateException(canonical + " uses a default skin (no custom skin to copy).");
        }
        JsonObject skin = tex.getAsJsonObject("SKIN");
        String url = skin.get("url").getAsString().replace("http://", "https://");
        boolean slim = skin.has("metadata")
                && skin.getAsJsonObject("metadata").has("model")
                && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());

        // 4) download the png
        HttpResponse<byte[]> r3 = get(url);
        if (r3.statusCode() != 200) {
            throw new IllegalStateException("Skin download failed (HTTP " + r3.statusCode() + ").");
        }
        byte[] png = r3.body();
        if (pngHeight(png) == 32) {
            png = convertLegacy(png);
        }
        return new Result(png, slim, canonical);
    }

    private static int pngHeight(byte[] b) {
        if (b.length < 24) return 0;
        return ((b[20] & 255) << 24) | ((b[21] & 255) << 16) | ((b[22] & 255) << 8) | (b[23] & 255);
    }

    /** Old 64x32 skins -> 64x64 (mirrors the right arm/leg onto the left ones, like vanilla does). */
    private static byte[] convertLegacy(byte[] png) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
        BufferedImage dst = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();

        // {srcX, srcY, translateX, translateY, width, height}, each mirrored horizontally
        int[][] ops = {
                {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4},
                {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12}, {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12},
                {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
                {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12}
        };
        for (int[] op : ops) {
            int sx = op[0], sy = op[1], tx = op[2], ty = op[3], w = op[4], h = op[5];
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    dst.setRGB(sx + tx + (w - 1 - i), sy + ty + j, dst.getRGB(sx + i, sy + j));
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(dst, "png", out);
        return out.toByteArray();
    }
}
