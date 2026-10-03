import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Loads a ParaView/VTK-style ".colormap" export — the JSON-like dict
 * STAR-CCM+/ParaView writes, but with single quotes instead of double
 * (see turbo.colormap) — and exposes it as:
 *
 *   - a forward function:  normalized position [0,1] -> RGB color
 *   - a fast, *approximate* reverse function: RGB color -> normalized
 *     position, used to recover the scalar value baked into an
 *     already-rendered scene PNG so two scenes can be diffed pixel-by-pixel
 *
 * File shape handled (only the first LookupTable in the file is used):
 *   {'LookupTables': [{'Name': 'turbo',
 *                       'ColorValues': [pos0,r0,g0,b0, pos1,r1,g1,b1, ...],
 *                       'AlphaValues': [...], 'ColorSpace': 0}]}
 *
 * Everything below is precomputed once at load time so that generating a
 * delta scene at runtime (DeltaSceneEngine) is just array lookups per pixel.
 */
public class ColorMap {

    private static final int FORWARD_SAMPLES = 1024;  // resolution of position -> color LUT
    private static final int REVERSE_BITS    = 5;     // 2^5 = 32 buckets per channel
    private static final int REVERSE_SIZE    = 1 << REVERSE_BITS;
    private static final int REVERSE_SHIFT   = 8 - REVERSE_BITS;
    private static final int REFINE_WINDOW   = 10;     // how far to search around the coarse guess

    /**
     * Squared RGB distance beyond which a pixel is treated as "not part of
     * this colormap" (background, geometry outline, text overlay, etc.)
     * rather than real data, and is passed through unchanged instead of
     * being diffed. Tune this if delta scenes bleed into the background or
     * eat legitimate near-edge data.
     */
    public static final int BACKGROUND_THRESHOLD_SQ = 40 * 40 * 3;

    public final String name;

    private final double[] cpPos, cpR, cpG, cpB; // control points, ascending position order
    private final int[] forwardLut;              // position sample index -> packed 0xRRGGBB
    private final int[] reverseGrid;              // quantized RGB bucket -> nearest forwardLut index

    public ColorMap(File file) throws IOException {
        String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        // The export is a Python dict repr (single-quoted), not strict JSON.
        // Our data never has literal apostrophes inside strings, so a
        // straight quote swap is safe here.
        String jsonText = raw.replace('\'', '"');

        Map<String, Object> root = MiniJson.asObject(MiniJson.parse(jsonText));
        List<Object> tables = MiniJson.asArray(root.get("LookupTables"));
        if (tables.isEmpty()) throw new IOException("No LookupTables found in " + file.getName());
        Map<String, Object> table = MiniJson.asObject(tables.get(0));

        this.name = table.containsKey("Name") ? MiniJson.asString(table.get("Name")) : file.getName();

        List<Object> flat = MiniJson.asArray(table.get("ColorValues"));
        int n = flat.size() / 4;
        cpPos = new double[n];
        cpR   = new double[n];
        cpG   = new double[n];
        cpB   = new double[n];
        for (int k = 0; k < n; k++) {
            cpPos[k] = MiniJson.asDouble(flat.get(k * 4));
            cpR[k]   = MiniJson.asDouble(flat.get(k * 4 + 1));
            cpG[k]   = MiniJson.asDouble(flat.get(k * 4 + 2));
            cpB[k]   = MiniJson.asDouble(flat.get(k * 4 + 3));
        }

        forwardLut  = buildForwardLut();
        reverseGrid = buildReverseGrid();
    }

    // ---- Forward: position [0,1] -> color ------------------------------

    private int interpolate(double t) {
        t = clamp01(t);
        int last = cpPos.length - 1;
        if (t <= cpPos[0])    return pack(cpR[0], cpG[0], cpB[0]);
        if (t >= cpPos[last]) return pack(cpR[last], cpG[last], cpB[last]);

        // Control points are stored in ascending position order in every
        // export seen so far; binary search for the bracketing segment.
        int lo = 0, hi = last;
        while (lo < hi - 1) {
            int mid = (lo + hi) / 2;
            if (cpPos[mid] <= t) lo = mid; else hi = mid;
        }
        double span = cpPos[hi] - cpPos[lo];
        double f = span <= 0 ? 0 : (t - cpPos[lo]) / span;
        double r = cpR[lo] + f * (cpR[hi] - cpR[lo]);
        double g = cpG[lo] + f * (cpG[hi] - cpG[lo]);
        double b = cpB[lo] + f * (cpB[hi] - cpB[lo]);
        return pack(r, g, b);
    }

