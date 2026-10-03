import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Plots viewer — all three sims compared on shared charts.
 *
 * MONITOR:   Two stacked charts: top = Raw values (all 3 sims), bottom = Avg values (all 3 sims).
 * RESIDUALS: Three side-by-side charts, one per sim (each with all residual types).
 * HISTOGRAM: Grouped bar chart, one colour per sim.
 * XY:        Single overlaid line chart, one colour per sim.
 */
public class PlotsViewer extends JDialog {

    private static final String PLOTS_DIR = "Plots";

    // Per-sim raw colours  — black / red / gold
    private static final Color C_ACT   = new Color(0x1A, 0x1A, 0x1A);  // sorta black
    private static final Color C_BSL   = new Color(0xCC, 0x11, 0x11);  // red
    private static final Color C_DELTA = new Color(0xCF, 0xA1, 0x00);  // gold

    // Per-sim Avg (lighter) colours — used for the "Average" monitor sub-chart
    private static final Color C_ACT_DARK   = new Color(0x55, 0x55, 0x55);  // mid grey
    private static final Color C_BSL_DARK   = new Color(0x88, 0x00, 0x00);  // dark red
    private static final Color C_DELTA_DARK = new Color(0x8B, 0x6A, 0x00);  // dark gold

    private static final Color[] SIM_RAW  = {C_ACT, C_BSL, C_DELTA};
    private static final Color[] SIM_DARK = {C_ACT_DARK, C_BSL_DARK, C_DELTA_DARK};

    private final String[] simNames;
    private final String[] simPaths;

    private final List<String> plotFiles     = new ArrayList<>();
    private final List<String> residualFiles = new ArrayList<>();

    // Swappable content area — replaced each time a new file is selected
    private final JPanel contentArea = new JPanel(new BorderLayout());

