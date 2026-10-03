import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * A dialog that browses .csv files from a sim's "Reports" subfolder and renders
 * them as a sortable, searchable, copy-pasteable JTable with adjustable sig figs.
 */
public class SingleSimReportsViewer extends JDialog {

    private static final String REPORTS_DIR = "Reports";

    private final File[] csvFiles;
    private final JTable table;
    private final DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> sorter;
    private final JLabel rowCountLabel;
    private final JTextField searchField;

    // Raw double values parallel to tableModel rows/cols (row-major).
    // null entry = non-numeric cell.  Rebuilt on every CSV load.
    private double[][] rawValues = new double[0][0];
    private boolean[]  isStdDevCol = new boolean[0];
    private int        decimalPlaces = 6;  // current display precision

    public SingleSimReportsViewer(Frame owner, String simName, String simPath) {
        super(owner, "Reports — " + simName, false);
        setSize(1400, 860);
        setLocationRelativeTo(owner);

        // --- Discover CSV files ---
        File reportsDir = new File(simPath, REPORTS_DIR);
        File[] found = reportsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".csv"));
        if (found == null) found = new File[0];
        Arrays.sort(found, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        this.csvFiles = found;

        // ---- Left: file list ----
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (File f : csvFiles) listModel.addElement(f.getName());

        JList<String> fileList = new JList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
        fileList.setBackground(new Color(245, 245, 245));
        fileList.setFixedCellHeight(24);

        JScrollPane listScroll = new JScrollPane(fileList);
        listScroll.setPreferredSize(new Dimension(260, 0));
        listScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(180, 180, 180)));

        // ---- Right: table ----
        tableModel = new DefaultTableModel() {
            @Override public boolean isCellEditable(int row, int col) { return false; }
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

        // Allow multi-cell selection for copy-paste
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setCellSelectionEnabled(true);

        // Alternating row colours + right-align numbers
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int col) {
                super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
                if (!isSelected) {
                    setBackground(row % 2 == 0 ? Color.WHITE : new Color(240, 244, 248));
                }
                try {
                    if (value != null) Double.parseDouble(value.toString().trim());
                    setHorizontalAlignment(RIGHT);
                } catch (NumberFormatException e) {
                    setHorizontalAlignment(LEFT);
                }
                return this;
            }
        });

        // ---- Ctrl+C: copy selected cells ----
        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copySelection");
        table.getActionMap().put("copySelection", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { copySelectionToClipboard(); }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBackground(Color.WHITE);

        // ---- Toolbar ----
        Font toolFont = new Font("Source Sans Pro", Font.PLAIN, 13);

        searchField = new JTextField(20);
        searchField.setFont(toolFont);
        searchField.setToolTipText("Filter rows (any column)");

        JLabel searchLabel = new JLabel("Filter: ");
        searchLabel.setFont(toolFont);

        JButton clearBtn = new JButton("✕");
        clearBtn.setFont(new Font("Source Sans Pro", Font.PLAIN, 11));
        clearBtn.setMargin(new Insets(1, 4, 1, 4));
        clearBtn.setToolTipText("Clear filter");
        clearBtn.addActionListener(e -> searchField.setText(""));

        // ---- Toolbar — two rows ----
        // Row 1: filter
        JPanel toolRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        toolRow1.setBackground(new Color(240, 240, 240));
        toolRow1.add(searchLabel);
        toolRow1.add(searchField);
        toolRow1.add(clearBtn);

        // Row 2: sig figs + col width + copy
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

        JButton copyAllBtn = new JButton("Copy All");
        copyAllBtn.setFont(toolFont);
        copyAllBtn.setToolTipText("Copy entire visible table to clipboard");
        copyAllBtn.addActionListener(e -> copyTableToClipboard());

        JPanel toolRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        toolRow2.setBackground(new Color(240, 240, 240));
        toolRow2.add(sigFigsLabel);
        toolRow2.add(sigFigsSpinner);
        toolRow2.add(Box.createHorizontalStrut(10));
        toolRow2.add(colWidthLabel);
        toolRow2.add(colWidthSlider);
        toolRow2.add(Box.createHorizontalStrut(10));
        toolRow2.add(copyAllBtn);

        JPanel toolbar = new JPanel(new GridLayout(2, 1, 0, 0));
        toolbar.setBackground(new Color(240, 240, 240));
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 200, 200)));
        toolbar.add(toolRow1);
        toolbar.add(toolRow2);

        // ---- Status bar ----
        rowCountLabel = new JLabel("  No file selected");
        rowCountLabel.setFont(new Font("Source Sans Pro", Font.ITALIC, 12));
        rowCountLabel.setForeground(Color.DARK_GRAY);

        JLabel hintLabel = new JLabel("Ctrl+C copies selection  •  Click headers to sort   ");
        hintLabel.setFont(new Font("Source Sans Pro", Font.ITALIC, 12));
        hintLabel.setForeground(Color.DARK_GRAY);

        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(3, 6, 3, 6));
        statusBar.setBackground(new Color(240, 240, 240));
        statusBar.add(rowCountLabel, BorderLayout.WEST);
        statusBar.add(hintLabel,    BorderLayout.EAST);

        // ---- Right panel assembly ----
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(toolbar,     BorderLayout.NORTH);
        rightPanel.add(tableScroll, BorderLayout.CENTER);
        rightPanel.add(statusBar,   BorderLayout.SOUTH);

        // ---- Split ----
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightPanel);
        split.setDividerLocation(260);
        split.setDividerSize(4);
        split.setBorder(null);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);

        // ---- Listeners ----
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && fileList.getSelectedIndex() >= 0) {
                loadCsv(csvFiles[fileList.getSelectedIndex()]);
            }
        });

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });

        if (csvFiles.length > 0) {
            fileList.setSelectedIndex(0);
        } else {
            rowCountLabel.setText("  No .csv files found in " + reportsDir.getPath());
        }
    }

    // ---- CSV loading -----------------------------------------------------

    private void loadCsv(File file) {
        SwingWorker<List<String[]>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<String[]> doInBackground() throws Exception {
                List<String[]> rows = new ArrayList<>();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) rows.add(parseCsvLine(line));
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    List<String[]> rows = get();
                    tableModel.setRowCount(0);
                    tableModel.setColumnCount(0);

                    if (rows.isEmpty()) { rowCountLabel.setText("  File is empty."); return; }

                    // Header — rename Variance → Std Dev
                    String[] header = rows.get(0);
                    isStdDevCol = new boolean[header.length];
                    String[] displayHeader = new String[header.length];
                    for (int c = 0; c < header.length; c++) {
                        isStdDevCol[c]  = header[c].contains("Variance");
                        displayHeader[c] = isStdDevCol[c]
                                ? header[c].replace("Variance", "Std Dev") : header[c];
                    }
                    tableModel.setColumnIdentifiers(displayHeader);

                    int dataRowCount = rows.size() - 1;
                    rawValues = new double[dataRowCount][header.length];
                    // NaN = non-numeric
                    for (double[] row : rawValues) Arrays.fill(row, Double.NaN);

                    for (int i = 1; i < rows.size(); i++) {
                        String[] row    = rows.get(i);
                        String[] padded = Arrays.copyOf(row, header.length);
                        int ri = i - 1;

                        for (int c = 0; c < padded.length; c++) {
                            String cell = padded[c] != null ? padded[c].trim() : "";
                            try {
                                double v = Double.parseDouble(cell);
                                double display = (isStdDevCol[c] && v >= 0) ? Math.sqrt(v) : v;
                                rawValues[ri][c] = display;
                                padded[c] = formatDecimalPlaces(display, decimalPlaces);
                            } catch (NumberFormatException ignored) {
                                // leave as string, rawValues[ri][c] stays NaN
                                padded[c] = cell;
                            }
                        }
                        tableModel.addRow(padded);
                    }

                    // Sorter with numeric comparator for numeric columns
                    sorter = new TableRowSorter<>(tableModel);
                    for (int c = 0; c < header.length; c++) {
                        final int col = c;
                        sorter.setComparator(col, (a, b) -> {
                            try {
                                return Double.compare(
                                        Double.parseDouble(a.toString().trim()),
                                        Double.parseDouble(b.toString().trim()));
                            } catch (NumberFormatException e) {
                                return a.toString().compareTo(b.toString());
                            }
                        });
                    }
                    table.setRowSorter(sorter);

                    autoSizeColumns();
                    applyFilter();
                    rowCountLabel.setText("  " + dataRowCount + " rows × " + header.length
                            + " columns   |   " + file.getName());
                } catch (Exception ex) {
                    ex.printStackTrace();
                    rowCountLabel.setText("  Error loading file.");
                }
            }
        };
        worker.execute();
    }

    // ---- Decimal places ------------------------------------------------

    private void applyColumnWidth(int width) {
        for (int col = 0; col < table.getColumnCount(); col++) {
            table.getColumnModel().getColumn(col).setPreferredWidth(width);
        }
    }

    /** Re-renders all numeric cells using the current sigFigs value. */
    private void reformatNumericCells() {
        int rows = tableModel.getRowCount();
        int cols = tableModel.getColumnCount();
        if (rawValues.length < rows) return;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols && c < rawValues[r].length; c++) {
                if (!Double.isNaN(rawValues[r][c])) {
                    tableModel.setValueAt(formatDecimalPlaces(rawValues[r][c], decimalPlaces), r, c);
                }
            }
        }
    }

    private String formatDecimalPlaces(double v, int dp) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return String.valueOf(v);

        return String.format("%." + dp + "f", v);
    }

    // ---- Copy-paste ------------------------------------------------------

    /**
     * Copies the currently selected cells as tab-separated text — respects
     * the visual row/col selection rectangle so it pastes cleanly into Excel.
     */
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
        toClipboard(sb.toString());
    }

    /** Copies the full visible table (header + all visible rows). */
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
        toClipboard(sb.toString());
        JOptionPane.showMessageDialog(this, "Table copied to clipboard (tab-separated).",
                "Copied", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void toClipboard(String text) {
        java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
    }

    // ---- Helpers ---------------------------------------------------------

    private void autoSizeColumns() {
        for (int col = 0; col < table.getColumnCount(); col++) {
            int maxWidth = 60;
            maxWidth = Math.max(maxWidth, table.getTableHeader().getDefaultRenderer()
                    .getTableCellRendererComponent(table,
                            table.getColumnModel().getColumn(col).getHeaderValue(),
                            false, false, -1, col)
                    .getPreferredSize().width + 10);
            int limit = Math.min(tableModel.getRowCount(), 200);
            for (int row = 0; row < limit; row++) {
                Component c = table.getDefaultRenderer(Object.class)
                        .getTableCellRendererComponent(table, tableModel.getValueAt(row, col),
                                false, false, row, col);
                maxWidth = Math.max(maxWidth, c.getPreferredSize().width + 10);
            }
            table.getColumnModel().getColumn(col).setPreferredWidth(Math.min(maxWidth, 300));
        }
    }

    private void applyFilter() {
        if (sorter == null) return;
        String text = searchField.getText().trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            try {
                sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
            } catch (java.util.regex.PatternSyntaxException ignored) {}
        }
        int visible = table.getRowCount();
        int total   = tableModel.getRowCount();
        String base = rowCountLabel.getText();
        int pipe = base.indexOf(" | Showing");
        if (pipe >= 0) base = base.substring(0, pipe);
        if (!text.isEmpty()) base += " | Showing " + visible + " of " + total;
        rowCountLabel.setText(base);
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
}