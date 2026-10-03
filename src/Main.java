import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

public class Main {

    // CONSTANTS
    private static final String[] VIEWS     = {"AftFore", "TopBottom", "Profile"};
    private static final String[] VARIABLES = {
            "Inwash", "Pressure", "Total Pressure", "VISQ", "Upwash",
            "Helicity", "Pressure Variance", "Total Pressure Variance"
    };

    private static final String ACTION_STREAM_DOWN     = "streamDown";
    private static final String ACTION_STREAM_UP       = "streamUp";
    private static final String ACTION_TOGGLE_ACTIVE   = "toggleActive";
    private static final String ACTION_TOGGLE_BASELINE = "toggleBaseline";
    private static final String ACTION_TOGGLE_DELTA    = "toggleDelta";

    // Sim palette colours
    private static final Color COL_ACT   = new Color(0x1A, 0x1A, 0x1A);
    private static final Color COL_BSL   = new Color(0xCC, 0x11, 0x11);

    // View tracker (mutable via lambda)
    private static String currentView = VIEWS[0];
    private static String currentVariable = "Total Pressure";

    // Shared delta-scene generation engine (colormap + scale settings, caches)
    private static final DeltaSceneEngine deltaEngine = new DeltaSceneEngine(DeltaSettings.load());

    // Scene state holders so the reload handler can reach everything
    private static SceneLoader actLoader;
    private static SceneLoader bslLoader;

