import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

public class Main {

    //File Structure Constants
    private static final String[] VIEWS = {"AftFore", "TopBottom", "Profile"};
    private static final String[] VARIABLES = {"Inwash", "Pressure", "Total Pressure", "VISQ", "Velocity Z", "Helicity"};
    //Action Commands
    private static final String ACTION_STREAM_DOWN = "streamDown";
    private static final String ACTION_STREAM_UP = "streamUp";
    private static final String ACTION_TOGGLE_ACTIVE = "toggleActive";
    private static final String ACTION_TOGGLE_BASELINE = "toggleBaseline";
    private static final String ACTION_TOGGLE_DELTA = "toggleDelta";

    // View Tracker Variable
    private static String currentView = VIEWS[0]; // Initialize with default

    /**
     * Helper method to show a JFileChooser, validate the selection, and return the chosen directory.
     *
     * @param parent      The parent component for the dialog.
     * @param title       The dialog title.
     * @param startPath   The initial directory path.
     * @param validationSubstring String that the directory name must contain (just put this here incase it ever changes)
     * @return The selected directory File (or null)
     */
    private static File selectSimulationDirectory(Component parent, String title, String startPath, String validationSubstring) {
        JFileChooser fileChooser = new JFileChooser(startPath);
        fileChooser.setDialogTitle(title);
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setAcceptAllFileFilterUsed(false); // Only allow directories

        File selectedFile = null;
        boolean validSelection = false;

        while (!validSelection) {
            int result = fileChooser.showOpenDialog(parent);

            if (result == JFileChooser.APPROVE_OPTION) {
                selectedFile = fileChooser.getSelectedFile();
                // Validate: Check if null and if name contains the required substring
                if (selectedFile != null && (validationSubstring == null || selectedFile.getName().contains(validationSubstring))) {
                    System.out.println("Selected " + title + ": " + selectedFile.getAbsolutePath());
                    validSelection = true;
                } else {
                    String validationMsg = validationSubstring != null ? "Directory name must contain '" + validationSubstring + "'." : "Invalid selection.";
                    JOptionPane.showMessageDialog(parent,
                            "Invalid directory selected. " + validationMsg,
                            "Selection Error", JOptionPane.WARNING_MESSAGE);
                    // Loop continues, JFileChooser will reopen
                    selectedFile = null; // Reset selected file if invalid
                }
            } else { // User cancelled or closed the dialog
                System.out.println(title + " selection cancelled by user.");
                return null;
            }
        }
        return selectedFile;
    }

