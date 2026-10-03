import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;

/**
 * Lets the user assign:
 *   - one Delta colormap (e.g. turbo), shared by every variable, to
 *     display the difference with
 *   - per variable, the Original .colormap the Active/Baseline scenes were
 *     actually rendered with plus the scale it covers (needed to decode
 *     their pixel colors back into scalar values), and the delta scale to
 *     display that variable's difference over
 * Saving invalidates the engine's caches so the next 'D' press re-parses
 * the colormap(s) and regenerates delta scenes.
 */
public class DeltaSettingsDialog extends JDialog {

    public DeltaSettingsDialog(Frame owner, String[] variables, DeltaSceneEngine engine) {
        super(owner, "Delta Colormap Settings", true);
        setSize(760, 170 + variables.length * 100);
        setLocationRelativeTo(owner);

        Font headerFont = new Font("Source Sans Pro", Font.BOLD, 13);
        Font labelFont  = new Font("Source Sans Pro", Font.BOLD, 11);
        Font fieldFont  = new Font("Source Sans Pro", Font.PLAIN, 12);

        DeltaSettings settings = engine.getSettings();

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        int gy = 0;

        // ---- One global delta colormap, shared by every variable --------
        JLabel globalHeader = new JLabel("Delta Colormap (applies to all variables)");
        globalHeader.setFont(headerFont);
        gc.gridy = gy++; gc.gridx = 0; gc.gridwidth = 5; gc.weightx = 1;
        grid.add(globalHeader, gc);
        gc.gridwidth = 1;

        JTextField deltaCmapField = new JTextField(settings.getDeltaCmapPath(), 20);
        deltaCmapField.setFont(fieldFont);
        deltaCmapField.setEditable(false);
        JButton deltaCmapBrowse = new JButton("Browse");
        deltaCmapBrowse.setFont(fieldFont);
        deltaCmapBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(deltaCmapField.getText().isBlank() ? "." : deltaCmapField.getText());
            fc.setDialogTitle("Choose the DELTA .colormap used to display every variable's difference");
            fc.setFileFilter(new FileNameExtensionFilter("Colormap files (*.colormap)", "colormap"));
            fc.setAcceptAllFileFilterUsed(true);
            if (fc.showOpenDialog(grid) == JFileChooser.APPROVE_OPTION) {
                deltaCmapField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        gc.gridy = gy++;
        gc.gridx = 1; gc.weightx = 1; grid.add(deltaCmapField, gc);
        gc.gridx = 2; gc.weightx = 0; grid.add(deltaCmapBrowse, gc);

        JSeparator globalSep = new JSeparator();
        gc.gridy = gy++; gc.gridx = 0; gc.gridwidth = 5;
        gc.insets = new Insets(10, 4, 10, 4);
        grid.add(globalSep, gc);
        gc.insets = new Insets(2, 4, 2, 4);
        gc.gridwidth = 1;

        // ---- Per-variable original colormap + both scales ----------------
        JTextField[] origCmapFields  = new JTextField[variables.length];
        JTextField[] origMinFields   = new JTextField[variables.length];
        JTextField[] origMaxFields   = new JTextField[variables.length];
        JTextField[] deltaMinFields  = new JTextField[variables.length];
        JTextField[] deltaMaxFields  = new JTextField[variables.length];

        for (int r = 0; r < variables.length; r++) {
            String variable = variables[r];
            DeltaSettings.VariableConfig cfg = settings.get(variable);

            JLabel nameLbl = new JLabel(variable);
            nameLbl.setFont(headerFont);
            gc.gridy = gy++; gc.gridx = 0; gc.gridwidth = 5; gc.weightx = 1;
            grid.add(nameLbl, gc);
            gc.gridwidth = 1;

            JTextField[] origRow = addCmapRow(grid, gc, gy++, "Original", labelFont, fieldFont,
                    cfg.originalCmapPath, cfg.originalMin, cfg.originalMax,
                    "Choose the ORIGINAL .colormap the Active/Baseline scenes for \"" + variable + "\" were rendered with");
            JTextField[] deltaRangeRow = addRangeRow(grid, gc, gy++, "Delta Range", labelFont, fieldFont,
                    cfg.deltaMin, cfg.deltaMax);

            origCmapFields[r] = origRow[0]; origMinFields[r] = origRow[1]; origMaxFields[r] = origRow[2];
            deltaMinFields[r] = deltaRangeRow[0]; deltaMaxFields[r] = deltaRangeRow[1];

            JSeparator sep = new JSeparator();
            gc.gridy = gy++; gc.gridx = 0; gc.gridwidth = 5;
            gc.insets = new Insets(6, 4, 6, 4);
            grid.add(sep, gc);
            gc.insets = new Insets(2, 4, 2, 4);
            gc.gridwidth = 1;
        }

        JButton saveBtn = new JButton("Save");
        JButton cancelBtn = new JButton("Cancel");
        saveBtn.addActionListener(e -> {
            try {
                settings.setDeltaCmapPath(deltaCmapField.getText().trim());
                for (int r = 0; r < variables.length; r++) {
                    DeltaSettings.VariableConfig cfg = new DeltaSettings.VariableConfig();
                    cfg.originalCmapPath = origCmapFields[r].getText().trim();
                    cfg.originalMin = Double.parseDouble(origMinFields[r].getText().trim());
                    cfg.originalMax = Double.parseDouble(origMaxFields[r].getText().trim());
                    cfg.deltaMin    = Double.parseDouble(deltaMinFields[r].getText().trim());
                    cfg.deltaMax    = Double.parseDouble(deltaMaxFields[r].getText().trim());
                    settings.set(variables[r], cfg);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "All scale fields must be valid numbers.",
                        "Invalid input", JOptionPane.ERROR_MESSAGE);
                return;
            }
            settings.save();
            engine.invalidateAll(); // re-parse cmaps / drop stale delta images next time 'D' is pressed
            dispose();
        });
        cancelBtn.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelBtn);
        buttons.add(saveBtn);

        setLayout(new BorderLayout());
        add(new JScrollPane(grid), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    /**
     * Adds the "Original" row to the grid: a role label, a read-only cmap
     * path field + Browse button, and Min/Max fields.
     * Returns {cmapField, minField, maxField} so the caller can read them back on Save.
     */
    private JTextField[] addCmapRow(JPanel grid, GridBagConstraints gc, int row,
                                    String roleLabel, Font labelFont, Font fieldFont,
                                    String cmapPath, double min, double max, String browseTitle) {
        JLabel roleLbl = new JLabel(roleLabel + ":");
        roleLbl.setFont(labelFont);

        JTextField cmapField = new JTextField(cmapPath, 20);
        cmapField.setFont(fieldFont);
        cmapField.setEditable(false);

        JButton browse = new JButton("Browse");
        browse.setFont(fieldFont);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(cmapField.getText().isBlank() ? "." : cmapField.getText());
            fc.setDialogTitle(browseTitle);
            fc.setFileFilter(new FileNameExtensionFilter("Colormap files (*.colormap)", "colormap"));
            fc.setAcceptAllFileFilterUsed(true);
            if (fc.showOpenDialog(grid) == JFileChooser.APPROVE_OPTION) {
                cmapField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JTextField minField = new JTextField(trimNum(min), 6);
        JTextField maxField = new JTextField(trimNum(max), 6);
        minField.setFont(fieldFont);
        maxField.setFont(fieldFont);

        gc.gridy = row;
        gc.gridx = 0; gc.weightx = 0; grid.add(roleLbl, gc);
        gc.gridx = 1; gc.weightx = 1; grid.add(cmapField, gc);
        gc.gridx = 2; gc.weightx = 0; grid.add(browse, gc);
        gc.gridx = 3; grid.add(minField, gc);
        gc.gridx = 4; grid.add(maxField, gc);

        return new JTextField[]{cmapField, minField, maxField};
    }

    /**
     * Adds a scale-only row (no colormap picker — used for the per-variable
     * Delta Range, since the colormap itself is now global).
     * Returns {minField, maxField}.
     */
    private JTextField[] addRangeRow(JPanel grid, GridBagConstraints gc, int row,
                                     String roleLabel, Font labelFont, Font fieldFont,
                                     double min, double max) {
        JLabel roleLbl = new JLabel(roleLabel + ":");
        roleLbl.setFont(labelFont);

        JTextField minField = new JTextField(trimNum(min), 6);
        JTextField maxField = new JTextField(trimNum(max), 6);
        minField.setFont(fieldFont);
        maxField.setFont(fieldFont);

        gc.gridy = row;
        gc.gridx = 0; gc.weightx = 0; grid.add(roleLbl, gc);
        gc.gridx = 3; grid.add(minField, gc);
        gc.gridx = 4; grid.add(maxField, gc);

        return new JTextField[]{minField, maxField};
    }

    private static String trimNum(double v) {
        return (v == Math.rint(v)) ? String.valueOf((long) v) : String.valueOf(v);
    }
}