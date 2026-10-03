import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delta-generation configuration: one global "delta" .colormap (e.g. turbo)
 * shared by every variable to display the difference, plus, per variable,
 * an "original" .colormap file (the one the Active/Baseline scenes were
 * actually rendered with — needed to decode their pixel colors back into
 * scalar values), the scale it was rendered over, and the delta scale to
 * re-encode + display that variable's difference with. Edited via
 * DeltaSettingsDialog and persisted to a small JSON file in the user's home
 * directory so it survives restarts and isn't tied to any particular sim
 * directory.
 */
public class DeltaSettings {

    public static class VariableConfig {
        public String originalCmapPath = "";
        public double originalMin = 0.0;
        public double originalMax = 1.0;
        public double deltaMin    = -0.1;
        public double deltaMax    = 0.1;
    }

    private static final File FILE =
            new File(System.getProperty("user.home"), ".flickbook_delta_settings.json");

    private String deltaCmapPath = ""; // shared across all variables

    private final Map<String, VariableConfig> configs = new LinkedHashMap<>();

    public String getDeltaCmapPath() { return deltaCmapPath; }
    public void setDeltaCmapPath(String path) { this.deltaCmapPath = path; }

    public VariableConfig get(String variable) {
        return configs.computeIfAbsent(variable, k -> new VariableConfig());
    }

    public void set(String variable, VariableConfig config) {
        configs.put(variable, config);
    }

    public static DeltaSettings load() {
        DeltaSettings settings = new DeltaSettings();
        if (!FILE.exists()) return settings;
        try {
            String text = new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = MiniJson.asObject(MiniJson.parse(text));

            settings.deltaCmapPath = root.containsKey("deltaCmapPath") ? MiniJson.asString(root.get("deltaCmapPath")) : "";

            if (root.containsKey("variables")) {
                Map<String, Object> variables = MiniJson.asObject(root.get("variables"));
                for (Map.Entry<String, Object> e : variables.entrySet()) {
                    Map<String, Object> obj = MiniJson.asObject(e.getValue());
                    VariableConfig cfg = new VariableConfig();
                    cfg.originalCmapPath = obj.containsKey("originalCmapPath") ? MiniJson.asString(obj.get("originalCmapPath")) : "";
                    cfg.originalMin = obj.containsKey("originalMin") ? MiniJson.asDouble(obj.get("originalMin")) : 0.0;
                    cfg.originalMax = obj.containsKey("originalMax") ? MiniJson.asDouble(obj.get("originalMax")) : 1.0;
                    cfg.deltaMin    = obj.containsKey("deltaMin")    ? MiniJson.asDouble(obj.get("deltaMin"))    : -0.1;
                    cfg.deltaMax    = obj.containsKey("deltaMax")    ? MiniJson.asDouble(obj.get("deltaMax"))    : 0.1;
                    settings.configs.put(e.getKey(), cfg);
                }
            }
        } catch (Exception ex) {
            System.err.println("Could not read delta settings, starting fresh: " + ex.getMessage());
        }
        return settings;
    }

    public void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("deltaCmapPath", deltaCmapPath);

        Map<String, Object> variables = new LinkedHashMap<>();
        for (Map.Entry<String, VariableConfig> e : configs.entrySet()) {
            VariableConfig cfg = e.getValue();
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("originalCmapPath", cfg.originalCmapPath);
            obj.put("originalMin", cfg.originalMin);
            obj.put("originalMax", cfg.originalMax);
            obj.put("deltaMin", cfg.deltaMin);
            obj.put("deltaMax", cfg.deltaMax);
            variables.put(e.getKey(), obj);
        }
        root.put("variables", variables);

        try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
            w.write(MiniJson.write(root));
        } catch (IOException ex) {
            System.err.println("Could not save delta settings: " + ex.getMessage());
        }
    }
}