    private int[] buildForwardLut() {
        int[] lut = new int[FORWARD_SAMPLES];
        for (int i = 0; i < FORWARD_SAMPLES; i++) {
            lut[i] = interpolate((double) i / (FORWARD_SAMPLES - 1));
        }
        return lut;
    }

    /** Opaque RGB (0xRRGGBB) for a normalized position in [0,1]. */
    public int colorAt(double position) {
        int idx = (int) Math.round(clamp01(position) * (FORWARD_SAMPLES - 1));
        return forwardLut[idx];
    }

    // ---- Reverse: color -> approximate position ------------------------

    private int[] buildReverseGrid() {
        int[] grid = new int[REVERSE_SIZE * REVERSE_SIZE * REVERSE_SIZE];
        for (int br = 0; br < REVERSE_SIZE; br++) {
            int r = (br << REVERSE_SHIFT) + (1 << (REVERSE_SHIFT - 1));
            for (int bg = 0; bg < REVERSE_SIZE; bg++) {
                int g = (bg << REVERSE_SHIFT) + (1 << (REVERSE_SHIFT - 1));
                for (int bb = 0; bb < REVERSE_SIZE; bb++) {
                    int b = (bb << REVERSE_SHIFT) + (1 << (REVERSE_SHIFT - 1));
                    grid[bucketIndex(br, bg, bb)] = nearestForwardIndexBruteForce(r, g, b);
                }
            }
        }
        return grid;
    }

    // Runs once per colormap load (32^3 buckets x 1024 samples) - a few
    // tens of milliseconds, not a per-pixel cost.
    private int nearestForwardIndexBruteForce(int r, int g, int b) {
        int best = 0, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < FORWARD_SAMPLES; i++) {
            int dist = channelDistSq(forwardLut[i], r, g, b);
            if (dist < bestDist) { bestDist = dist; best = i; }
        }
        return best;
    }

    private static int bucketIndex(int br, int bg, int bb) {
        return (br << (2 * REVERSE_BITS)) | (bg << REVERSE_BITS) | bb;
    }

    /**
     * Decodes a rendered pixel's color back into a normalized [0,1] position
     * along this colormap. Returns Double.NaN if the color doesn't
     * plausibly belong to this colormap (background, outline, text, etc.).
     *
     * O(1)-ish: a bucket lookup (bit shifts) plus a small local refine
     * against the exact forward LUT, so a full scene image decodes in a
     * few tens of milliseconds rather than doing a full LUT search per pixel.
     */
    public double reversePosition(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int br = r >> REVERSE_SHIFT, bg = g >> REVERSE_SHIFT, bb = b >> REVERSE_SHIFT;
        int guess = reverseGrid[bucketIndex(br, bg, bb)];

        int lo = Math.max(0, guess - REFINE_WINDOW);
        int hi = Math.min(FORWARD_SAMPLES - 1, guess + REFINE_WINDOW);
        int best = guess, bestDist = channelDistSq(forwardLut[guess], r, g, b);
        for (int i = lo; i <= hi; i++) {
            int dist = channelDistSq(forwardLut[i], r, g, b);
            if (dist < bestDist) { bestDist = dist; best = i; }
        }

        if (bestDist > BACKGROUND_THRESHOLD_SQ) return Double.NaN;
        return (double) best / (FORWARD_SAMPLES - 1);
    }

    private static int channelDistSq(int packed, int r, int g, int b) {
        int dr = ((packed >> 16) & 0xFF) - r;
        int dg = ((packed >> 8) & 0xFF) - g;
        int db = (packed & 0xFF) - b;
        return dr * dr + dg * dg + db * db;
    }

    private static int pack(double r, double g, double b) {
        int ri = (int) Math.round(clamp01(r) * 255);
        int gi = (int) Math.round(clamp01(g) * 255);
        int bi = (int) Math.round(clamp01(b) * 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(v, 1); }
}