    /**
     * Sets up Key Bindings for the ImageDisplayPanel.
     * @param displayer The panel to attach key bindings to.
     */
    private static void setupKeyBindings(ImageDisplayPanel displayer) {
        InputMap inputMap = displayer.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = displayer.getActionMap();

        // Right Arrow -> Stream Down
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), ACTION_STREAM_DOWN);
        actionMap.put(ACTION_STREAM_DOWN, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                displayer.toggleStreamDown();
            }
        });

        // Left Arrow -> Stream Up
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), ACTION_STREAM_UP);
        actionMap.put(ACTION_STREAM_UP, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                displayer.toggleStreamUp();
            }
        });

        // '1' Key -> Toggle Active
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), ACTION_TOGGLE_ACTIVE);
        actionMap.put(ACTION_TOGGLE_ACTIVE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                displayer.toggleActive();
            }
        });

        // '2' Key -> Toggle Baseline
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), ACTION_TOGGLE_BASELINE);
        actionMap.put(ACTION_TOGGLE_BASELINE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                displayer.toggleBaseline();
            }
        });

        // 'D' Key -> Toggle Delta
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, 0), ACTION_TOGGLE_DELTA);
        actionMap.put(ACTION_TOGGLE_DELTA, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                displayer.toggleDelta();
            }
        });
    }


    /**
     * Main Class
     * Uses SwingUtilities.invokeLater to ensure GUI operations run on the EDT.
     */
    public static void main(String[] args) {
        // Run GUI setup on the Event Dispatch Thread
        SwingUtilities.invokeLater(() -> createAndShowGui());
    }

    /**
     * Creates and shows the main application GUI.
     */
    private static void createAndShowGui() {
        String pwd = System.getProperty("user.dir");

        // --- File Selection ---
        File actDir = selectSimulationDirectory(null, "Choose Active Sim CM", pwd, "PF");
        if (actDir == null) {
            System.out.println("Active sim selection required. Program Terminated.");
            System.exit(0);
        }

        File bslDir = selectSimulationDirectory(null, "Choose Baseline Sim CM", pwd, "PF");
        if (bslDir == null) {
            System.out.println("Baseline sim selection required. Program Terminated.");
            System.exit(0);
        }

        File deltaDir = selectSimulationDirectory(null, "Choose Delta Sim CM", pwd, "PF");
        if (deltaDir == null) {
            System.out.println("Delta sim selection required. Program Terminated.");
            System.exit(0);
        }

        // --- Window Setup ---
        JFrame window = new JFrame();
        window.setTitle("Purdue FormulaSAE Flickbook");
        window.setSize(1600, 900);
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.getContentPane().setBackground(new Color(229, 229, 229));

        // --- Scene Loading ---
        SceneLoader actLoader = new SceneLoader(actDir.getAbsolutePath());
        SceneLoader bslLoader = new SceneLoader(bslDir.getAbsolutePath());
        SceneLoader deltaLoader = new SceneLoader(deltaDir.getAbsolutePath());

        // --- Image Display Panel Setup ---
        // Initialize with the default view
        ImageDisplayPanel displayer = new ImageDisplayPanel(
                actLoader.cptScenes.getImages(currentView), // Default to 'Total Pressure' initially
                bslLoader.cptScenes.getImages(currentView),
                deltaLoader.cptScenes.getImages(currentView),
                0,
                actDir.getName(),
                bslDir.getName(),
                deltaDir.getName()
        );

        // --- Popup Menu Setup ---
        JPopupMenu mainMenu = new JPopupMenu();

        ActionListener menuListener = event -> {
            JMenuItem source = (JMenuItem) event.getSource();
            JPopupMenu popupMenu = (JPopupMenu) source.getParent();
            JMenu parentMenu = (JMenu) popupMenu.getInvoker(); // Get the submenu that was selected

            String selectedView = event.getActionCommand(); // View name
            String selectedVariable = parentMenu.getText(); // Variable name

            System.out.println("Selected Variable: " + selectedVariable);
            System.out.println("Selected View: " + selectedView);

            // Determine if streamCount needs resetting (0) or preserving (-1)
            int count = currentView.equals(selectedView) ? -1 : 0;

            if (!currentView.equals(selectedView)) {
                currentView = selectedView; // Update the current view state
            }

            // Use SceneLoader instances
            VariableScenes actScenes;
            VariableScenes bslScenes;
            VariableScenes deltaScenes;
            switch (selectedVariable) {
                case "Total Pressure":
                    actScenes = actLoader.cptScenes;
                    bslScenes = bslLoader.cptScenes;
                    deltaScenes = deltaLoader.cptScenes;
                    break;
                case "Pressure":
                    actScenes = actLoader.pressureScenes;
                    bslScenes = bslLoader.pressureScenes;
                    deltaScenes = deltaLoader.pressureScenes;
                    break;
                case "Inwash":
                    actScenes = actLoader.inwashScenes;
                    bslScenes = bslLoader.inwashScenes;
                    deltaScenes = deltaLoader.inwashScenes;
                    break;
                case "Velocity Z":
                    actScenes = actLoader.velZScenes;
                    bslScenes = bslLoader.velZScenes;
                    deltaScenes = deltaLoader.velZScenes;
                    break;
                case "VISQ":
                    actScenes = actLoader.vorticityScenes;
                    bslScenes = bslLoader.vorticityScenes;
                    deltaScenes = deltaLoader.vorticityScenes;
                    break;
                case "Helicity":
                    actScenes = actLoader.helicityScenes;
                    bslScenes = bslLoader.helicityScenes;
                    deltaScenes = deltaLoader.helicityScenes;
                    break;
                default:
                    System.err.println("Unknown variable selected: " + selectedVariable);
                    return;
            }

            displayer.switchVariable(
                    actScenes.getImages(selectedView),
                    bslScenes.getImages(selectedView),
                    deltaScenes.getImages(selectedView),
                    count, selectedView);
        };

        // Popup Menu
        for (String variable : VARIABLES) {
            JMenu variableMenu = new JMenu(variable); // Submenu for each variable
            for (String view : VIEWS) {
                JMenuItem viewItem = new JMenuItem(view);
                viewItem.addActionListener(menuListener);
                variableMenu.add(viewItem);
            }
            mainMenu.add(variableMenu);
        }

        // --- Mouse Listener for Popup ---
        MouseListener popupListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                // Show popup on right-click
                if (e.isPopupTrigger()) {
                    mainMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };

        displayer.addMouseListener(popupListener);
        setupKeyBindings(displayer); // Setup key bindings

        // --- Finalize Window ---
        window.add(displayer);
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }
}