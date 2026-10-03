import java.io.File;
import java.util.*;

public class VariableScenes {
    private final List<File> sceneFiles;

    public VariableScenes(List<File> sceneFiles) {
        this.sceneFiles = sceneFiles != null ? new ArrayList<>(sceneFiles) : Collections.emptyList();
    }

    public File[] getImages(String view) {
        if (view == null || view.isEmpty()) return new File[0];

        // Find the view directory — folder name must contain the view string
        for (File file : sceneFiles) {
            if (file != null && file.getName().contains(view) && file.isDirectory()) {
                File[] imageFiles = file.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".png"));

                if (imageFiles != null && imageFiles.length > 0) {
                    // Sort by the leading numeric value in the filename (handles "140.00_..." correctly)
                    Arrays.sort(imageFiles, Comparator.comparingDouble(f -> {
                        String fname = f.getName();
                        // Extract leading number before the first underscore or non-numeric/non-dot char
                        StringBuilder sb = new StringBuilder();
                        for (char c : fname.toCharArray()) {
                            if (Character.isDigit(c) || c == '.') sb.append(c);
                            else break;
                        }
                        try { return Double.parseDouble(sb.toString()); }
                        catch (NumberFormatException e) { return 0.0; }
                    }));
                    return imageFiles;
                }
                break;
            }
        }

        return new File[0];
    }

    public List<File> getSceneFiles() {
        return Collections.unmodifiableList(sceneFiles);
    }
}