import java.io.File;
import java.util.*;

/**
 * Loads 3D scene PNG files from a sim's "3D scenes" subdirectory.
 *
 * Expected filename format:
 *   {Variable} [3D] [{Component}] {Angle}.png
 *   {Variable} [3D] [{Component}] {Angle} Filtered.png
 *
 * Example:
 *   "Pressure [3D] [FW] Back.png"
 *   "Pressure [3D] [FW] Back Filtered.png"
 *
 * Parsed into:
 *   variable  -> "Pressure"
 *   component -> "FW"
 *   angle     -> "Back"
 *   filtered  -> false / true
 *
 * The key used throughout the viewer is: "{variable} / [{component}] {angle}"
 * e.g. "Pressure / [FW] Back"
 */
public class SceneLoader3D {

    public static final String DIR_3D = "3D scenes";

    /** One entry per unique (variable, component, angle) combination. */
    public static class Scene3D {
        public final String variable;    // e.g. "Pressure"
        public final String component;   // e.g. "FW"
        public final String angle;       // e.g. "Back"
        public final File   normal;      // non-filtered image (may be null)
        public final File   filtered;    // filtered image (may be null)

        /** Display key shown in the list: "Pressure / [FW] Back" */
        public final String key;

        public Scene3D(String variable, String component, String angle, File normal, File filtered) {
            this.variable  = variable;
            this.component = component;
            this.angle     = angle;
            this.normal    = normal;
            this.filtered  = filtered;
            this.key       = variable + " / [" + component + "] " + angle;
        }
    }

    /** All discovered scenes, in a consistent sorted order. */
    public final List<Scene3D> scenes;

    /** Ordered list of unique variable names found. */
    public final List<String> variables;

    public SceneLoader3D(String simDir) {
        File dir3D = new File(simDir, DIR_3D);
        File[] pngs = dir3D.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".png"));
        if (pngs == null) pngs = new File[0];

        // Map from (variable, component, angle) -> [normal, filtered]
        Map<String, File[]> map = new LinkedHashMap<>();

        for (File f : pngs) {
            String name = f.getName();
            // Remove .png suffix
            String base = name.substring(0, name.length() - 4);

            // Expect: "{Variable} [3D] [{Component}] {Angle}" or "{Variable} [3D] [{Component}] {Angle} Filtered"
            // Find "[3D]"
            int tag3D = base.indexOf("[3D]");
            if (tag3D < 0) continue;

            String variable = base.substring(0, tag3D).trim();

            String rest = base.substring(tag3D + 4).trim(); // after "[3D]"
            // rest = "[FW] Back" or "[FW] Back Filtered"

            // Extract component: between '[' and ']'
            int cOpen  = rest.indexOf('[');
            int cClose = rest.indexOf(']');
            if (cOpen < 0 || cClose < 0 || cClose <= cOpen) continue;
            String component = rest.substring(cOpen + 1, cClose).trim();

            String afterComponent = rest.substring(cClose + 1).trim();
            // afterComponent = "Back" or "Back Filtered"

            boolean isFiltered = afterComponent.endsWith("Filtered");
            String angle = isFiltered
                    ? afterComponent.substring(0, afterComponent.length() - "Filtered".length()).trim()
                    : afterComponent;

            if (angle.isEmpty() || variable.isEmpty() || component.isEmpty()) continue;

            String mapKey = variable + "\0" + component + "\0" + angle;
            File[] pair = map.computeIfAbsent(mapKey, k -> new File[2]);
            if (isFiltered) pair[1] = f;
            else            pair[0] = f;
        }

        // Build sorted Scene3D list
        List<Scene3D> result = new ArrayList<>();
        Set<String> varsOrdered = new LinkedHashSet<>();

        // Sort keys: variable alphabetically, then component, then angle
        List<String> sortedKeys = new ArrayList<>(map.keySet());
        sortedKeys.sort((a, b) -> {
            String[] pa = a.split("\0", 3);
            String[] pb = b.split("\0", 3);
            int cv = pa[0].compareTo(pb[0]);
            if (cv != 0) return cv;
            int cc = pa[1].compareTo(pb[1]);
            if (cc != 0) return cc;
            return pa[2].compareTo(pb[2]);
        });

        for (String mk : sortedKeys) {
            String[] parts = mk.split("\0", 3);
            File[] pair = map.get(mk);
            result.add(new Scene3D(parts[0], parts[1], parts[2], pair[0], pair[1]));
            varsOrdered.add(parts[0]);
        }

        this.scenes    = Collections.unmodifiableList(result);
        this.variables = Collections.unmodifiableList(new ArrayList<>(varsOrdered));
    }

    /** Returns the Scene3D matching the given key, or null. */
    public Scene3D findByKey(String key) {
        for (Scene3D s : scenes) {
            if (s.key.equals(key)) return s;
        }
        return null;
    }
}
