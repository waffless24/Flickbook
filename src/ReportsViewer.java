import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.awt.event.KeyEvent;

/**
 * Shows Reports from all three sims combined in a single sortable table.
 *
 * Layout:
 *   Left:  filename list (union across sims; amber = not present in all)
 *   Right: merged JTable with a leading "Sim" column colour-coded per sim,
 *          plus filter, sort, and clipboard-copy.
 */
public class ReportsViewer extends JDialog {

    private static final String REPORTS_DIR = "Reports";

    // Per-sim report directories
    private final File actReportsDir;
    private final File bslReportsDir;
    private final File deltaReportsDir;

    private final String actName;
    private final String bslName;
    private final String deltaName;

    // Palette matching your 5-colour scheme
    private static final Color COL_ACT   = new Color(31, 119, 180);   // blue
    private static final Color COL_BSL   = new Color(255, 127, 14);   // orange
    private static final Color COL_DELTA = new Color(23, 190, 207);   // teal
    private static final Color COL_ACT_BG   = new Color(220, 235, 250);
    private static final Color COL_BSL_BG   = new Color(255, 237, 210);
    private static final Color COL_DELTA_BG = new Color(210, 245, 250);

    private final List<String> allFilenames;

    // Table
    private final DefaultTableModel tableModel;
    private final JTable table;
    private TableRowSorter<DefaultTableModel> sorter;

    private final JLabel rowCountLabel;
    private final JTextField searchField;

    // Track which model row belongs to which sim (for colouring)
    private final List<Integer> rowSimIndex = new ArrayList<>(); // 0=act, 1=bsl, 2=delta

    // Raw numeric values parallel to tableModel (row-major, col 0 = Sim string skipped → offset by 1)
    private double[][] rawValues  = new double[0][0];
    private boolean[]  isStdDevCols = new boolean[0];
    private int        decimalPlaces = 6;

