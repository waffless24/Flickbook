import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class ImageDisplayPanel extends JPanel {
    private File[] actSceneFiles;
    private File[] bslSceneFiles;

    private BufferedImage currentActImage;
    private BufferedImage currentBslImage;
    private BufferedImage currentDeltaImage;

    private boolean actSceneToggle = true;
    private boolean deltaSceneToggle = false;
    private final String actSimName;
    private final String bslSimName;
    private int totalCount;
    private int streamCount;

    private double zoomFactor = 1.0;
    private double zoomIncrement = 0.1;

    private Point dragStartScreen = null;
    private Point imageOffset = new Point(0, 0);

    private int lastImageWidth = 0;
    private int lastImageHeight = 0;

    private boolean showGrid = false;

    private boolean mirrorMode = false;
    private boolean mirrorVertical = true;
    private boolean mirrorFirstHalf = true;

    private final Font textFont = new Font("Source Sans Pro", Font.ITALIC, 15);
    private String selectedView;

    private String actSimNameOverride   = null;
    private String bslSimNameOverride   = null;

    // ---- On-the-fly delta scene generation --------------------------------
    private DeltaSceneEngine deltaEngine = null;
    private String currentVariable = "Total Pressure";
    private boolean deltaGenerating = false;
    private String deltaError = null;

    public ImageDisplayPanel(File[] actScene, File[] bslScene, int count,
                             String actSimName, String bslSimName) {
        this.actSceneFiles = actScene != null ? actScene : new File[0];
        this.bslSceneFiles = bslScene != null ? bslScene : new File[0];
        this.streamCount = count;
        int maxLen = Math.max(this.actSceneFiles.length, this.bslSceneFiles.length);
        this.totalCount = maxLen > 0 ? maxLen - 1 : -1;
        this.actSimName = actSimName;
        this.bslSimName = bslSimName;

        loadCurrentImageAsync();

        addMouseWheelListener(e -> {
            double prevZoom = zoomFactor;
            if (e.getPreciseWheelRotation() < 0) {
                zoomFactor += zoomIncrement;
            } else {
                zoomFactor = Math.max(zoomIncrement, zoomFactor - zoomIncrement);
            }

            if (zoomFactor <= 1.0) {
                resetView();
                return;
            }

            Point mousePos = e.getPoint();
            int panelWidth  = getWidth();
            int panelHeight = getHeight();

            if (currentActImage == null && currentBslImage == null && currentDeltaImage == null) return;

            BufferedImage img = (deltaSceneToggle ? currentDeltaImage :
                    actSceneToggle ? currentActImage : currentBslImage);
            if (img == null) return;

            int imgWidth  = img.getWidth();
            int imgHeight = img.getHeight();

            double baseScale       = Math.min((double) panelWidth / imgWidth, (double) panelHeight / imgHeight);
            double prevScaledWidth  = imgWidth  * baseScale * prevZoom;
            double prevScaledHeight = imgHeight * baseScale * prevZoom;
            double newScaledWidth   = imgWidth  * baseScale * zoomFactor;
            double newScaledHeight  = imgHeight * baseScale * zoomFactor;

            double prevX = (panelWidth  - prevScaledWidth)  / 2.0 + imageOffset.x;
            double prevY = (panelHeight - prevScaledHeight) / 2.0 + imageOffset.y;

            double relX = (mousePos.getX() - prevX) / prevScaledWidth;
            double relY = (mousePos.getY() - prevY) / prevScaledHeight;

            double newX = mousePos.getX() - relX * newScaledWidth;
            double newY = mousePos.getY() - relY * newScaledHeight;

            imageOffset.x = (int) (newX - (panelWidth  - newScaledWidth)  / 2.0);
            imageOffset.y = (int) (newY - (panelHeight - newScaledHeight) / 2.0);

            clampImageOffset();
            repaint();
        });

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) dragStartScreen = e.getPoint();
            }
            @Override public void mouseReleased(MouseEvent e) { dragStartScreen = null; }
        });

        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && dragStartScreen != null) {
                    Point dragEnd = e.getPoint();
                    imageOffset.translate(dragEnd.x - dragStartScreen.x, dragEnd.y - dragStartScreen.y);
                    clampImageOffset();
                    dragStartScreen = dragEnd;
                    repaint();
                }
            }
        });

        // R, G, M, A, S handled in Main.setupKeyBindings() via WHEN_IN_FOCUSED_WINDOW
        setFocusable(true);
    }

    // ---- Mirror ----------------------------------------------------------

    private BufferedImage createMirroredImage(BufferedImage source) {
        if (source == null) return null;
        int width  = source.getWidth();
        int height = source.getHeight();
        int type   = source.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : source.getType();
        BufferedImage out = new BufferedImage(width, height, type);
        Graphics2D g2d = out.createGraphics();

        if (mirrorVertical) {
            int midX = 2010;
            if (mirrorFirstHalf) {
                g2d.drawImage(source, midX, 0, width, height, midX, 0, width, height, null);
                g2d.drawImage(source, 0, 0, midX, height, width, 0, midX, height, null);
            } else {
                g2d.drawImage(source, 0, 0, midX, height, 0, 0, midX, height, null);
                g2d.drawImage(source, midX, 0, width, height, midX, 0, 0, height, null);
            }
        } else {
            int midY = 1061;
            if (mirrorFirstHalf) {
                g2d.drawImage(source, 0, 0, width, midY, 0, 0, width, midY, null);
                g2d.drawImage(source, 0, midY, width, height, 0, midY, width, 0, null);
            } else {
                g2d.drawImage(source, 0, midY, width, height, 0, midY, width, height, null);
                g2d.drawImage(source, 0, 0, width, midY, 0, height, width, midY, null);
            }
        }

        g2d.dispose();
        return out;
    }

    // ---- Async loading ---------------------------------------------------

    private void loadCurrentImageAsync() {
        if (totalCount < 0 || streamCount < 0 || streamCount > totalCount) {
            currentActImage   = null;
            currentBslImage   = null;
            currentDeltaImage = null;
            repaint();
            return;
        }

        File actFile = (actSceneFiles.length > streamCount) ? actSceneFiles[streamCount] : null;
        File bslFile = (bslSceneFiles.length > streamCount) ? bslSceneFiles[streamCount] : null;

        new SwingWorker<BufferedImage[], Void>() {
            @Override protected BufferedImage[] doInBackground() {
                return new BufferedImage[]{
                        loadImageFromFile(actFile),
                        loadImageFromFile(bslFile)
                };
            }
            @Override protected void done() {
                try {
                    BufferedImage[] result = get();
                    currentActImage = result[0];
                    currentBslImage = result[1];
                } catch (Exception e) {
                    System.err.println("Error retrieving images.");
                    e.printStackTrace();
                    currentActImage = null;
                    currentBslImage = null;
                }
                currentDeltaImage = null; // stale for the new slice — regenerate below if needed
                if (deltaSceneToggle) requestDeltaImage();
                repaint();
            }
        }.execute();
    }

    private BufferedImage loadImageFromFile(File file) {
        if (file == null || !file.exists()) {
            System.err.println("Error: Image file does not exist: " + (file != null ? file.getPath() : "null"));
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            System.err.println("Error loading image: " + file.getPath());
            e.printStackTrace();
            return null;
        }
    }

    // ---- Public API ------------------------------------------------------

    public void switchVariable(File[] actScene, File[] bslScene, int count, String selectedView) {
        this.actSceneFiles = actScene != null ? actScene : new File[0];
        this.bslSceneFiles = bslScene != null ? bslScene : new File[0];
        this.selectedView  = selectedView;

        int maxLen = Math.max(this.actSceneFiles.length, this.bslSceneFiles.length);
        this.totalCount = maxLen > 0 ? maxLen - 1 : -1;

        if (count != -1) {
            this.streamCount = (this.totalCount >= 0) ? Math.max(0, Math.min(count, this.totalCount)) : -1;
        } else {
            this.streamCount = (this.totalCount >= 0) ? Math.max(0, Math.min(this.streamCount, this.totalCount)) : -1;
        }

        loadCurrentImageAsync();
    }

    public void switchVariable(File[] actScene, File[] bslScene, int count, String selectedView,
                               String newActName, String newBslName) {
        updateSimNames(newActName, newBslName);
        switchVariable(actScene, bslScene, count, selectedView);
    }

    void updateSimNames(String act, String bsl) {
        this.actSimNameOverride = act;
        this.bslSimNameOverride = bsl;
    }

    public void toggleActive()   { deltaSceneToggle = false; actSceneToggle = true;  repaint(); }
    public void toggleBaseline() { deltaSceneToggle = false; actSceneToggle = false; repaint(); }
    public void toggleDelta()    {
        deltaSceneToggle = true;
        actSceneToggle = false;
        requestDeltaImage();
        repaint();
    }

    /** Wires up the shared delta-generation engine (colormap + scale settings, caches). */
    public void setDeltaEngine(DeltaSceneEngine engine) { this.deltaEngine = engine; }

    /** Keeps the panel aware of which variable's images are currently loaded. */
    public void setCurrentVariable(String variable) { this.currentVariable = variable; }

    /**
     * Generates (or fetches from cache) the delta scene for the currently
     * selected variable/view/slice from the Active + Baseline PNGs, using
     * that variable's colormap + scale settings. Always diffs Active with
     * respect to Baseline — there is no separate "third sim" delta source.
     */
    private void requestDeltaImage() {
        deltaError = null;

        if (deltaEngine == null) {
            deltaError = "Delta engine not initialized.";
            currentDeltaImage = null;
            repaint();
            return;
        }

        File actFile = (streamCount >= 0 && streamCount < actSceneFiles.length) ? actSceneFiles[streamCount] : null;
        File bslFile = (streamCount >= 0 && streamCount < bslSceneFiles.length) ? bslSceneFiles[streamCount] : null;
        if (actFile == null || bslFile == null) {
            deltaError = "Need both Active and Baseline scenes loaded to generate a delta.";
            currentDeltaImage = null;
            repaint();
            return;
        }

        DeltaSettings.VariableConfig cfg = deltaEngine.getSettings().get(currentVariable);
        boolean missingOrig  = cfg.originalCmapPath == null || cfg.originalCmapPath.isBlank();
        String globalDeltaCmap = deltaEngine.getSettings().getDeltaCmapPath();
        boolean missingDelta = globalDeltaCmap == null || globalDeltaCmap.isBlank();
        if (missingOrig || missingDelta) {
            String which = missingOrig && missingDelta ? "an original and delta colormap"
                    : missingOrig ? "an original colormap for \"" + currentVariable + "\"" : "a delta colormap";
            deltaError = "Missing " + which + " — set it in Settings > Delta Colormaps.";
            currentDeltaImage = null;
            repaint();
            return;
        }

        BufferedImage cached = deltaEngine.getCached(currentVariable, actFile, bslFile);
        if (cached != null) {
            currentDeltaImage = cached;
            deltaGenerating = false;
            repaint();
            prefetchNeighbors();
            return;
        }

        deltaGenerating = true;
        repaint(); // shows the "Generating delta scene..." placeholder right away

        final String variable = currentVariable;
        // Snapshot the already-decoded images rather than re-reading the
        // files: they're already sitting in memory from loadCurrentImageAsync,
        // so this avoids decoding the same PNGs a second time.
        final BufferedImage actSnapshot = currentActImage;
        final BufferedImage bslSnapshot = currentBslImage;
        new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() throws Exception {
                return deltaEngine.generate(variable, actFile, actSnapshot, bslFile, bslSnapshot);
            }
            @Override protected void done() {
                deltaGenerating = false;
                try {
                    BufferedImage result = get();
                    currentDeltaImage = result;
                    if (result == null) deltaError = "Delta generation returned no image.";
                } catch (Exception ex) {
                    currentDeltaImage = null;
                    deltaError = (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                    System.err.println("Delta scene generation failed: " + deltaError);
                }
                repaint();
                prefetchNeighbors();
            }
        }.execute();
    }

    /**
     * Best-effort background generation of the neighboring slices' delta
     * images so that scrubbing with the arrow keys hits the cache (near-
     * instant) instead of generating from scratch on every press. Silently
     * does nothing for slices that are out of range, already cached, or
     * missing a source file — failures here are non-fatal since this is
     * purely a warm-up, not something the user is waiting on.
     */
    private void prefetchNeighbors() {
        if (deltaEngine == null) return;
        DeltaSettings.VariableConfig cfg = deltaEngine.getSettings().get(currentVariable);
        String globalDeltaCmap = deltaEngine.getSettings().getDeltaCmapPath();
        if (cfg.originalCmapPath == null || cfg.originalCmapPath.isBlank()) return;
        if (globalDeltaCmap == null || globalDeltaCmap.isBlank()) return;

        final String variable = currentVariable;
        for (int idx : new int[]{streamCount - 1, streamCount + 1}) {
            if (idx < 0 || idx > totalCount) continue;
            final File actFile = (idx < actSceneFiles.length) ? actSceneFiles[idx] : null;
            final File bslFile = (idx < bslSceneFiles.length) ? bslSceneFiles[idx] : null;
            if (actFile == null || bslFile == null) continue;
            if (deltaEngine.getCached(variable, actFile, bslFile) != null) continue;

            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() {
                    try {
                        deltaEngine.generate(variable, actFile, bslFile);
                    } catch (Exception ex) {
                        // best-effort only — the user isn't waiting on this
                    }
                    return null;
                }
            }.execute();
        }
    }

    public void toggleStreamDown() {
        if (totalCount < 0) return;
        this.streamCount = (this.streamCount == this.totalCount) ? 0 : this.streamCount + 1;
        loadCurrentImageAsync();
    }

    public void toggleStreamUp() {
        if (totalCount < 0) return;
        this.streamCount = (this.streamCount == 0) ? this.totalCount : this.streamCount - 1;
        loadCurrentImageAsync();
    }

    public void resetView()       { zoomFactor = 1.0; imageOffset = new Point(0, 0); repaint(); }
    public void toggleGrid()      { showGrid = !showGrid; repaint(); }
    public void toggleMirror()    { mirrorMode = !mirrorMode; repaint(); }
    public void toggleMirrorAxis(){ mirrorVertical = !mirrorVertical; repaint(); }
    public void toggleMirrorSide(){ mirrorFirstHalf = !mirrorFirstHalf; repaint(); }

    private void clampImageOffset() {
        int panelWidth  = getWidth();
        int panelHeight = getHeight();
        int maxOffsetX = Math.max(0, (lastImageWidth  - panelWidth)  / 2);
        int maxOffsetY = Math.max(0, (lastImageHeight - panelHeight) / 2);
        imageOffset.x = Math.max(-maxOffsetX, Math.min(maxOffsetX, imageOffset.x));
        imageOffset.y = Math.max(-maxOffsetY, Math.min(maxOffsetY, imageOffset.y));
    }

    // ---- Paint -----------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // ---- Determine which image + label to show ----------------------
        BufferedImage imageToDraw;
        String textOverlay;
        String sceneSlice;
        File currentFile;

        if (deltaSceneToggle) {
            imageToDraw = currentDeltaImage;
            String actLbl = (actSimNameOverride != null ? actSimNameOverride : this.actSimName);
            String bslLbl = (bslSimNameOverride != null ? bslSimNameOverride : this.bslSimName);
            textOverlay = "\u0394  " + actLbl + " \u2212 " + bslLbl;
            currentFile = (streamCount >= 0 && streamCount < actSceneFiles.length)
                    ? actSceneFiles[streamCount] : null;
        } else if (actSceneToggle) {
            imageToDraw = currentActImage;
            textOverlay = (actSimNameOverride != null ? actSimNameOverride : this.actSimName)
                    + (currentActImage == null ? "  [not loaded]" : "");
            currentFile = (streamCount >= 0 && streamCount < actSceneFiles.length)
                    ? actSceneFiles[streamCount] : null;
        } else {
            imageToDraw = currentBslImage;
            textOverlay = (bslSimNameOverride != null ? bslSimNameOverride : this.bslSimName)
                    + (currentBslImage == null ? "  [not loaded]" : "");
            currentFile = (streamCount >= 0 && streamCount < bslSceneFiles.length)
                    ? bslSceneFiles[streamCount] : null;
        }

        if (mirrorMode && imageToDraw != null) {
            imageToDraw = createMirroredImage(imageToDraw);
        }

        if (currentFile != null) {
            sceneSlice = currentFile.getName();
            int dot = sceneSlice.lastIndexOf('.');
            if (dot > 0) sceneSlice = sceneSlice.substring(0, dot);
        } else {
            sceneSlice = "N/A";
        }

        // ---- Draw image + grid ------------------------------------------
        if (imageToDraw != null) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);

            int panelWidth  = getWidth();
            int panelHeight = getHeight();
            int imgWidth    = imageToDraw.getWidth();
            int imgHeight   = imageToDraw.getHeight();

            double baseScale = Math.min((double) panelWidth / imgWidth, (double) panelHeight / imgHeight);
            double scale     = baseScale * zoomFactor;
            int newWidth     = (int) (imgWidth  * scale);
            int newHeight    = (int) (imgHeight * scale);

            lastImageWidth  = newWidth;
            lastImageHeight = newHeight;

            int x = (panelWidth  - newWidth)  / 2 + imageOffset.x;
            int y = (panelHeight - newHeight) / 2 + imageOffset.y;

            g2d.drawImage(imageToDraw, x, y, newWidth, newHeight, this);

            // ---- Grid overlay (only when showGrid is true) --------------
            if (showGrid) {
                double totalWidthMM;
                if      (Objects.equals(this.selectedView, "AftFore"))   totalWidthMM = 3542.0;
                else if (Objects.equals(this.selectedView, "TopBottom")) totalWidthMM = 4498.0;
                else                                                      totalWidthMM = 3919.0;

                double majorGridMM = 100.0;
                double minorGridMM = 10.0;

                double majorStepPx = (majorGridMM * imgWidth / totalWidthMM) * scale;
                double minorStepPx = (minorGridMM * imgWidth / totalWidthMM) * scale;

                double xOffsetPx, yOffsetPx;
                if (Objects.equals(this.selectedView, "AftFore")) {
                    xOffsetPx =  0.75 * minorStepPx;
                    yOffsetPx = -2.95 * minorStepPx;
                } else if (Objects.equals(this.selectedView, "TopBottom")) {
                    xOffsetPx =  0.0  * minorStepPx;
                    yOffsetPx = -2.85 * minorStepPx;
                } else {
                    xOffsetPx =  0.0  * minorStepPx;
                    yOffsetPx = -5.1  * minorStepPx;
                }

                double imgCenterX = x + (imgWidth  / 2.0) * scale + xOffsetPx;
                double imgCenterY = y + (imgHeight / 2.0) * scale + yOffsetPx;

                // Minor grid
                g2d.setColor(new Color(20, 20, 20, 60));
                g2d.setStroke(new BasicStroke(0.5f));
                for (double gx = imgCenterX; gx <= x + newWidth; gx += minorStepPx)
                    g2d.drawLine((int) gx, y, (int) gx, y + newHeight);
                for (double gx = imgCenterX - minorStepPx; gx >= x; gx -= minorStepPx)
                    g2d.drawLine((int) gx, y, (int) gx, y + newHeight);
                for (double gy = imgCenterY; gy <= y + newHeight; gy += minorStepPx)
                    g2d.drawLine(x, (int) gy, x + newWidth, (int) gy);
                for (double gy = imgCenterY - minorStepPx; gy >= y; gy -= minorStepPx)
                    g2d.drawLine(x, (int) gy, x + newWidth, (int) gy);

                // Major grid
                g2d.setColor(new Color(20, 20, 20, 100));
                g2d.setStroke(new BasicStroke(1.2f));
                for (double gx = imgCenterX; gx <= x + newWidth; gx += majorStepPx)
                    g2d.drawLine((int) gx, y, (int) gx, y + newHeight);
                for (double gx = imgCenterX - majorStepPx; gx >= x; gx -= majorStepPx)
                    g2d.drawLine((int) gx, y, (int) gx, y + newHeight);
                for (double gy = imgCenterY; gy <= y + newHeight; gy += majorStepPx)
                    g2d.drawLine(x, (int) gy, x + newWidth, (int) gy);
                for (double gy = imgCenterY - majorStepPx; gy >= y; gy -= majorStepPx)
                    g2d.drawLine(x, (int) gy, x + newWidth, (int) gy);
            }

            g2d.dispose();

        } else {
            // No image — show placeholder text
            g.setColor(new Color(120, 120, 120));
            g.setFont(textFont);
            String msg = deltaGenerating
                    ? "Generating delta scene..."
                    : (deltaSceneToggle && deltaError != null)
                    ? deltaError
                    : (totalCount < 0)
                    ? "No scenes loaded — use the sidebar to select and load sims."
                    : "Loading...";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
        }

        // ---- Annotation box — always drawn last on a fresh context ------
        Graphics2D ag = (Graphics2D) g.create();
        ag.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        ag.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        ag.setFont(textFont);
        FontMetrics tfm = ag.getFontMetrics();

        int textPadH = 8;
        int textPadV = 6;
        int lineGap  = 3;
        int lineH    = tfm.getAscent() + tfm.getDescent();
        int boxW     = Math.max(tfm.stringWidth(textOverlay), tfm.stringWidth(sceneSlice)) + textPadH * 2;
        int boxH     = lineH * 2 + lineGap + textPadV * 2;
        int boxX     = 5;
        int boxY     = 5;

        // White fully-opaque fill
        ag.setColor(Color.WHITE);
        ag.fillRoundRect(boxX, boxY, boxW, boxH, 8, 8);
        // Black outer border
        ag.setStroke(new BasicStroke(1.5f));
        ag.setColor(Color.BLACK);
        ag.drawRoundRect(boxX, boxY, boxW, boxH, 8, 8);
        // Gold inner border (inset 2px)
        ag.setColor(new Color(0xCF, 0xA1, 0x00));
        ag.drawRoundRect(boxX + 2, boxY + 2, boxW - 4, boxH - 4, 5, 5);

        // Text
        int line1Y = boxY + textPadV + tfm.getAscent();
        int line2Y = line1Y + lineH + lineGap;
        ag.setColor(Color.BLACK);
        ag.drawString(textOverlay, boxX + textPadH, line1Y);
        ag.drawString(sceneSlice,  boxX + textPadH, line2Y);

        // Mirror status
        if (mirrorMode) {
            String axisStr = mirrorVertical ? "Vertical" : "Horizontal";
            String sideStr = mirrorVertical
                    ? (mirrorFirstHalf ? "Right" : "Left")
                    : (mirrorFirstHalf ? "Top"   : "Bottom");
            ag.setColor(new Color(220, 50, 50));
            ag.drawString("Mirror: ON | Axis: " + axisStr + " | Side: " + sideStr,
                    boxX + textPadH, boxY + boxH + tfm.getAscent() + 4);
        }

        ag.dispose();
    }
}