package fi.alavesa.cctv.monitor;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;

/**
 * Player skins for the monitor feed, as 64x64 ARGB arrays (classic 64x32 skins are widened to the 64x64 layout).
 * Skins come from the profile's {@code textures} property (Mojang, or a skin plugin such as SkinsRestorer that sets
 * it); they are downloaded once per URL off the main thread, only from textures.minecraft.net. Until a skin is in,
 * and for profiles without one (offline mode), figures use {@link #DEFAULT}, a plain placeholder person. Arrays are
 * never modified after they are published, so render threads read them freely.
 */
final class Skins {

    static final int[] DEFAULT = placeholder();

    private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"(https?://textures\\.minecraft\\.net/[^\"]+)\"");
    private static final int[] LOADING = new int[0];

    private final Map<String, int[]> byUrl = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Logger log;

    Skins(Logger log) {
        this.log = log;
    }

    /** The skin for these profile properties now: the real one once downloaded, else {@link #DEFAULT}. */
    int[] of(Collection<ProfileProperty> properties) {
        String url = url(properties);
        if (url == null) return DEFAULT;
        int[] s = byUrl.get(url);
        if (s == null) {
            byUrl.put(url, LOADING);
            fetch(url);
            return DEFAULT;
        }
        return s == LOADING ? DEFAULT : s;
    }

    /**
     * A Mannequin's skin: its profile's textures, or, for a name-only profile (e.g. summoned with
     * {@code profile:{name:"..."}}), the skin of that account, looked up once per name.
     */
    int[] of(io.papermc.paper.datacomponent.item.ResolvableProfile profile) {
        if (!profile.properties().isEmpty() || profile.name() == null || profile.name().isBlank()) {
            return of(profile.properties());
        }
        String key = "name:" + profile.name().toLowerCase(java.util.Locale.ROOT);
        int[] s = byUrl.get(key);
        if (s == null) {
            byUrl.put(key, LOADING);
            org.bukkit.Bukkit.createProfile(profile.name()).update().whenComplete((pp, err) -> {
                String url = err == null && pp != null ? url(pp.getProperties()) : null;
                if (url == null) {
                    byUrl.put(key, DEFAULT);
                    return;
                }
                byUrl.put(key, LOADING);
                fetch(url, key);
            });
            return DEFAULT;
        }
        return s == LOADING ? DEFAULT : s;
    }

    private void fetch(String url) {
        fetch(url, url);
    }

    private void fetch(String url, String key) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, err) -> {
            int[] skin = null;
            try {
                if (err == null && res.statusCode() == 200) skin = decode(res.body());
            } catch (Exception e) {
                err = e;
            }
            if (skin == null) {
                log.fine("skin " + url + " could not be loaded (" + (err != null ? err : "HTTP " + res.statusCode())
                        + "); the monitor feed uses the placeholder");
                skin = DEFAULT;
            }
            byUrl.put(url, skin);
            byUrl.put(key, skin);
        });
    }

    private static @Nullable String url(Collection<ProfileProperty> properties) {
        for (ProfileProperty p : properties) {
            if (!p.getName().equals("textures")) continue;
            try {
                String json = new String(Base64.getDecoder().decode(p.getValue()), StandardCharsets.UTF_8);
                Matcher m = URL.matcher(json);
                if (m.find()) return m.group(1);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int @Nullable [] decode(byte[] png) throws java.io.IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null || img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32)) return null;
        int[] out = new int[64 * 64];
        img.getRGB(0, 0, 64, img.getHeight(), out, 0, 64);
        if (img.getHeight() == 32) {
            // classic skin: the left arm and leg reuse the right ones
            copy(out, 40, 16, 32, 48, 16, 16);
            copy(out, 0, 16, 16, 48, 16, 16);
        }
        return out;
    }

    private static void copy(int[] px, int sx, int sy, int dx, int dy, int w, int h) {
        for (int y = 0; y < h; y++) System.arraycopy(px, (sy + y) * 64 + sx, px, (dy + y) * 64 + dx, w);
    }

    /** A plain person: brown hair, skin-tone face and hands, a dark blue shirt, grey trousers, black shoes. */
    private static int[] placeholder() {
        int[] px = new int[64 * 64];
        int skin = 0xFFC8A07E, hair = 0xFF3A2A1C, shirt = 0xFF2F4E6E, pants = 0xFF4A4C52, shoe = 0xFF1E1E22;
        box(px, 0, 0, 8, 8, 8, skin);                // head
        box(px, 16, 16, 8, 12, 4, shirt);            // body
        box(px, 40, 16, 4, 12, 4, shirt);            // right arm
        box(px, 32, 48, 4, 12, 4, shirt);            // left arm
        box(px, 0, 16, 4, 12, 4, pants);             // right leg
        box(px, 16, 48, 4, 12, 4, pants);            // left leg
        fill(px, 8, 0, 8, 8, hair);                  // head top
        fill(px, 0, 8, 32, 2, hair);                 // hairline all round
        fill(px, 24, 8, 8, 8, hair);                 // back of the head
        fill(px, 9, 12, 2, 1, 0xFFF0F0F0);           // eyes
        fill(px, 13, 12, 2, 1, 0xFFF0F0F0);
        fill(px, 10, 12, 1, 1, 0xFF34507A);
        fill(px, 13, 12, 1, 1, 0xFF34507A);
        fill(px, 40, 28, 16, 4, skin);               // hands
        fill(px, 32, 60, 16, 4, skin);
        fill(px, 0, 30, 16, 2, shoe);                // shoes
        fill(px, 16, 62, 16, 2, shoe);
        return px;
    }

    /** Fills every face of a box in the standard skin layout (u, v = texture origin; w, h, d = box size). */
    private static void box(int[] px, int u, int v, int w, int h, int d, int argb) {
        fill(px, u + d, v, 2 * w, d, argb);
        fill(px, u, v + d, 2 * d + 2 * w, h, argb);
    }

    private static void fill(int[] px, int x, int y, int w, int h, int argb) {
        for (int j = y; j < y + h; j++) for (int i = x; i < x + w; i++) px[j * 64 + i] = argb;
    }
}