    public ReportsViewer(Frame owner,
                         String actName,   String actPath,
                         String bslName,   String bslPath,
                         String deltaName, String deltaPath) {
        super(owner, "Reports — All Sims Combined", false);
        setSize(1600, 860);
        setLocationRelativeTo(owner);

        this.actName   = actName;
        this.bslName   = bslName;
        this.deltaName = deltaName;

        this.actReportsDir   = new File(actPath,   REPORTS_DIR);
        this.bslReportsDir   = new File(bslPath,   REPORTS_DIR);
        this.deltaReportsDir = new File(deltaPath, REPORTS_DIR);

        // Union of filenames, sorted
        Set<String> nameSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        addNames(nameSet, actReportsDir);
        addNames(nameSet, bslReportsDir);
        addNames(nameSet, deltaReportsDir);
        allFilenames = new ArrayList<>(nameSet);

        // ---- File list (left) ----
        DefaultListModel<String> listModel = new DefaultListModel<>();
        allFilenames.forEach(listModel::addElement);

        JList<String> fileList = new JList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
        fileList.setBackground(new Color(245, 245, 245));
        fileList.setFixedCellHeight(24);

        // Amber = not present in all sims
        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String name = (String) value;
                boolean missing = !new File(actReportsDir,   name).exists()
                        || !new File(bslReportsDir,   name).exists()
                        || !new File(deltaReportsDir, name).exists();
                if (!isSelected && missing) setForeground(new Color(180, 100, 0));
                return this;
            }
        });

        JScrollPane listScroll = new JScrollPane(fileList);
        listScroll.setPreferredSize(new Dimension(240, 0));
        listScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(180, 180, 180)));

        // ---- Table ----
        tableModel = new DefaultTableModel() {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(tableModel);
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setGridColor(new Color(210, 210, 210));
        table.setShowGrid(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setFont(new Font("Source Sans Pro", Font.BOLD, 12));
        table.getTableHeader().setBackground(new Color(50, 50, 50));
        table.getTableHeader().setForeground(Color.WHITE);
        table.setSelectionBackground(new Color(180, 210, 240));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setCellSelectionEnabled(true);

        // Ctrl+C copies selected cells
        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copySelection");
        table.getActionMap().put("copySelection", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { copySelectionToClipboard(); }
        });

        // Custom renderer: colour the "Sim" column and alternate rows per sim block
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                                                           boolean hasFocus, int viewRow, int col) {
                super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, viewRow, col);
                if (!isSelected) {
                    int modelRow = tbl.convertRowIndexToModel(viewRow);
                    int simIdx = modelRow < rowSimIndex.size() ? rowSimIndex.get(modelRow) : -1;
                    Color bg = Color.WHITE;
                    if (simIdx == 0)      bg = COL_ACT_BG;
                    else if (simIdx == 1) bg = COL_BSL_BG;
                    else if (simIdx == 2) bg = COL_DELTA_BG;
                    setBackground(bg);

                    // Bold + foreground colour for Sim column
                    if (col == 0) {
                        Color fg = simIdx == 0 ? COL_ACT : simIdx == 1 ? COL_BSL : COL_DELTA;
                        setForeground(fg);
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else {
                        setForeground(Color.BLACK);
                        setFont(getFont().deriveFont(Font.PLAIN));
                    }
                }

                // Right-align numbers (skip Sim column)
                if (col > 0) {
                    try {
                        if (value != null) Double.parseDouble(value.toString().trim());
                        setHorizontalAlignment(RIGHT);
                    } catch (NumberFormatException ex) {
                        setHorizontalAlignment(LEFT);
                    }
                } else {
                    setHorizontalAlignment(LEFT);
                }
                return this;
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);

        // ---- Toolbar ----
        searchField = new JTextField(20);
        searchField.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
        searchField.setToolTipText("Filter rows — supports regex");

        JLabel searchLabel = new JLabel("Filter:");
        searchLabel.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));

        JButton clearBtn = new JButton("✕");
        clearBtn.setFont(new Font("Source Sans Pro", Font.PLAIN, 11));
        clearBtn.setMargin(new Insets(1, 4, 1, 4));
        clearBtn.setToolTipText("Clear filter");
        clearBtn.addActionListener(e -> searchField.setText(""));

        // Quick-filter buttons per sim
        JButton filterAct   = makeSimFilterButton(actName,   COL_ACT);
        JButton filterBsl   = makeSimFilterButton(bslName,   COL_BSL);
        JButton filterDelta = makeSimFilterButton(deltaName, COL_DELTA);
        filterAct.addActionListener(e   -> searchField.setText(actName));
        filterBsl.addActionListener(e   -> searchField.setText(bslName));
        filterDelta.addActionListener(e -> searchField.setText(deltaName));

        // ---- Toolbar — two rows ----
        Font toolFont = new Font("Source Sans Pro", Font.PLAIN, 13);

        // Row 1: filter
        JPanel toolRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        toolRow1.setBackground(new Color(240, 240, 240));
        toolRow1.add(searchLabel);
        toolRow1.add(searchField);
        toolRow1.add(clearBtn);
        toolRow1.add(Box.createHorizontalStrut(8));
        JLabel showOnlyLabel = new JLabel("Show only:");
        showOnlyLabel.setFont(toolFont);
        toolRow1.add(showOnlyLabel);
        toolRow1.add(filterAct);
        toolRow1.add(filterBsl);
        toolRow1.add(filterDelta);

        // Row 2: sig figs + column width + copy
        JLabel sigFigsLabel = new JLabel("Decimal places:");
        sigFigsLabel.setFont(toolFont);
        SpinnerNumberModel spinModel = new SpinnerNumberModel(decimalPlaces, 0, 15, 1);
        JSpinner sigFigsSpinner = new JSpinner(spinModel);
        sigFigsSpinner.setFont(toolFont);
        sigFigsSpinner.setPreferredSize(new Dimension(58, sigFigsSpinner.getPreferredSize().height));
        sigFigsSpinner.setToolTipText("Number of decimal places displayed");
        sigFigsSpinner.addChangeListener(e -> {
            decimalPlaces = (Integer) spinModel.getValue();
            reformatNumericCells();
        });

        JLabel colWidthLabel = new JLabel("Col width:");
        colWidthLabel.setFont(toolFont);
        JSlider colWidthSlider = new JSlider(30, 400, 150);
        colWidthSlider.setPreferredSize(new Dimension(130, colWidthSlider.getPreferredSize().height));
        colWidthSlider.setToolTipText("Adjust column width");
        colWidthSlider.setBackground(new Color(240, 240, 240));
        colWidthSlider.addChangeListener(e -> applyColumnWidth(colWidthSlider.getValue()));

        JButton copyBtn = new JButton("Copy All");
        copyBtn.setFont(toolFont);
        copyBtn.setToolTipText("Copy entire visible table to clipboard");
        copyBtn.addActionListener(e -> copyTableToClipboard());

        JPanel toolRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        toolRow2.setBackground(new Color(240, 240, 240));
        toolRow2.add(sigFigsLabel);
        toolRow2.add(sigFigsSpinner);
        toolRow2.add(Box.createHorizontalStrut(10));
        toolRow2.add(colWidthLabel);
        toolRow2.add(colWidthSlider);
        toolRow2.add(Box.createHorizontalStrut(10));
        toolRow2.add(copyBtn);

        JPanel toolbar = new JPanel(new GridLayout(2, 1, 0, 0));
        toolbar.setBackground(new Color(240, 240, 240));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 200, 200)));
        toolbar.add(toolRow1);
        toolbar.add(toolRow2);

        // ---- Legend ----
        JPanel legend = buildLegend();

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.add(toolbar, BorderLayout.CENTER);
        topBar.add(legend, BorderLayout.EAST);

        // ---- Status bar ----
        rowCountLabel = new JLabel("  No file selected");
        rowCountLabel.setFont(new Font("Source Sans Pro", Font.ITALIC, 12));
        rowCountLabel.setForeground(Color.DARK_GRAY);

        JLabel hintLabel = new JLabel("Ctrl+C copies selection  •  Click column headers to sort   ");
        hintLabel.setFont(new Font("Source Sans Pro", Font.ITALIC, 12));
        hintLabel.setForeground(Color.DARK_GRAY);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(3, 6, 3, 6));
        statusBar.setBackground(new Color(240, 240, 240));
        statusBar.add(rowCountLabel, BorderLayout.WEST);
        statusBar.add(hintLabel, BorderLayout.EAST);

        // ---- Right panel ----
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(topBar, BorderLayout.NORTH);
        rightPanel.add(tableScroll, BorderLayout.CENTER);
        rightPanel.add(statusBar, BorderLayout.SOUTH);

        // ---- Split ----
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightPanel);
        split.setDividerLocation(240);
        split.setDividerSize(4);
        split.setBorder(null);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);

        // ---- Listeners ----
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && fileList.getSelectedIndex() >= 0) {
                loadCsvCombined(allFilenames.get(fileList.getSelectedIndex()));
            }
        });

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });

        if (!allFilenames.isEmpty()) fileList.setSelectedIndex(0);
    }

    // -------------------------------------------------------------------------

    private void addNames(Set<String> set, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".csv"));
        if (files != null) for (File f : files) set.add(f.getName());
    }

    private JButton makeSimFilterButton(String name, Color color) {
        JButton btn = new JButton(name);
        btn.setFont(new Font("Source Sans Pro", Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setBackground(color);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setMargin(new Insets(2, 8, 2, 8));
        return btn;
    }

    private JPanel buildLegend() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        p.setBackground(new Color(240, 240, 240));
        p.add(legendChip(actName,   COL_ACT,   COL_ACT_BG));
        p.add(legendChip(bslName,   COL_BSL,   COL_BSL_BG));
        p.add(legendChip(deltaName, COL_DELTA, COL_DELTA_BG));
        return p;
    }

    private JLabel legendChip(String name, Color fg, Color bg) {
        JLabel l = new JLabel("  " + name + "  ");
        l.setFont(new Font("Source Sans Pro", Font.BOLD, 12));
        l.setForeground(fg);
        l.setBackground(bg);
        l.setOpaque(true);
        l.setBorder(BorderFactory.createLineBorder(fg, 1));
        return l;
    }

    // -------------------------------------------------------------------------

    private void loadCsvCombined(String filename) {
        File actFile   = new File(actReportsDir,   filename);
        File bslFile   = new File(bslReportsDir,   filename);
        File deltaFile = new File(deltaReportsDir, filename);

        SwingWorker<Object[][], Void> worker = new SwingWorker<>() {
            @Override
            protected Object[][] doInBackground() throws Exception {
                // Read each sim; tag each row with sim name
                List<String[]> actRows   = readCsv(actFile);
                List<String[]> bslRows   = readCsv(bslFile);
                List<String[]> deltaRows = readCsv(deltaFile);

                // Determine common header (use first available, verify others match)
                String[] header = null;
                if (!actRows.isEmpty())   header = actRows.get(0);
                else if (!bslRows.isEmpty())   header = bslRows.get(0);
                else if (!deltaRows.isEmpty()) header = deltaRows.get(0);

                // Return as Object[0] = header (String[]), Object[1] = merged rows (List<Object[]>), Object[2] = simIndices (List<Integer>)
                List<Object[]> merged = new ArrayList<>();
                List<Integer>  sims   = new ArrayList<>();

                appendRows(merged, sims, actRows,   0, header);
                appendRows(merged, sims, bslRows,   1, header);
                appendRows(merged, sims, deltaRows, 2, header);

                return new Object[][]{ header, merged.toArray(new Object[0][]), sims.stream().mapToInt(i->i).boxed().toArray(Integer[]::new) };
            }

            @Override
            protected void done() {
                try {
                    Object[][] result = get();
                    String[]   header  = (String[])   result[0];
                    Object[][] merged  = (Object[][]) result[1];
                    Integer[]  sims    = (Integer[])  result[2];

                    tableModel.setRowCount(0);
                    tableModel.setColumnCount(0);
                    rowSimIndex.clear();

                    if (header == null) {
                        rowCountLabel.setText("  No data found for " + filename);
                        return;
                    }

                    // Prepend "Sim" column; rename Variance columns to Std Dev
                    String[] fullHeader = new String[header.length + 1];
                    fullHeader[0] = "Sim";
                    for (int c = 0; c < header.length; c++) {
                        String h = header[c];
                        fullHeader[c + 1] = h.contains("Variance") ? h.replace("Variance", "Std Dev") : h;
                    }
                    tableModel.setColumnIdentifiers(fullHeader);

                    // Identify which model columns are variance (now std dev) — col offset +1 for Sim
                    isStdDevCols = new boolean[header.length];
                    for (int c = 0; c < header.length; c++) {
                        isStdDevCols[c] = header[c].contains("Variance");
                    }

                    rawValues = new double[merged.length][header.length];
                    for (double[] row : rawValues) Arrays.fill(row, Double.NaN);

                    for (int i = 0; i < merged.length; i++) {
                        Object[] row = merged[i];
                        // row[0] = sim name, row[1..] = data values
                        for (int c = 1; c < row.length; c++) {
                            int dataCol = c - 1;
                            try {
                                double v = Double.parseDouble(row[c].toString().trim());
                                double display = (dataCol < isStdDevCols.length && isStdDevCols[dataCol] && v >= 0)
                                        ? Math.sqrt(v) : v;
                                rawValues[i][dataCol] = display;
                                row[c] = formatDecimalPlaces(display, decimalPlaces);
                            } catch (NumberFormatException ignored) {}
                        }
                        tableModel.addRow(row);
                        rowSimIndex.add(sims[i]);
                    }

                    sorter = new TableRowSorter<>(tableModel);
                    table.setRowSorter(sorter);
                    autoSizeColumns();
                    applyFilter();

                    rowCountLabel.setText("  " + tableModel.getRowCount() + " rows × " + fullHeader.length + " columns   |   " + filename);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    rowCountLabel.setText("  Error loading " + filename);
                }
            }
        };
        worker.execute();
    }

    /** Read CSV, return list of String[] rows (first row = header). Empty list if file missing. */
    private List<String[]> readCsv(File file) {
        List<String[]> rows = new ArrayList<>();
        if (file == null || !file.exists()) return rows;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) rows.add(parseCsvLine(line));
        } catch (IOException ex) {
            System.err.println("Error reading CSV: " + file);
        }
        return rows;
    }

    /** Append data rows (skip header at index 0) to merged list, prepending sim name. */
    private void appendRows(List<Object[]> merged, List<Integer> sims,
                            List<String[]> rows, int simIdx, String[] canonicalHeader) {
        if (rows.isEmpty()) return;
        String simName = simIdx == 0 ? actName : simIdx == 1 ? bslName : deltaName;
        // Detect if this file's header matches canonical (best-effort, skip if not)
        // headerLen = canonical header length; total cols = headerLen + 1 (Sim prepended)
        int hLen = canonicalHeader != null ? canonicalHeader.length : 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] src = rows.get(i);
            Object[] dest = new Object[hLen + 1];
            dest[0] = simName;
            for (int c = 0; c < hLen; c++) {
                dest[c + 1] = c < src.length ? src[c] : "";
            }
            merged.add(dest);
            sims.add(simIdx);
        }
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { inQuotes = !inQuotes; }
            else if (c == ',' && !inQuotes) { fields.add(sb.toString().trim()); sb.setLength(0); }
            else { sb.append(c); }
        }
        fields.add(sb.toString().trim());
        return fields.toArray(new String[0]);
    }

    private void autoSizeColumns() {
        for (int col = 0; col < table.getColumnCount(); col++) {
            int maxW = 60;
            maxW = Math.max(maxW, table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table,
                            table.getColumnModel().getColumn(col).getHeaderValue(),
                            false, false, -1, col).getPreferredSize().width + 10);
            int limit = Math.min(tableModel.getRowCount(), 200);
            for (int row = 0; row < limit; row++) {
                Component c = table.getDefaultRenderer(Object.class)
                        .getTableCellRendererComponent(table, tableModel.getValueAt(row, col),
                                false, false, row, col);
                maxW = Math.max(maxW, c.getPreferredSize().width + 10);
            }
            table.getColumnModel().getColumn(col).setPreferredWidth(Math.min(maxW, 300));
        }
    }

    private void applyFilter() {
        if (sorter == null) return;
        String text = searchField.getText().trim();
        if (text.isEmpty()) { sorter.setRowFilter(null); }
        else {
            try { sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text)); }
            catch (java.util.regex.PatternSyntaxException ignored) {}
        }
        String base = rowCountLabel.getText();
        int pipe = base.indexOf(" | Showing");
        if (pipe >= 0) base = base.substring(0, pipe);
        if (!text.isEmpty()) base += " | Showing " + table.getRowCount() + " of " + tableModel.getRowCount();
        rowCountLabel.setText(base);
    }

    private void applyColumnWidth(int width) {
        for (int col = 0; col < table.getColumnCount(); col++) {
            table.getColumnModel().getColumn(col).setPreferredWidth(width);
        }
    }

    private void reformatNumericCells() {
        int rows = tableModel.getRowCount();
        if (rawValues.length < rows) return;
        for (int r = 0; r < rows; r++) {
            // col 0 = Sim string, data starts at col 1
            for (int c = 1; c < tableModel.getColumnCount() && (c - 1) < rawValues[r].length; c++) {
                if (!Double.isNaN(rawValues[r][c - 1])) {
                    tableModel.setValueAt(formatDecimalPlaces(rawValues[r][c - 1], decimalPlaces), r, c);
                }
            }
        }
    }

    private String formatDecimalPlaces(double v, int dp) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);
        return String.format("%." + dp + "f", v);
    }

    private void copySelectionToClipboard() {
        int[] selRows = table.getSelectedRows();
        int[] selCols = table.getSelectedColumns();
        if (selRows.length == 0 || selCols.length == 0) return;
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < selRows.length; r++) {
            for (int c = 0; c < selCols.length; c++) {
                if (c > 0) sb.append('\t');
                Object val = table.getValueAt(selRows[r], selCols[c]);
                sb.append(val != null ? val : "");
            }
            sb.append('\n');
        }
        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(sb.toString());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
    }

    private void copyTableToClipboard() {
        StringBuilder sb = new StringBuilder();
        for (int col = 0; col < table.getColumnCount(); col++) {
            if (col > 0) sb.append('\t');
            sb.append(table.getColumnName(col));
        }
        sb.append('\n');
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int col = 0; col < table.getColumnCount(); col++) {
                if (col > 0) sb.append('\t');
                Object val = table.getValueAt(row, col);
                sb.append(val != null ? val : "");
            }
            sb.append('\n');
        }
        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(sb.toString());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        JOptionPane.showMessageDialog(this, "Table copied to clipboard (tab-separated).", "Copied", JOptionPane.INFORMATION_MESSAGE);
    }
}