    // Entry point
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::createAndShowGui);
    }

    // ======================================================================
    //  GUI CONSTRUCTION
    // ======================================================================

    private static void createAndShowGui() {
        String pwd = System.getProperty("user.dir");

        // Main window
        JFrame window = new JFrame("Purdue FormulaSAE Flickbook");
        window.setSize(1700, 900);
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setLocationRelativeTo(null);

        //  Sim-selector sidebar
        //  Two rows: Active / Baseline, each with a path field + Browse btn + Reload btn at the bottom.
        //  The sidebar is collapsible via a toggle button on its left edge.

        Font fieldFont = new Font("Source Sans Pro", Font.PLAIN, 12);
        Font boldFont  = new Font("Source Sans Pro", Font.BOLD,  12);
        Font hintFont  = new Font("Source Sans Pro", Font.ITALIC, 11);

        JTextField actField   = new JTextField(pwd, 30);
        JTextField bslField   = new JTextField(pwd, 30);
        for (JTextField tf : new JTextField[]{actField, bslField})
            tf.setFont(fieldFont);

        JButton actBrowse   = browseButton(window, actField,   fieldFont);
        JButton bslBrowse   = browseButton(window, bslField,   fieldFont);

        JPanel sidebarContent = new JPanel(new GridBagLayout());
        sidebarContent.setBackground(new Color(245, 245, 245));
        sidebarContent.setBorder(new EmptyBorder(10, 8, 10, 8));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets  = new Insets(4, 3, 4, 3);
        gc.anchor  = GridBagConstraints.WEST;

        Object[][] rows = {
                {"Active",   COL_ACT,   actField,   actBrowse},
                {"Baseline", COL_BSL,   bslField,   bslBrowse},
        };

        for (int r = 0; r < rows.length; r++) {
            String role  = (String) rows[r][0];
            Color  col   = (Color)  rows[r][1];
            JTextField tf = (JTextField) rows[r][2];
            JButton    btn= (JButton)    rows[r][3];

            JLabel lbl = simLabel(role, col, boldFont);

            gc.gridy = r * 2;   // label row
            gc.gridx = 0; gc.gridwidth = 2; gc.weightx = 1;
            gc.fill  = GridBagConstraints.NONE;
            sidebarContent.add(lbl, gc);

            gc.gridy = r * 2 + 1;  // field + button row
            gc.gridx = 0; gc.gridwidth = 1; gc.weightx = 1;
            gc.fill  = GridBagConstraints.HORIZONTAL;
            sidebarContent.add(tf, gc);

            gc.gridx = 1; gc.weightx = 0;
            gc.fill  = GridBagConstraints.NONE;
            sidebarContent.add(btn, gc);
        }

        // Hint label
        JLabel hint = new JLabel("ERROR: Directory names MUST contain \"PF\"");
        hint.setFont(hintFont);
        hint.setForeground(Color.GRAY);
        gc.gridy = 4; gc.gridx = 0; gc.gridwidth = 2; gc.weightx = 1;
        gc.fill  = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(8, 3, 2, 3);
        sidebarContent.add(hint, gc);

        // Status label (shown after reload)
        JLabel statusLabel = new JLabel(" ");
        statusLabel.setFont(hintFont);
        statusLabel.setForeground(new Color(0x33, 0x88, 0x33));
        gc.gridy = 5; gc.insets = new Insets(0, 3, 4, 3);
        sidebarContent.add(statusLabel, gc);

        // Spacer to push Reload btn to bottom
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        gc.gridy = 6; gc.weighty = 1; gc.fill = GridBagConstraints.VERTICAL;
        sidebarContent.add(spacer, gc);
        gc.weighty = 0;

        // Reload btn
        JButton reloadBtn = new JButton("⟳  Load / Reload Sims");
        reloadBtn.setFont(boldFont);
        reloadBtn.setBackground(new Color(0x1A, 0x1A, 0x1A));
        reloadBtn.setForeground(Color.WHITE);
        reloadBtn.setOpaque(true);
        reloadBtn.setBorderPainted(false);
        reloadBtn.setFocusPainted(false);
        reloadBtn.setMargin(new Insets(6, 10, 6, 10));
        gc.gridy = 7; gc.gridx = 0; gc.gridwidth = 2;
        gc.fill  = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 3, 4, 3);
        sidebarContent.add(reloadBtn, gc);

        // Sidebar wrapper with a top border matching the app bg
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(280, 0));
        sidebar.setMinimumSize(new Dimension(280, 0));
        sidebar.add(sidebarContent, BorderLayout.CENTER);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(200, 200, 200)));

        // ImageDisplayPanel (starts empty)
        ImageDisplayPanel displayer = new ImageDisplayPanel(
                new File[0], new File[0], 0, "—", "—");
        displayer.setDeltaEngine(deltaEngine);

        // Right-click popup menu (built once, updated on reload)
        JPopupMenu mainMenu = new JPopupMenu();
        // Populated in rebuildPopupMenu() and called after each reload
        // We wrap in a holder so the lambda can swap it
        final JPopupMenu[] popupHolder = {mainMenu};

        // Reload action
        reloadBtn.addActionListener(e -> {
            String actPath = actField.getText().trim();
            String bslPath = bslField.getText().trim();

            // Resolve each path: empty or default pwd treated as "not provided"
            File actDir   = resolveSimDir(actPath, pwd);
            File bslDir   = resolveSimDir(bslPath, pwd);
            File deltaDir = null; // no longer collected from the sidebar — see buildMenuBar/rebuildPopupMenu

            if (actDir == null && bslDir == null) {
                statusLabel.setForeground(COL_BSL);
                statusLabel.setText("ERROR: At least one valid sim directory is required.");
                return;
            }

            // Validate non-null entries contain "PF"
            String err = null;
            if (actDir != null && !actDir.getName().contains("PF"))      err = "ERROR: Active path MUST contain \"PF\".";
            else if (bslDir != null && !bslDir.getName().contains("PF")) err = "ERROR: Baseline path MUST contain \"PF\".";

            if (err != null) {
                statusLabel.setForeground(COL_BSL);
                statusLabel.setText(err);
                return;
            }

            statusLabel.setForeground(new Color(0x33, 0x88, 0x33));
            statusLabel.setText("Loading...");
            reloadBtn.setEnabled(false);

            SwingWorker<Void, Void> worker = new SwingWorker<>() {
                @Override protected Void doInBackground() throws Exception {
                    actLoader   = actDir   != null ? new SceneLoader(actDir.getAbsolutePath())   : null;
                    bslLoader   = bslDir   != null ? new SceneLoader(bslDir.getAbsolutePath())   : null;
                    return null;
                }
                @Override protected void done() {
                    try {
                        get();

                        currentView = VIEWS[0];
                        currentVariable = "Total Pressure";
                        displayer.setCurrentVariable(currentVariable);

                        File[] actImages   = actLoader   != null ? actLoader.cptScenes.getImages(currentView)   : new File[0];
                        File[] bslImages   = bslLoader   != null ? bslLoader.cptScenes.getImages(currentView)   : new File[0];

                        String actName   = actDir   != null ? actDir.getName()   : "—";
                        String bslName   = bslDir   != null ? bslDir.getName()   : "—";

                        displayer.switchVariable(actImages, bslImages,
                                0, currentView, actName, bslName);

                        File safeActDir   = actDir   != null ? actDir   : new File(pwd);
                        File safeBslDir   = bslDir   != null ? bslDir   : new File(pwd);
                        File safeDeltaDir = deltaDir != null ? deltaDir : new File(pwd);
                        window.setJMenuBar(buildMenuBar(window, safeActDir, safeBslDir, safeDeltaDir));
                        rebuildPopupMenu(popupHolder[0], displayer, safeActDir, safeBslDir, safeDeltaDir);
                        window.revalidate();

                        statusLabel.setForeground(new Color(0x33, 0x88, 0x33));
                        statusLabel.setText("Loaded: " + actName);

                    } catch (Exception ex) {
                        ex.printStackTrace();
                        statusLabel.setForeground(COL_BSL);
                        statusLabel.setText("ERROR: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    } finally {
                        reloadBtn.setEnabled(true);
                    }
                }
            };
            worker.execute();
        });

        // ---- Popup (right-click on displayer) ----------------------------
        displayer.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e)  { maybeShowPopup(e); }
            public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) popupHolder[0].show(e.getComponent(), e.getX(), e.getY());
            }
        });

        setupKeyBindings(displayer);

        // Collapse toggle for sidebar
        JButton collapseBtn = new JButton("◀");
        collapseBtn.setFont(new Font("Source Sans Pro", Font.BOLD, 11));
        collapseBtn.setMargin(new Insets(60, 2, 60, 2));
        collapseBtn.setFocusPainted(false);
        collapseBtn.setToolTipText("Hide/Show Sim Selector");
        collapseBtn.addActionListener(ev -> {
            boolean nowVisible = !sidebar.isVisible();
            sidebar.setVisible(nowVisible);
            collapseBtn.setText(nowVisible ? "◀" : "▶");
            window.revalidate();
        });

        // Root layout
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(229, 229, 229));
        root.add(sidebar,      BorderLayout.WEST);
        root.add(collapseBtn,  BorderLayout.LINE_START);  // sits just right of sidebar
        root.add(displayer,    BorderLayout.CENTER);

        // collapseBtn needs to be on the east edge of the sidebar, not in LINE_START.
        JPanel leftStrip = new JPanel(new BorderLayout());
        leftStrip.setOpaque(false);
        leftStrip.add(sidebar,     BorderLayout.CENTER);
        leftStrip.add(collapseBtn, BorderLayout.EAST);

        root.remove(sidebar);
        root.remove(collapseBtn);
        root.add(leftStrip, BorderLayout.WEST);

        window.setContentPane(root);
        window.setJMenuBar(buildMenuBar(window, new File(pwd), new File(pwd), new File(pwd)));
        window.setVisible(true);
    }

    // ======================================================================
    //  POPUP MENU
    // ======================================================================

    private static void rebuildPopupMenu(JPopupMenu menu, ImageDisplayPanel displayer,
                                         File actDir, File bslDir, File deltaDir) {
        menu.removeAll();

        ActionListener menuListener = event -> {
            JMenuItem source    = (JMenuItem) event.getSource();
            JPopupMenu popup    = (JPopupMenu) source.getParent();
            JMenu parentMenu    = (JMenu) popup.getInvoker();
            String selectedView = event.getActionCommand();
            String selectedVar  = parentMenu.getText();

            int count = currentView.equals(selectedView) ? -1 : 0;
            if (!currentView.equals(selectedView)) currentView = selectedView;
            currentVariable = selectedVar;
            displayer.setCurrentVariable(currentVariable);

            if (actLoader == null && bslLoader == null) return;

            displayer.switchVariable(
                    getImages(actLoader, selectedVar, selectedView),
                    getImages(bslLoader, selectedVar, selectedView),
                    count, selectedView,
                    actDir.getName(), bslDir.getName());
        };

        for (String variable : VARIABLES) {
            JMenu variableMenu = new JMenu(variable);
            variableMenu.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
            for (String view : VIEWS) {
                JMenuItem viewItem = new JMenuItem(view);
                viewItem.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
                viewItem.addActionListener(menuListener);
                variableMenu.add(viewItem);
            }
            menu.add(variableMenu);
        }
    }

    // ======================================================================
    //  KEY BINDINGS
    // ======================================================================

    private static void setupKeyBindings(ImageDisplayPanel displayer) {
        InputMap  im = displayer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = displayer.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), ACTION_STREAM_DOWN);
        am.put(ACTION_STREAM_DOWN, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleStreamDown(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), ACTION_STREAM_UP);
        am.put(ACTION_STREAM_UP, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleStreamUp(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), ACTION_TOGGLE_ACTIVE);
        am.put(ACTION_TOGGLE_ACTIVE, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleActive(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), ACTION_TOGGLE_BASELINE);
        am.put(ACTION_TOGGLE_BASELINE, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleBaseline(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, 0), ACTION_TOGGLE_DELTA);
        am.put(ACTION_TOGGLE_DELTA, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleDelta(); }
        });

        // R, G, M, A, S — moved from ImageDisplayPanel's KeyListener to here so they
        // fire via WHEN_IN_FOCUSED_WINDOW and don't require the panel to hold focus
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "resetView");
        am.put("resetView", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.resetView(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), "toggleGrid");
        am.put("toggleGrid", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleGrid(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "toggleMirror");
        am.put("toggleMirror", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleMirror(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "toggleMirrorAxis");
        am.put("toggleMirrorAxis", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleMirrorAxis(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "toggleMirrorSide");
        am.put("toggleMirrorSide", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { displayer.toggleMirrorSide(); }
        });
    }

    // ======================================================================
    //  MENU BAR
    // ======================================================================

    private static JMenuBar buildMenuBar(JFrame window, File actDir, File bslDir, File deltaDir) {
        JMenuBar menuBar = new JMenuBar();
        JMenu dataMenu   = new JMenu("Data");
        dataMenu.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));

        JMenuItem plotsItem = new JMenuItem("Plots");
        plotsItem.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
        plotsItem.addActionListener(e -> {
            if (actLoader == null) {
                JOptionPane.showMessageDialog(window, "Atleast a single sim must be loaded first ...");
                return;
            }
            new PlotsViewer(window,
                    actDir.getName(),   actDir.getAbsolutePath(),
                    bslDir.getName(),   bslDir.getAbsolutePath(),
                    deltaDir.getName(), deltaDir.getAbsolutePath()).setVisible(true);
        });

        JMenu reportsMenu = new JMenu("Reports");
        reportsMenu.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));

        JMenuItem reportsAllItem = new JMenuItem("⬛  All Sims — Combined");
        reportsAllItem.setFont(new Font("Source Sans Pro", Font.BOLD, 13));
        reportsAllItem.addActionListener(e -> {
            if (actLoader == null) {
                JOptionPane.showMessageDialog(window, "Atleast a single sim must be loaded first ...");
                return;
            }
            new ReportsViewer(window,
                    actDir.getName(),   actDir.getAbsolutePath(),
                    bslDir.getName(),   bslDir.getAbsolutePath(),
                    deltaDir.getName(), deltaDir.getAbsolutePath()).setVisible(true);
        });
        reportsMenu.add(reportsAllItem);
        reportsMenu.addSeparator();

        String[]  simLabels = {"Active — " + actDir.getName(), "Baseline — " + bslDir.getName(), "Delta — " + deltaDir.getName()};
        File[]    simDirs   = {actDir, bslDir, deltaDir};
        for (int i = 0; i < 3; i++) {
            final File dir = simDirs[i];
            JMenuItem item = new JMenuItem(simLabels[i]);
            item.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
            item.addActionListener(e -> {
                if (actLoader == null) {
                    JOptionPane.showMessageDialog(window, "Atleast a single sim must be loaded first ...");
                    return;
                }
                new SingleSimReportsViewer(window, dir.getName(), dir.getAbsolutePath()).setVisible(true);
            });
            reportsMenu.add(item);
        }

        dataMenu.add(plotsItem);
        dataMenu.add(reportsMenu);
        menuBar.add(dataMenu);

        // Scenes menu
        JMenu scenesMenu = new JMenu("Scenes");
        scenesMenu.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));

        JMenuItem scenes3DItem = new JMenuItem("3D Scenes");
        scenes3DItem.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
        scenes3DItem.addActionListener(e -> {
            if (actLoader == null) {
                JOptionPane.showMessageDialog(window, "Atleast a single sim must be loaded first ...");
                return;
            }
            new Scenes3DViewer(window,
                    actDir.getName(),   actDir.getAbsolutePath(),
                    bslDir.getName(),   bslDir.getAbsolutePath(),
                    deltaDir.getName(), deltaDir.getAbsolutePath()).setVisible(true);
        });
        scenesMenu.add(scenes3DItem);
        menuBar.add(scenesMenu);

        // Settings menu
        JMenu settingsMenu = new JMenu("Settings");
        settingsMenu.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));

        JMenuItem deltaSettingsItem = new JMenuItem("Delta Colormaps...");
        deltaSettingsItem.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
        deltaSettingsItem.addActionListener(e ->
                new DeltaSettingsDialog(window, VARIABLES, deltaEngine).setVisible(true));
        settingsMenu.add(deltaSettingsItem);
        menuBar.add(settingsMenu);

        return menuBar;
    }

    // ======================================================================
    //  HELPERS
    // ======================================================================

    private static JButton browseButton(Component parent, JTextField target, Font font) {
        JButton btn = new JButton("Browse");
        btn.setFont(font);
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(target.getText());
            fc.setDialogTitle("Choose Sim Directory");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setAcceptAllFileFilterUsed(false);
            if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION)
                target.setText(fc.getSelectedFile().getAbsolutePath());
        });
        return btn;
    }

    private static JLabel simLabel(String role, Color color, Font font) {
        JLabel l = new JLabel("  " + role + "  ");
        l.setFont(font);
        l.setForeground(Color.WHITE);
        l.setBackground(color);
        l.setOpaque(true);
        l.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color.darker(), 1),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        return l;
    }

    /**
     * Returns the images for a given variable + view from a SceneLoader,
     * or an empty array if the loader is null (sim not loaded).
     */
    private static File[] getImages(SceneLoader loader, String variable, String view) {
        if (loader == null) return new File[0];
        switch (variable) {
            case "Total Pressure":          return loader.cptScenes.getImages(view);
            case "Pressure":                return loader.pressureScenes.getImages(view);
            case "Inwash":                  return loader.inwashScenes.getImages(view);
            case "Upwash":                  return loader.velZScenes.getImages(view);
            case "VISQ":                    return loader.vorticityScenes.getImages(view);
            case "Helicity":                return loader.helicityScenes.getImages(view);
            case "Pressure Variance":       return loader.pressureVarianceScenes.getImages(view);
            case "Total Pressure Variance": return loader.cptVarianceScenes.getImages(view);
            default:
                System.err.println("Unknown variable: " + variable);
                return new File[0];
        }
    }

    /**
     * Resolves a path string to a valid sim directory, or null if the path is
     * blank, equal to pwd (i.e. untouched default), or not a valid directory.
     */
    private static File resolveSimDir(String path, String pwd) {
        if (path == null || path.isEmpty() || path.equals(pwd)) return null;
        File f = new File(path);
        return f.isDirectory() ? f : null;
    }
}