    public PlotsViewer(Frame owner,
                       String actName,   String actPath,
                       String bslName,   String bslPath,
                       String deltaName, String deltaPath) {
        super(owner, "Plots — All Sims Comparison", false);
        setSize(1600, 900);
        setLocationRelativeTo(owner);

        simNames = new String[]{actName, bslName, deltaName};
        simPaths = new String[]{actPath, bslPath, deltaPath};

        discoverFiles();

        // File list
        DefaultListModel<String> listModel = new DefaultListModel<>();
        plotFiles.forEach(listModel::addElement);
        if (!residualFiles.isEmpty()) {
            listModel.addElement("─── Residuals ───");
            residualFiles.forEach(listModel::addElement);
        }

        JList<String> fileList = new JList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
        fileList.setBackground(new Color(245, 245, 245));
        fileList.setFixedCellHeight(24);
        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int idx, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, idx, sel, focus);
                String name = (String) value;
                if (name.startsWith("───")) {
                    setForeground(Color.GRAY);
                    setFont(getFont().deriveFont(Font.ITALIC));
                    setEnabled(false);
                } else if (!sel && isPartial(name)) {
                    setForeground(new Color(180, 100, 0));
                }
                return this;
            }
        });

        JScrollPane listScroll = new JScrollPane(fileList);
        listScroll.setPreferredSize(new Dimension(230, 0));
        listScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(180, 180, 180)));

        // Status bar
        JLabel statusLbl = new JLabel(
                "  Scroll to zoom • Drag to pan • Double-click / R to reset   " +
                        "|   Yellow filename = NOT present in all sims");
        statusLbl.setFont(new Font("Source Sans Pro", Font.ITALIC, 11));
        statusLbl.setForeground(Color.DARK_GRAY);
        statusLbl.setBorder(new EmptyBorder(2, 6, 2, 6));

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.add(buildSimBar(), BorderLayout.NORTH);
        bottomBar.add(statusLbl,    BorderLayout.SOUTH);

        contentArea.setBackground(Color.WHITE);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(contentArea, BorderLayout.CENTER);
        rightPanel.add(bottomBar,   BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightPanel);
        split.setDividerLocation(230);
        split.setDividerSize(4);
        split.setBorder(null);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);

        // Selection listener
        fileList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String sel = fileList.getSelectedValue();
            if (sel == null || sel.startsWith("───")) return;
            loadAndPlot(sel);
        });

        if (!plotFiles.isEmpty()) fileList.setSelectedIndex(0);
    }

    // FILE DISCOVERY
    private void discoverFiles() {
        Set<String> plots = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> res   = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String path : simPaths) {
            File dir = new File(path, PLOTS_DIR);
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".txt"));
            if (files == null) continue;
            for (File f : files) {
                if (f.getName().contains("Residuals")) res.add(f.getName());
                else                                   plots.add(f.getName());
            }
        }
        plotFiles.addAll(plots);
        residualFiles.addAll(res);
    }

    private boolean isPartial(String name) {
        int found = 0;
        for (String p : simPaths)
            if (new File(new File(p, PLOTS_DIR), name).exists()) found++;
        return found > 0 && found < simPaths.length;
    }

    // CONTENT AREA SWAP
    private void setContent(JComponent component) {
        SwingUtilities.invokeLater(() -> {
            contentArea.removeAll();
            contentArea.add(component, BorderLayout.CENTER);
            contentArea.revalidate();
            contentArea.repaint();
        });
    }

    // PLOTTING DISPATCH
    private void loadAndPlot(String filename) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                PlotData[] data = new PlotData[3];
                for (int i = 0; i < 3; i++) {
                    File f = new File(new File(simPaths[i], PLOTS_DIR), filename);
                    if (f.exists()) {
                        try { data[i] = new PlotData(f); }
                        catch (Exception ex) { System.err.println("Parse error " + f + ": " + ex); }
                    }
                }
                PlotData.PlotType type = typeOf(data);
                if (type == null) return null;

                // CFL monitor files get a special two-panel treatment
                boolean isCFL = filename.toLowerCase().contains("cfl");

                switch (type) {
                    case MONITOR:
                        if (isCFL) buildCFLChart(data, filename);
                        else       buildMonitorChart(data, filename);
                        break;
                    case RESIDUALS: buildResidualsChart(data);           break;
                    case HISTOGRAM: buildHistogramChart(data, filename); break;
                    case XY:        buildXYChart(data, filename);        break;
                }
                return null;
            }
        }.execute();
    }

    private PlotData.PlotType typeOf(PlotData[] data) {
        for (PlotData d : data) if (d != null) return d.type;
        return null;
    }

    // SMOOTHING FUNCTION (for Lengthwise plots because they are hella noisy)
    /**
     * Applies a Gaussian-weighted moving average to y[], leaving x[] unchanged.
     * windowRadius controls how many points on each side contribute.
     */
    private double[] smoothSeries(double[] y, int windowRadius) {
        if (y == null || y.length == 0 || windowRadius <= 0) return y;
        double sigma = windowRadius / 2.0;
        double[] out = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            double sum = 0, wSum = 0;
            for (int j = Math.max(0, i - windowRadius); j <= Math.min(y.length - 1, i + windowRadius); j++) {
                if (Double.isNaN(y[j])) continue;
                double dist = i - j;
                double w = Math.exp(-(dist * dist) / (2 * sigma * sigma));
                sum  += w * y[j];
                wSum += w;
            }
            out[i] = wSum > 0 ? sum / wSum : y[i];
        }
        return out;
    }

    // Monitor chart: top is Raw (all sims), bottom is Avg (all sims)
    private void buildMonitorChart(PlotData[] data, String filename) {
        List<ChartPanel.SeriesSpec> rawSpecs = new ArrayList<>();
        List<ChartPanel.SeriesSpec> avgSpecs = new ArrayList<>();
        String yLbl = "";
        String title = cleanTitle(filename);

        for (int si = 0; si < 3; si++) {
            PlotData d = data[si];
            if (d == null) continue;
            if (yLbl.isEmpty()) yLbl = d.yLabel;

            for (PlotData.Series s : d.series) {
                if (s.isAvg) {
                    avgSpecs.add(new ChartPanel.SeriesSpec(
                            simNames[si], s.x, s.y, SIM_DARK[si], 1.5f, 0.95f));
                } else {
                    rawSpecs.add(new ChartPanel.SeriesSpec(
                            simNames[si], s.x, s.y, SIM_RAW[si],  2.2f, 0.70f));
                }
            }
        }

        ChartPanel rawChart = new ChartPanel();
        ChartPanel avgChart = new ChartPanel();
        final String yl = yLbl;
        rawChart.setSeries(rawSpecs, title + " — Raw",     "Iteration", yl);
        avgChart.setSeries(avgSpecs, title + " — Average", "Iteration", yl);

        JSplitPane vertSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rawChart, avgChart);
        vertSplit.setResizeWeight(0.5);
        vertSplit.setDividerSize(5);
        vertSplit.setBorder(null);

        setContent(vertSplit);
    }

    // CFL Chart: top is CFL Number, bottom is AMG Cycles
    private void buildCFLChart(PlotData[] data, String filename) {
        List<ChartPanel.SeriesSpec> cflSpecs = new ArrayList<>();
        List<ChartPanel.SeriesSpec> amgSpecs = new ArrayList<>();
        String title = cleanTitle(filename);

        for (int si = 0; si < 3; si++) {
            PlotData d = data[si];
            if (d == null) continue;
            for (PlotData.Series s : d.series) {
                String nl = s.name.toLowerCase();
                boolean isAMG = nl.contains("amg");
                List<ChartPanel.SeriesSpec> target = isAMG ? amgSpecs : cflSpecs;
                target.add(new ChartPanel.SeriesSpec(
                        simNames[si] + (isAMG ? " — AMG" : " — CFL"),
                        s.x, s.y, SIM_RAW[si], 1.6f, 0.85f));
            }
        }

        ChartPanel cflChart = new ChartPanel();
        ChartPanel amgChart = new ChartPanel();
        cflChart.setSeries(cflSpecs, title + " — CFL Number",  "Iteration", "CFL Number");
        amgChart.setSeries(amgSpecs, title + " — AMG Cycles",  "Iteration", "AMG Cycles");

        JSplitPane vertSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, cflChart, amgChart);
        vertSplit.setResizeWeight(0.6);
        vertSplit.setDividerSize(5);
        vertSplit.setBorder(null);
        setContent(vertSplit);
    }

    // RESIDUALS
    /**
     * Six fixed, perceptually distinct colours for the residual variables.
     * Order matches typical STAR-CCM+ residual column order:
     *   Sdr, Tke, Continuity, X-momentum, Y-momentum, Z-momentum
     */
    private static final Color[] RESIDUAL_COLORS = {
            new Color(0xE6, 0x19, 0x4B),  // vivid red       — Sdr
            new Color(0x3C, 0xB4, 0x4B),  // vivid green     — Tke
            new Color(0x43, 0x63, 0xD8),  // vivid blue      — Continuity
            new Color(0xFF, 0x7F, 0x00),  // vivid orange    — X-momentum
            new Color(0x91, 0x1E, 0xB4),  // vivid purple    — Y-momentum
            new Color(0x00, 0xBE, 0xD1),  // vivid cyan      — Z-momentum
    };

    private void buildResidualsChart(PlotData[] data) {
        JPanel threeUp = new JPanel(new GridLayout(3, 1, 0, 3));
        threeUp.setBackground(new Color(180, 180, 180));

        for (int si = 0; si < 3; si++) {
            PlotData d = data[si];
            ChartPanel panel = new ChartPanel();

            if (d != null && !d.series.isEmpty()) {
                List<ChartPanel.SeriesSpec> specs = new ArrayList<>();
                int n = d.series.size();
                for (int j = 0; j < n; j++) {
                    PlotData.Series s = d.series.get(j);
                    Color col = j < RESIDUAL_COLORS.length ? RESIDUAL_COLORS[j]
                            : RESIDUAL_COLORS[j % RESIDUAL_COLORS.length];
                    specs.add(new ChartPanel.SeriesSpec(s.name, s.x, s.y, col, 1.4f, 0.90f));
                }
                panel.setSeries(specs, simNames[si] + " — Residuals", "Iteration", "Residual");
                panel.setLogY(true);
            } else {
                panel.setSeries(Collections.emptyList(),
                        simNames[si] + " — Residuals (no data)", "Iteration", "Residual");
            }

            panel.setBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, SIM_RAW[si]));
            threeUp.add(panel);
        }

        setContent(threeUp);
    }

    // Wall Y+ Histogram
    private void buildHistogramChart(PlotData[] data, String filename) {
        final double CLIP = 5.0;
        List<ChartPanel.SeriesSpec> specs = new ArrayList<>();

        for (int si = 0; si < 3; si++) {
            PlotData d = data[si];
            if (d == null || d.series.isEmpty()) continue;
            PlotData.Series s = d.series.get(0);

            List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (int bi = 0; bi < Math.min(s.x.length, s.y.length); bi++) {
                if (s.x[bi] <= CLIP) { xs.add(s.x[bi]); ys.add(s.y[bi]); }
            }
            double[] xArr = xs.stream().mapToDouble(Double::doubleValue).toArray();
            double[] yArr = ys.stream().mapToDouble(Double::doubleValue).toArray();
            specs.add(new ChartPanel.SeriesSpec(simNames[si], xArr, yArr, SIM_RAW[si], 0.75f, true));
        }

        ChartPanel chart = new ChartPanel();
        chart.setSeries(specs, cleanTitle(filename) + "  (Y+ ≤ 5 shown)", "Wall Y+", "Frequency");
        setContent(chart);
    }

    // XY chart with optional smoothing for noisy spanwise/lengthwise ClA plots
    private void buildXYChart(PlotData[] data, String filename) {
        List<ChartPanel.SeriesSpec> specs = new ArrayList<>();
        String xLbl = "", yLbl = "";

        // Apply smoothing for spanwise and lengthwise Cl (not Cd) plots
        String fnLower = filename.toLowerCase();
        boolean applySmooth = (fnLower.contains("spanwise") || fnLower.contains("lengthwise"))
                && !fnLower.contains("cd") && !fnLower.contains("drag");
        int smoothRadius = 8;  // Gaussian window half-width in data points

        for (int si = 0; si < 3; si++) {
            PlotData d = data[si];
            if (d == null) continue;
            if (xLbl.isEmpty()) xLbl = d.xLabel;
            if (yLbl.isEmpty()) yLbl = d.yLabel;
            for (PlotData.Series s : d.series) {
                double[] yData = applySmooth ? smoothSeries(s.y, smoothRadius) : s.y;
                specs.add(new ChartPanel.SeriesSpec(simNames[si], s.x, yData, SIM_RAW[si], 1.8f, 0.85f));
            }
        }

        ChartPanel chart = new ChartPanel();
        chart.setSeries(specs, cleanTitle(filename), xLbl, yLbl);
        setContent(chart);
    }

    // ---- Helpers ---------------------------------------------------------

    private String cleanTitle(String filename) {
        return filename.replace(".txt", "").replace("_", " ").trim();
    }

    private Color blend(Color a, Color b, float t) {
        return new Color(
                (int)(a.getRed()   * (1-t) + b.getRed()   * t),
                (int)(a.getGreen() * (1-t) + b.getGreen() * t),
                (int)(a.getBlue()  * (1-t) + b.getBlue()  * t));
    }

    private Color blendToWhite(Color c, float t) { return blend(c, Color.WHITE, t); }

    // ---- Sim legend bar --------------------------------------------------

    private JPanel buildSimBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 4));
        bar.setBackground(new Color(240, 240, 242));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(200, 200, 205)));

        String[] roles = {"Active", "Baseline", "Delta"};
        for (int i = 0; i < 3; i++) {
            JLabel name = new JLabel("  " + roles[i] + ": " + simNames[i] + "  ");
            name.setFont(new Font("Source Sans Pro", Font.BOLD, 12));
            name.setForeground(SIM_RAW[i].darker());
            name.setBackground(blend(SIM_RAW[i], Color.WHITE, 0.85f));
            name.setOpaque(true);
            name.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(SIM_RAW[i], 1),
                    BorderFactory.createEmptyBorder(2, 4, 2, 4)));
            bar.add(name);

            JLabel raw = new JLabel(" Raw ");
            raw.setFont(new Font("Source Sans Pro", Font.PLAIN, 11));
            raw.setForeground(SIM_RAW[i]);
            raw.setBackground(blend(SIM_RAW[i], Color.WHITE, 0.92f));
            raw.setOpaque(true);
            raw.setBorder(BorderFactory.createLineBorder(SIM_RAW[i], 1));
            bar.add(raw);

            JLabel avg = new JLabel(" Avg ");
            avg.setFont(new Font("Source Sans Pro", Font.BOLD, 11));
            avg.setForeground(SIM_DARK[i]);
            avg.setBackground(blend(SIM_DARK[i], Color.WHITE, 0.88f));
            avg.setOpaque(true);
            avg.setBorder(BorderFactory.createLineBorder(SIM_DARK[i], 1));
            bar.add(avg);

            if (i < 2) {
                JLabel sep = new JLabel("│");
                sep.setForeground(Color.LIGHT_GRAY);
                bar.add(sep);
            }
        }
        return bar;
    }
}