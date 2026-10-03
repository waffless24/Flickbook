import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Turns (Active PNG, Baseline PNG, variable) into an on-the-fly "delta
 * scene" BufferedImage: decode each pixel's color back to a scalar value
 * using the variable's *original* colormap + original scale (the one the
 * source scenes were actually rendered with), subtract, then re-encode the
 * difference using one shared, usually simpler *delta* colormap (e.g.
 * turbo) over the variable's delta scale. A title/tick/colorbar legend for
 * that delta colormap + scale is baked onto the bottom of the result, the
 * same way a normal STAR-CCM+ scene export carries its own legend.
 *
 * Kept fast three ways:
 *   - parsed ColorMap objects are cached per cmap file path (parsing +
 *     building the reverse-lookup grid is the expensive one-time cost)
 *   - generated delta images are cached per (variable, act file, bsl file)
 *     so scrubbing back over already-visited slices is free
 *   - the fast-path generate() overload takes already-decoded Active/
 *     Baseline images (the caller usually has them loaded anyway) so a
 *     scene PNG is never decoded twice, and the per-pixel work is split
 *     across all available CPU cores
 */
public class DeltaSceneEngine {

    private final DeltaSettings settings;
    private final Map<String, ColorMap> cmapCache = new HashMap<>();

    private static final int IMAGE_CACHE_CAPACITY = 150;
    private final LinkedHashMap<String, BufferedImage> imageCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > IMAGE_CACHE_CAPACITY;
                }
            };

    public DeltaSceneEngine(DeltaSettings settings) {
        this.settings = settings;
    }

    public DeltaSettings getSettings() { return settings; }

    /** Drops cached colormaps/images. Call after settings are edited/saved. */
    public synchronized void invalidateAll() {
        cmapCache.clear();
        imageCache.clear();
    }

    private static String cacheKey(String variable, File act, File bsl) {
        return variable + "|" + act.getAbsolutePath() + "|" + act.lastModified()
                + "|" + bsl.getAbsolutePath() + "|" + bsl.lastModified();
    }

    /** Non-blocking cache check — safe to call from the EDT before spawning a worker. */
    public synchronized BufferedImage getCached(String variable, File act, File bsl) {
        if (act == null || bsl == null) return null;
        return imageCache.get(cacheKey(variable, act, bsl));
    }

    /**
     * Convenience entry point that reads the source PNGs from disk itself.
     * Use the (variable, act, actImg, bsl, bslImg) overload instead whenever
     * the caller already has the Active/Baseline images decoded — this one
     * exists for cases (e.g. background prefetch of a slice that isn't the
     * one currently on screen) where nothing has decoded them yet.
     */
    public BufferedImage generate(String variable, File act, File bsl) throws IOException {
        if (act == null || bsl == null) return null;
        BufferedImage cached = getCached(variable, act, bsl);
        if (cached != null) return cached;

        BufferedImage actImg = ImageIO.read(act);
        BufferedImage bslImg = ImageIO.read(bsl);
        if (actImg == null || bslImg == null) {
            throw new IOException("Could not read source scene image(s) for " + variable);
        }
        return generate(variable, act, actImg, bsl, bslImg);
    }

    /**
     * Generates (or returns the cached) delta image from already-decoded
     * Active/Baseline images. Does real per-pixel work — always call this
     * off the EDT (e.g. from a SwingWorker). {@code act}/{@code bsl} are
     * used only as cache keys (path + last-modified time).
     */
    public BufferedImage generate(String variable, File act, BufferedImage actImg,
                                  File bsl, BufferedImage bslImg) throws IOException {
        if (act == null || bsl == null) return null;
        String key = cacheKey(variable, act, bsl);
        synchronized (this) {
            BufferedImage cached = imageCache.get(key);
            if (cached != null) return cached;
        }

        DeltaSettings.VariableConfig cfg = settings.get(variable);

        ColorMap origCmap = getColorMap(cfg.originalCmapPath);
        if (origCmap == null) {
            throw new IOException("No original colormap configured for \"" + variable
                    + "\" — set one in Settings > Delta Colormaps.");
        }
        ColorMap deltaCmap = getColorMap(settings.getDeltaCmapPath());
        if (deltaCmap == null) {
            throw new IOException("No delta colormap configured"
                    + " — set one in Settings > Delta Colormaps.");
        }

        if (actImg == null || bslImg == null) {
            throw new IOException("Could not read source scene image(s) for " + variable);
        }
        if (actImg.getWidth() != bslImg.getWidth() || actImg.getHeight() != bslImg.getHeight()) {
            throw new IOException("Active/Baseline scene sizes don't match for " + variable);
        }

        BufferedImage contour = renderDeltaContour(actImg, bslImg, origCmap, deltaCmap, cfg);
        BufferedImage result  = appendLegend(contour, deltaCmap, variable, cfg);

        synchronized (this) {
            imageCache.put(key, result);
        }
        return result;
    }

    private synchronized ColorMap getColorMap(String cmapPath) {
        if (cmapPath == null || cmapPath.isBlank()) return null;
        ColorMap cached = cmapCache.get(cmapPath);
        if (cached != null) return cached;
        try {
            ColorMap cmap = new ColorMap(new File(cmapPath));
            cmapCache.put(cmapPath, cmap);
            return cmap;
        } catch (IOException e) {
            System.err.println("Failed to load colormap " + cmapPath + ": " + e.getMessage());
            return null;
        }
    }

    private BufferedImage renderDeltaContour(BufferedImage actImg, BufferedImage bslImg,
                                             ColorMap origCmap, ColorMap deltaCmap,
                                             DeltaSettings.VariableConfig cfg) {
        int w = actImg.getWidth(), h = actImg.getHeight();

        // Bulk pixel access (one big int[] each) instead of per-pixel
        // getRGB/setRGB through Graphics — this is the main speed lever,
        // on top of which the row loop below is split across every
        // available CPU core (each row is independent, so this scales
        // close to linearly with core count).
        int[] actPixels = actImg.getRGB(0, 0, w, h, null, 0, w);
        int[] bslPixels = bslImg.getRGB(0, 0, w, h, null, 0, w);
        int[] outPixels = new int[w * h];

        double origSpan  = cfg.originalMax - cfg.originalMin;
        double deltaSpan = cfg.deltaMax - cfg.deltaMin;
        double origMin   = cfg.originalMin;
        double deltaMin  = cfg.deltaMin;

        IntStream.range(0, h).parallel().forEach(y -> {
            int rowStart = y * w;
            for (int x = 0; x < w; x++) {
                int i = rowStart + x;
                int actRgb = actPixels[i];
                int bslRgb = bslPixels[i];

                double actPos = origCmap.reversePosition(actRgb);
                double bslPos = origCmap.reversePosition(bslRgb);

                if (Double.isNaN(actPos) || Double.isNaN(bslPos) || origSpan == 0) {
                    // Not a data pixel (background/geometry outline/text) —
                    // pass the active scene's pixel through so the car/mesh
                    // outline still reads correctly on the delta scene.
                    outPixels[i] = actRgb;
                    continue;
                }

                double actVal = origMin + actPos * origSpan;
                double bslVal = origMin + bslPos * origSpan;
                double delta  = actVal - bslVal;

                double deltaPos = deltaSpan == 0 ? 0.5 : (delta - deltaMin) / deltaSpan;
                deltaPos = Math.max(0.0, Math.min(1.0, deltaPos));

                outPixels[i] = 0xFF000000 | deltaCmap.colorAt(deltaPos);
            }
        });

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, w, h, outPixels, 0, w);
        return result;
    }

    // ---- Legend (title + tick labels + colorbar), matching the look of a
    //      normal STAR-CCM+ scene export's legend strip -------------------

    private static final int TICK_COUNT = 7;

    private BufferedImage appendLegend(BufferedImage contour, ColorMap deltaCmap,
                                       String variable, DeltaSettings.VariableConfig cfg) {
        int w = contour.getWidth();
        int contourH = contour.getHeight();
        int legendH = Math.max(90, (int) Math.round(contourH * 0.10));
        int totalH = contourH + legendH;

        BufferedImage out = new BufferedImage(w, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.drawImage(contour, 0, 0, null);

        g.setColor(Color.WHITE);
        g.fillRect(0, contourH, w, legendH);

        String title = "Delta of " + variable;
        Font titleFont = new Font("Source Sans Pro", Font.PLAIN, Math.max(14, legendH / 5));
        g.setFont(titleFont);
        g.setColor(Color.BLACK);
        FontMetrics tfm = g.getFontMetrics();
        int titleY = contourH + (int) Math.round(legendH * 0.34);
        g.drawString(title, (w - tfm.stringWidth(title)) / 2, titleY);

        int marginX = (int) Math.round(w * 0.12);
        int barX = marginX;
        int barW = Math.max(1, w - 2 * marginX);
        int barY = contourH + (int) Math.round(legendH * 0.60);
        int barH = Math.max(6, (int) Math.round(legendH * 0.24));

        Font tickFont = new Font("Source Sans Pro", Font.PLAIN, Math.max(11, legendH / 8));
        g.setFont(tickFont);
        FontMetrics tkfm = g.getFontMetrics();
        int tickLabelY = barY - 6;
        for (int i = 0; i < TICK_COUNT; i++) {
            double frac = (double) i / (TICK_COUNT - 1);
            double value = cfg.deltaMin + frac * (cfg.deltaMax - cfg.deltaMin);
            String label = formatTick(value, i == 0, i == TICK_COUNT - 1);
            int tx = barX + (int) Math.round(frac * barW);
            int labelW = tkfm.stringWidth(label);
            int drawX = Math.max(0, Math.min(w - labelW, tx - labelW / 2));
            g.drawString(label, drawX, tickLabelY);
        }

        for (int px = 0; px < barW; px++) {
            double frac = (double) px / Math.max(1, barW - 1);
            g.setColor(new Color(deltaCmap.colorAt(frac)));
            g.drawLine(barX + px, barY, barX + px, barY + barH);
        }
        g.setColor(Color.DARK_GRAY);
        g.drawRect(barX, barY, barW, barH);

        g.dispose();
        return out;
    }

    private static String formatTick(double value, boolean isFirst, boolean isLast) {
        String num = trimTickNumber(value);
        if (isFirst) return "< " + num;
        if (isLast)  return "> " + num;
        return num;
    }

    private static String trimTickNumber(double v) {
        String s = String.format("%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.equals("-0")) s = "0";
        return s;
    }
}