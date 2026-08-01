import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Dialog for viewing 3D scene images across Active, Baseline, and Delta sims.
 *
 * Left panel  : scrollable list of scene keys, grouped by variable with headers.
 * Right panel : image display with Active / Baseline / Delta toggle (keys 1, 2, D)
 *               and "Filtered" toggle (key F), plus zoom/pan.
 *
 * Keyboard shortcuts:
 *   1       — show Active
 *   2       — show Baseline
 *   D       — show Delta
 *   F       — toggle Filtered view
 *   R       — reset zoom/pan
 */
public class Scenes3DViewer extends JDialog {

    // Sim colours — same as the rest of the app
    private static final Color COL_ACT   = new Color(0x1A, 0x1A, 0x1A);
    private static final Color COL_BSL   = new Color(0xCC, 0x11, 0x11);
    private static final Color COL_DELTA = new Color(0xCF, 0xA1, 0x00);

    private enum SimSlot { ACTIVE, BASELINE, DELTA }

    private final SceneLoader3D actLoader;
    private final SceneLoader3D bslLoader;
    private final SceneLoader3D deltaLoader;

    private final String actName;
    private final String bslName;
    private final String deltaName;

    // Current display state
    private SimSlot   currentSlot  = SimSlot.ACTIVE;
    private boolean   showFiltered = false;
    private String    currentKey   = null;

    // Loaded images for the current key
    private BufferedImage actNormal     = null;
    private BufferedImage actFiltered   = null;
    private BufferedImage bslNormal     = null;
    private BufferedImage bslFiltered   = null;
    private BufferedImage deltaNormal   = null;
    private BufferedImage deltaFiltered = null;

    // Zoom / pan
    private double zoomFactor  = 1.0;
    private Point  imageOffset = new Point(0, 0);
    private Point  dragStart   = null;

    // UI components
    private final JPanel canvas;

    private final Font uiFont     = new Font("Source Sans Pro", Font.PLAIN,  13);
    private final Font headerFont = new Font("Source Sans Pro", Font.BOLD,   12);

    // -------------------------------------------------------------------------

