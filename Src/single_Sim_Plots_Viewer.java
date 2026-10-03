import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * A dialog that browses .png files from a sim's "Plots" subfolder.
 * Left panel: scrollable list of plot filenames.
 * Right panel: zoomable/pannable image display.
 */
public class SingleSimPlotsViewer extends JDialog {

    private static final String PLOTS_DIR = "Plots";

    private final File[] plotFiles;
    private BufferedImage currentImage = null;

    // Zoom / pan state
    private double zoomFactor = 1.0;
    private Point imageOffset = new Point(0, 0);
    private Point dragStart = null;

    // Inner canvas
    private final JPanel canvas;

    public SingleSimPlotsViewer(Frame owner, String simName, String simPath) {
        super(owner, "Plots — " + simName, false); // non-modal so main window stays usable
        setSize(1400, 860);
        setLocationRelativeTo(owner);
        setBackground(new Color(229, 229, 229));

        // --- Discover plot files ---
        File plotsDir = new File(simPath, PLOTS_DIR);
        File[] found = plotsDir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".png"));
        if (found == null) found = new File[0];
        Arrays.sort(found, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        this.plotFiles = found;

        // ---- Left: file list ----
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (File f : plotFiles) listModel.addElement(f.getName());

        JList<String> fileList = new JList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setFont(new Font("Source Sans Pro", Font.PLAIN, 13));
        fileList.setBackground(new Color(245, 245, 245));
        fileList.setFixedCellHeight(24);

        JScrollPane listScroll = new JScrollPane(fileList);
        listScroll.setPreferredSize(new Dimension(260, 0));
        listScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(180, 180, 180)));

        // ---- Right: image canvas ----
        canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (currentImage == null) {
                    g.setFont(new Font("Source Sans Pro", Font.ITALIC, 14));
                    g.setColor(Color.DARK_GRAY);
                    String msg = plotFiles.length == 0 ? "No plots found in " + plotsDir.getPath() : "Select a plot from the list.";
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
                    return;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

                int pw = getWidth(), ph = getHeight();
                int iw = currentImage.getWidth(), ih = currentImage.getHeight();
                double base = Math.min((double) pw / iw, (double) ph / ih);
                double scale = base * zoomFactor;
                int nw = (int) (iw * scale), nh = (int) (ih * scale);
                int x = (pw - nw) / 2 + imageOffset.x;
                int y = (ph - nh) / 2 + imageOffset.y;
                g2.drawImage(currentImage, x, y, nw, nh, this);
                g2.dispose();
            }
        };
        canvas.setBackground(new Color(229, 229, 229));

        // Zoom
        canvas.addMouseWheelListener(e -> {
            double prev = zoomFactor;
            if (e.getPreciseWheelRotation() < 0) zoomFactor = Math.min(zoomFactor + 0.1, 10.0);
            else zoomFactor = Math.max(0.1, zoomFactor - 0.1);

            if (currentImage == null) return;
            if (zoomFactor <= 1.0) { imageOffset = new Point(0, 0); canvas.repaint(); return; }

            Point mp = e.getPoint();
            int pw = canvas.getWidth(), ph = canvas.getHeight();
            int iw = currentImage.getWidth(), ih = currentImage.getHeight();
            double base = Math.min((double) pw / iw, (double) ph / ih);
            double pScW = iw * base * prev, pScH = ih * base * prev;
            double nScW = iw * base * zoomFactor, nScH = ih * base * zoomFactor;
            double px = (pw - pScW) / 2.0 + imageOffset.x;
            double py = (ph - pScH) / 2.0 + imageOffset.y;
            double rx = (mp.x - px) / pScW, ry = (mp.y - py) / pScH;
            imageOffset.x = (int) (mp.x - rx * nScW - (pw - nScW) / 2.0);
            imageOffset.y = (int) (mp.y - ry * nScH - (ph - nScH) / 2.0);
            canvas.repaint();
        });

        // Pan
        canvas.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) dragStart = e.getPoint(); }
            public void mouseReleased(MouseEvent e) { dragStart = null; }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && dragStart != null) {
                    imageOffset.translate(e.getX() - dragStart.x, e.getY() - dragStart.y);
                    dragStart = e.getPoint();
                    canvas.repaint();
                }
            }
        });

        // Reset view on 'R'
        canvas.setFocusable(true);
        canvas.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R) { zoomFactor = 1.0; imageOffset = new Point(0,0); canvas.repaint(); }
            }
        });

        // List selection -> load image
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && fileList.getSelectedIndex() >= 0) {
                loadImage(plotFiles[fileList.getSelectedIndex()]);
            }
        });

        // Double click to open in system viewer
        fileList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && fileList.getSelectedIndex() >= 0) {
                    try { Desktop.getDesktop().open(plotFiles[fileList.getSelectedIndex()]); }
                    catch (IOException ex) { JOptionPane.showMessageDialog(SingleSimPlotsViewer.this, "Cannot open file: " + ex.getMessage()); }
                }
            }
        });

        // Keyboard nav in list
        InputMap im = fileList.getInputMap(JComponent.WHEN_FOCUSED);
        // default UP/DOWN already moves selection; repaint is triggered by listener above

        // ---- Status bar ----
        JLabel statusLabel = new JLabel(" " + plotFiles.length + " plot(s) found   |   Scroll to zoom • Drag to pan • R to reset • Double-click to open externally");
        statusLabel.setFont(new Font("Source Sans Pro", Font.ITALIC, 12));
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setBorder(new EmptyBorder(3, 6, 3, 6));

        // ---- Layout ----
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, canvas);
        split.setDividerLocation(260);
        split.setDividerSize(4);
        split.setBorder(null);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        // Select first item automatically
        if (plotFiles.length > 0) {
            fileList.setSelectedIndex(0);
        }
    }

    private void loadImage(File file) {
        zoomFactor = 1.0;
        imageOffset = new Point(0, 0);
        SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
            protected BufferedImage doInBackground() throws Exception { return ImageIO.read(file); }
            protected void done() {
                try { currentImage = get(); }
                catch (Exception ex) { currentImage = null; System.err.println("Failed to load plot: " + file); }
                canvas.repaint();
            }
        };
        worker.execute();
    }
}
