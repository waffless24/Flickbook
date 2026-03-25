import java.io.File;
import java.util.*;

public class VariableScenes {
    private final List<File> sceneFiles;

    public VariableScenes(List<File> sceneFiles) {
        this.sceneFiles = sceneFiles != null ? new ArrayList<>(sceneFiles) : Collections.emptyList();
    }

    public File[] getImages(String view) {
        if (view == null || view.isEmpty()) return new File[0];

        // Find View directory
        for (File file : sceneFiles) {
            if (file != null && file.getName().contains(view) && file.isDirectory()) {
                File[] imageFiles = file.listFiles();

                if (imageFiles != null) {
                    Arrays.sort(imageFiles, Comparator.comparingInt(f -> {
                        String digits = f.getName().replaceAll("[^0-9]", "");
                        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
                    }));
                    return imageFiles;
                }
                break;
            }
        }

        return new File[0]; // If nothing found
    }

    // Not used rn, but bethod to retrieve all the specific scenefiles if ever needed in main
    public List<File> getSceneFiles() {
        return Collections.unmodifiableList(sceneFiles);
    }
}
