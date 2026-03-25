import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class SceneLoader {

    public static final String EXTENSION = ".png";
    public static final String SCENES_2D = "2D scenes";
    public static final String[] VARIABLES = {
            "Inwash", "Pressure", "Total Pressure", "VISQ", "Upwash", "Helicity"
    };

    public final VariableScenes inwashScenes;
    public final VariableScenes pressureScenes;
    public final VariableScenes cptScenes;
    public final VariableScenes vorticityScenes;
    public final VariableScenes velZScenes;
    public final VariableScenes helicityScenes;
    public final String dir;

    public SceneLoader(String dir) {
        this.dir = dir;

        File scenesRoot = new File(dir, SCENES_2D);
        File[] allScenes = scenesRoot.listFiles(File::isDirectory);
        if (allScenes == null) {
            allScenes = new File[0];
        }

        // Load variable-specific scene folders
        List<File> inwash = getVariableScenes("Inwash", allScenes);
        List<File> pressure = getVariableScenes("Pressure", allScenes);
        List<File> totalPressure = getVariableScenes("Total Pressure", allScenes);
        List<File> vorticity = getVariableScenes("VISQ", allScenes);
        List<File> velZ = getVariableScenes("Upwash", allScenes);
        List<File> helicity = getVariableScenes("Helicity", allScenes);

        // Final Variable scene instances to refer back to in main loop
        this.inwashScenes = new VariableScenes(inwash);
        this.pressureScenes = new VariableScenes(pressure);
        this.cptScenes = new VariableScenes(totalPressure);
        this.vorticityScenes = new VariableScenes(vorticity);
        this.velZScenes = new VariableScenes(velZ);
        this.helicityScenes = new VariableScenes(helicity);
    }

    /**
     * Filters the given directories to match the variable name.
     */
    private List<File> getVariableScenes(String variable, File[] allScenes) {
        List<File> result = new ArrayList<>();
        if (allScenes == null || variable == null) return result;

        for (File scene : allScenes) {
            if (scene == null || !scene.isDirectory()) continue;
            String name = scene.getName();

            // Ensure Presure and Total Pressure does not overlap
            if ("Pressure".equals(variable)) {
                if (name.contains("Pressure") && !name.contains("Total Pressure")) {
                    result.add(scene);
                }
            } else if (name.contains(variable)) {
                result.add(scene);
            }
        }

        return result;
    }
}