    public Scenes3DViewer(Frame owner,
                          String actName,   String actPath,
                          String bslName,   String bslPath,
                          String deltaName, String deltaPath) {
        super(owner, "3D Scenes", false);
        setSize(1500, 900);
        setLocationRelativeTo(owner);

        this.actName   = actName;
        this.bslName   = bslName;
        this.deltaName = deltaName;

        // Load 3D scene metadata for each sim (no image I/O yet)
        actLoader   = new SceneLoader3D(actPath);
        bslLoader   = new SceneLoader3D(bslPath);
        deltaLoader = new SceneLoader3D(deltaPath);

        // Union of all scene keys, preserving variable-grouped order
        Set<String> allKeys = new LinkedHashSet<>();
        for (SceneLoader3D loader : new SceneLoader3D[]{actLoader, bslLoader, deltaLoader})
            for (SceneLoader3D.Scene3D s : loader.scenes) allKeys.add(s.key);

        Map<String, List<String>> byVariable = new LinkedHashMap<>();
        for (String key : allKeys) {
            String var = key.contains(" / ") ? key.substring(0, key.indexOf(" / ")) : key;
            byVariable.computeIfAbsent(var, v -> new ArrayList<>()).add(key);
        }

        // ---- Left: scene list -------------------------------------------
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (Map.Entry<String, List<String>> entry : byVariable.entrySet()) {
            listModel.addElement("\u00A7" + entry.getKey()); // § = section header
            for (String key : entry.getValue()) listModel.addElement(key);
        }

        JList<String> sceneList = new JList<>(listModel);
        sceneList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sceneList.setFont(uiFont);
        sceneList.setBackground(new Color(245, 245, 245));
        sceneList.setFixedCellHeight(24);
        sceneList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int idx, boolean sel, boolean focus) {
                String item = (String) value;
                boolean isHeader = item.startsWith("\u00A7");
                super.getListCellRendererComponent(list, isHeader ? item.substring(1) : item,
                        idx, sel && !isHeader, focus);
                if (isHeader) {
                    setBackground(new Color(225, 225, 225));
                    setForeground(new Color(60, 60, 60));
                    setFont(headerFont);
                    setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(200, 200, 200)));
                } else {
                    setBorder(new EmptyBorder(0, 16, 0, 0));
                    if (!sel) {
                        setBackground(new Color(245, 245, 245));
                        setForeground(Color.DARK_GRAY);
                    }
                    setFont(uiFont);
                }
                return this;
            }
        });

        sceneList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int idx = sceneList.getSelectedIndex();
            if (idx < 0) return;
            String item = listModel.getElementAt(idx);
            if (item.startsWith("\u00A7")) { sceneList.clearSelection(); return; }
            selectScene(item);
        });

        JScrollPane listScroll = new JScrollPane(sceneList);
        listScroll.setPreferredSize(new Dimension(270, 0));
        listScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(180, 180, 180)));

        // ---- Keybind hint bar (replaces toggle buttons) -----------------
        JLabel hintBar = new JLabel(
                "  \u2022  1  Active   \u2022  2  Baseline   \u2022  D  Delta   \u2022  F  Filtered   \u2022  R  Reset view   \u2022  Scroll to zoom   \u2022  Drag to pan");
        hintBar.setFont(new Font("Source Sans Pro", Font.ITALIC, 12));
        hintBar.setForeground(new Color(90, 90, 90));
        hintBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 200, 200)),
                new EmptyBorder(5, 6, 5, 6)));

        // ---- Canvas -----------------------------------------------------
        canvas = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawScene(g);
            }
        };
        canvas.setBackground(new Color(229, 229, 229));
        canvas.setFocusable(true);

        canvas.addMouseWheelListener(e -> {
            double prev = zoomFactor;
            if (e.getPreciseWheelRotation() < 0) zoomFactor = Math.min(zoomFactor + 0.1, 10.0);
            else zoomFactor = Math.max(0.1, zoomFactor - 0.1);
            if (zoomFactor <= 1.0) { imageOffset = new Point(0, 0); repaintCanvas(); return; }
            BufferedImage img = getCurrentImage();
            if (img == null) { repaintCanvas(); return; }
            Point mp = e.getPoint();
            int pw = canvas.getWidth(), ph = canvas.getHeight();
            double base = Math.min((double)pw / img.getWidth(), (double)ph / img.getHeight());
            double pW = img.getWidth() * base * prev, pH = img.getHeight() * base * prev;
            double nW = img.getWidth() * base * zoomFactor, nH = img.getHeight() * base * zoomFactor;
            double px = (pw - pW) / 2.0 + imageOffset.x, py = (ph - pH) / 2.0 + imageOffset.y;
            double rx = (mp.x - px) / pW, ry = (mp.y - py) / pH;
            imageOffset.x = (int)(mp.x - rx * nW - (pw - nW) / 2.0);
            imageOffset.y = (int)(mp.y - ry * nH - (ph - nH) / 2.0);
            repaintCanvas();
        });
        canvas.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) { dragStart = e.getPoint(); canvas.requestFocusInWindow(); }
            }
            public void mouseReleased(MouseEvent e) { dragStart = null; }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && dragStart != null) {
                    imageOffset.translate(e.getX() - dragStart.x, e.getY() - dragStart.y);
                    dragStart = e.getPoint();
                    repaintCanvas();
                }
            }
        });

        setupKeyBindings();

        // ---- Right panel ------------------------------------------------
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(hintBar,   BorderLayout.NORTH);
        rightPanel.add(canvas,    BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightPanel);
        split.setDividerLocation(270);
        split.setDividerSize(4);
        split.setBorder(null);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);

        // Auto-select first non-header entry
        for (int i = 0; i < listModel.getSize(); i++) {
            if (!listModel.getElementAt(i).startsWith("\u00A7")) {
                sceneList.setSelectedIndex(i);
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Scene selection & async loading
    // -------------------------------------------------------------------------

    private void selectScene(String key) {
        if (key.equals(currentKey)) return;
        currentKey = key;
        zoomFactor = 1.0;
        imageOffset = new Point(0, 0);
        actNormal = actFiltered = bslNormal = bslFiltered = deltaNormal = deltaFiltered = null;
        repaintCanvas();
        loadImagesAsync(key);
    }

    private void loadImagesAsync(String key) {
        SceneLoader3D.Scene3D aS = actLoader.findByKey(key);
        SceneLoader3D.Scene3D bS = bslLoader.findByKey(key);
        SceneLoader3D.Scene3D dS = deltaLoader.findByKey(key);

        new SwingWorker<BufferedImage[], Void>() {
            @Override protected BufferedImage[] doInBackground() {
                return new BufferedImage[]{
                        load(aS != null ? aS.normal    : null),
                        load(aS != null ? aS.filtered  : null),
                        load(bS != null ? bS.normal    : null),
                        load(bS != null ? bS.filtered  : null),
                        load(dS != null ? dS.normal    : null),
                        load(dS != null ? dS.filtered  : null),
                };
            }
            @Override protected void done() {
                if (!key.equals(currentKey)) return;
                try {
                    BufferedImage[] imgs = get();
                    actNormal    = imgs[0]; actFiltered   = imgs[1];
                    bslNormal    = imgs[2]; bslFiltered   = imgs[3];
                    deltaNormal  = imgs[4]; deltaFiltered = imgs[5];
                } catch (Exception ex) { ex.printStackTrace(); }
                repaintCanvas();
            }
        }.execute();
    }

    private BufferedImage load(File f) {
        if (f == null || !f.exists()) return null;
        try { return ImageIO.read(f); } catch (Exception e) { return null; }
    }

    // -------------------------------------------------------------------------
    //  Drawing
    // -------------------------------------------------------------------------

    private BufferedImage getCurrentImage() {
        switch (currentSlot) {
            case ACTIVE:   return showFiltered ? actFiltered   : actNormal;
            case BASELINE: return showFiltered ? bslFiltered   : bslNormal;
            case DELTA:    return showFiltered ? deltaFiltered : deltaNormal;
            default:       return null;
        }
    }

    private String getCurrentSimName() {
        switch (currentSlot) {
            case ACTIVE:   return actName;
            case BASELINE: return bslName;
            case DELTA:    return deltaName;
            default:       return "\u2014";
        }
    }

    private Color getCurrentSimColor() {
        switch (currentSlot) {
            case ACTIVE:   return COL_ACT;
            case BASELINE: return COL_BSL;
            case DELTA:    return COL_DELTA;
            default:       return Color.DARK_GRAY;
        }
    }

    private void repaintCanvas() { canvas.repaint(); }

    private void drawScene(Graphics g) {
        BufferedImage img = getCurrentImage();
        int pw = canvas.getWidth(), ph = canvas.getHeight();

        if (img == null) {
            g.setColor(new Color(120, 120, 120));
            g.setFont(new Font("Source Sans Pro", Font.ITALIC, 14));
            String msg;
            if (currentKey == null) {
                msg = "Select a scene from the list.";
            } else {
                msg = sceneExistsForCurrentSlot() ? "Loading\u2026" : "Scene not available for this sim.";
            }
            FontMetrics fm = g.getFontMetrics();
            g.drawString(msg, (pw - fm.stringWidth(msg)) / 2, ph / 2);
        } else {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int iw = img.getWidth(), ih = img.getHeight();
            double base = Math.min((double)pw / iw, (double)ph / ih);
            int nw = (int)(iw * base * zoomFactor), nh = (int)(ih * base * zoomFactor);
            g2.drawImage(img, (pw - nw)/2 + imageOffset.x, (ph - nh)/2 + imageOffset.y, nw, nh, canvas);
            g2.dispose();
        }

        drawAnnotation(g, img);
    }

    private boolean sceneExistsForCurrentSlot() {
        if (currentKey == null) return false;
        SceneLoader3D loader = currentSlot == SimSlot.ACTIVE   ? actLoader
                : currentSlot == SimSlot.BASELINE ? bslLoader : deltaLoader;
        SceneLoader3D.Scene3D s = loader.findByKey(currentKey);
        if (s == null) return false;
        return (showFiltered ? s.filtered : s.normal) != null;
    }

    private void drawAnnotation(Graphics g, BufferedImage img) {
        Graphics2D ag = (Graphics2D) g.create();
        ag.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        ag.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        Font textFont = new Font("Source Sans Pro", Font.ITALIC, 14);
        ag.setFont(textFont);
        FontMetrics fm = ag.getFontMetrics();

        String line1 = getCurrentSimName() + (img == null ? "  [not loaded]" : "");
        String line2 = (currentKey != null ? currentKey : "No scene selected")
                + (showFiltered ? "  [Filtered]" : "");

        int pH = 8, pV = 6, gap = 3;
        int lH = fm.getAscent() + fm.getDescent();
        int bW = Math.max(fm.stringWidth(line1), fm.stringWidth(line2)) + pH * 2;
        int bH = lH * 2 + gap + pV * 2;

        ag.setColor(Color.WHITE);
        ag.fillRoundRect(5, 5, bW, bH, 8, 8);
        ag.setStroke(new BasicStroke(1.5f));
        ag.setColor(Color.BLACK);
        ag.drawRoundRect(5, 5, bW, bH, 8, 8);
        ag.setColor(getCurrentSimColor());
        ag.drawRoundRect(7, 7, bW - 4, bH - 4, 5, 5);
        ag.setColor(Color.BLACK);
        ag.drawString(line1, 5 + pH, 5 + pV + fm.getAscent());
        ag.drawString(line2, 5 + pH, 5 + pV + fm.getAscent() + lH + gap);
        ag.dispose();
    }

    // -------------------------------------------------------------------------
    //  Key bindings
    // -------------------------------------------------------------------------

    private void setupKeyBindings() {
        InputMap  im = canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = canvas.getActionMap();

        bind(im, am, KeyEvent.VK_1, "3d_active",   () -> { currentSlot = SimSlot.ACTIVE;   repaintCanvas(); });
        bind(im, am, KeyEvent.VK_2, "3d_baseline", () -> { currentSlot = SimSlot.BASELINE; repaintCanvas(); });
        bind(im, am, KeyEvent.VK_D, "3d_delta",    () -> { currentSlot = SimSlot.DELTA;    repaintCanvas(); });
        bind(im, am, KeyEvent.VK_F, "3d_filtered", () -> {
            showFiltered = !showFiltered;
            repaintCanvas();
        });
        bind(im, am, KeyEvent.VK_R, "3d_reset", () -> {
            zoomFactor = 1.0; imageOffset = new Point(0, 0); repaintCanvas();
        });
    }

    private void bind(InputMap im, ActionMap am, int key, String name, Runnable action) {
        im.put(KeyStroke.getKeyStroke(key, 0), name);
        am.put(name, new AbstractAction() {
            public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

}