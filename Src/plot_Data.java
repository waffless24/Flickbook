import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses a STAR-CCM+ exported .txt file into typed series.
 *
 * File types detected automatically:
 *   MONITOR   – col0 = "Iteration", filename contains "Monitor"
 *   RESIDUALS – col0 = "Iteration", filename = "Residuals"
 *   HISTOGRAM – col0 contains "Min Extent" (Wall Y+)
 *   XY        – everything else (spanwise, lengthwise)
 */
public class PlotData {

    public enum PlotType { MONITOR, RESIDUALS, HISTOGRAM, XY }

    public static class Series {
        public final String name;      // clean display name
        public final double[] x;
        public final double[] y;
        public final boolean isAvg;    // true if this is an "Avg Monitor" series

        public Series(String name, double[] x, double[] y, boolean isAvg) {
            this.name  = name;
            this.x     = x;
            this.y     = y;
            this.isAvg = isAvg;
        }
    }

    public final String      filename;
    public final PlotType    type;
    public final String      xLabel;
    public final String      yLabel;
    public final List<Series> series = new ArrayList<>();

    public PlotData(File file) throws IOException {
        this.filename = file.getName();
        List<String[]> rows = readCsv(file);
        if (rows.size() < 2) {
            type   = PlotType.XY;
            xLabel = "";
            yLabel = "";
            return;
        }

        String[] header  = rows.get(0);
        String   col0raw = strip(header[0]);

        // --- Classify ---
        if (col0raw.equals("Iteration") && filename.contains("Residuals")) {
            type = PlotType.RESIDUALS;
        } else if (col0raw.equals("Iteration")) {
            type = PlotType.MONITOR;
        } else if (col0raw.contains("Min Extent")) {
            type = PlotType.HISTOGRAM;
        } else {
            type = PlotType.XY;
        }

        // --- Extract x column ---
        double[] x = new double[rows.size() - 1];
        for (int i = 1; i < rows.size(); i++) {
            x[i - 1] = parseDouble(rows.get(i)[0]);
        }

        this.xLabel = cleanLabel(col0raw);

        // --- Build series for each remaining column ---
        // For y-axis label use the first data column (strip sim name prefix)
        String firstColName = header.length > 1 ? cleanLabel(strip(header[1])) : "";
        this.yLabel = firstColName;

        for (int col = 1; col < header.length; col++) {
            String rawName = strip(header[col]);
            boolean isVariance = rawName.toLowerCase().contains("variance");
            // For variance columns, rename to Std Dev and take sqrt of values
            String displayName = isVariance
                    ? cleanLabel(rawName).replace("Variance", "Std Dev").replace("variance", "Std Dev")
                    : cleanLabel(rawName);
            boolean isAvg  = rawName.contains("Avg");

            double[] y = new double[rows.size() - 1];
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);
                double val = col < row.length ? parseDouble(row[col]) : Double.NaN;
                y[i - 1] = (isVariance && !Double.isNaN(val) && val >= 0) ? Math.sqrt(val) : val;
            }
            series.add(new Series(displayName, x, y, isAvg));
        }
    }

    // -------------------------------------------------------------------------

    private List<String[]> readCsv(File file) throws IOException {
        List<String[]> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                result.add(splitCsv(line));
            }
        }
        return result;
    }

    private String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQ = false;
        for (char c : line.toCharArray()) {
            if (c == '"') { inQ = !inQ; }
            else if (c == ',' && !inQ) { fields.add(sb.toString()); sb.setLength(0); }
            else { sb.append(c); }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    private String strip(String s) {
        return s == null ? "" : s.trim().replace("\"", "");
    }

    /**
     * Cleans a raw STAR-CCM+ column header for display:
     *  "UT Lift Avg Monitor: UT Lift Avg Monitor" -> "UT Lift Avg Monitor"
     *  "Wall Y+ Aero: Wall Y+ - Min Extent"       -> "Wall Y+"
     */
    private String cleanLabel(String raw) {
        // Remove duplicate "X: X" pattern — keep left side
        int colon = raw.indexOf(':');
        if (colon > 0) {
            String left  = raw.substring(0, colon).trim();
            String right = raw.substring(colon + 1).trim();
            // If right starts with left, just use left
            if (right.startsWith(left) || left.length() < right.length()) {
                raw = left;
            }
        }
        // Strip trailing units like "(kg/s)"
        raw = raw.replaceAll("\\s*\\(.*?\\)\\s*$", "").trim();
        return raw;
    }

    private double parseDouble(String s) {
        if (s == null || s.isBlank()) return Double.NaN;
        s = s.trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}