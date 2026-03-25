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
    private File[] deltaSceneFiles;

    private BufferedImage currentActImage;
    private BufferedImage currentBslImage;
    private BufferedImage currentDeltaImage;

    private boolean actSceneToggle = true;
    private boolean deltaSceneToggle = false;
    private final String actSimName;
    private final String bslSimName;
    private final String deltaSimName;
    private int totalCount;
    private int streamCount;

    private double zoomFactor = 1.0;
    private double zoomIncrement = 0.1;

    private Point dragStartScreen = null;
    private Point imageOffset = new Point(0, 0);

    // To limit dragging image beyond the border
    private int lastImageWidth = 0;
    private int lastImageHeight = 0;

    private boolean showGrid = false;

    private boolean mirrorMode = false;
    private boolean mirrorVertical = true; // true for vertical, false for horizontal
    private boolean mirrorFirstHalf = true; // true for left/top, false for right/bottom

    private final Font textFont = new Font("Source Sans Pro", Font.ITALIC, 15);
    private String selectedView;

    public ImageDisplayPanel(File[] actScene, File[] bslScene, File[] deltaScene, int count, String actSimName, String bslSimName, String deltaSimName) {
        this.actSceneFiles = actScene;
        this.bslSceneFiles = bslScene;
        this.deltaSceneFiles = deltaScene;
        this.streamCount = count;
        this.totalCount = (actScene != null && actScene.length > 0) ? actScene.length - 1 : -1;
        this.actSimName = actSimName;
        this.bslSimName = bslSimName;
        this.deltaSimName = deltaSimName;

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

            // Get mouse position relative to panel
            Point mousePos = e.getPoint();
            int panelWidth = getWidth();
            int panelHeight = getHeight();

            // Calculate image position and scale before zoom
            if (currentActImage == null && currentBslImage == null && currentDeltaImage == null)
                return;

            BufferedImage img = (deltaSceneToggle ? currentDeltaImage :
                    actSceneToggle ? currentActImage : currentBslImage);
            if (img == null) return;

            int imgWidth = img.getWidth();
            int imgHeight = img.getHeight();

            double baseScale = Math.min((double) panelWidth / imgWidth, (double) panelHeight / imgHeight);
            double prevScaledWidth = imgWidth * baseScale * prevZoom;
            double prevScaledHeight = imgHeight * baseScale * prevZoom;
            double newScaledWidth = imgWidth * baseScale * zoomFactor;
            double newScaledHeight = imgHeight * baseScale * zoomFactor;

            // Image top-left corner before zoom
            double prevX = (panelWidth - prevScaledWidth) / 2.0 + imageOffset.x;
            double prevY = (panelHeight - prevScaledHeight) / 2.0 + imageOffset.y;

            // Calculate relative position of mouse on image
            double relX = (mousePos.getX() - prevX) / prevScaledWidth;
            double relY = (mousePos.getY() - prevY) / prevScaledHeight;

            // New top-left corner to keep mouse point stable
            double newX = mousePos.getX() - relX * newScaledWidth;
            double newY = mousePos.getY() - relY * newScaledHeight;

            // Adjust image offset
            imageOffset.x = (int) (newX - (panelWidth - newScaledWidth) / 2.0);
            imageOffset.y = (int) (newY - (panelHeight - newScaledHeight) / 2.0);

            clampImageOffset();
            repaint();
        });

        // Drag image
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragStartScreen = e.getPoint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragStartScreen = null;
            }
        });

        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && dragStartScreen != null) {
                    Point dragEnd = e.getPoint();
                    int dx = dragEnd.x - dragStartScreen.x;
                    int dy = dragEnd.y - dragStartScreen.y;

                    imageOffset.translate(dx, dy);
                    clampImageOffset();
                    dragStartScreen = dragEnd;
                    repaint();
                }
            }
        });

        // Combines all key-based actions into a single listener.
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_R:
                        resetView();
                        break;
                    case KeyEvent.VK_G:
                        showGrid = !showGrid;
                        repaint();
                        break;
                    case KeyEvent.VK_M: // 'M' to toggle Mirror mode
                        mirrorMode = !mirrorMode;
                        repaint();
                        break;
                    case KeyEvent.VK_A: // 'A' to switch mirror Axis
                        mirrorVertical = !mirrorVertical;
                        repaint();
                        break;
                    case KeyEvent.VK_S: // 'S' to switch mirror Side
                        mirrorFirstHalf = !mirrorFirstHalf;
                        repaint();
                        break;
                }
            }
        });
    }

    /**
     * Creates a new image by mirroring one half of the source image.
     * The behavior is controlled by the mirrorVertical and mirrorFirstHalf flags.
     * @param source The original BufferedImage.
     * @return A new BufferedImage containing the mirrored result.
     */
    private BufferedImage createMirroredImage(BufferedImage source) {
        if (source == null) return null;

        int width = source.getWidth();
        int height = source.getHeight();
        int imageType = source.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : source.getType();
        BufferedImage mirroredImage = new BufferedImage(width, height, imageType);
        Graphics2D g2d = mirroredImage.createGraphics();

        if (mirrorVertical) {
            int midX = 2010;
            if (mirrorFirstHalf) {
                // Keep right half, mirror it to the left
                // Draw the right half of the source to the right side of the destination
                g2d.drawImage(source, midX, 0, width, height, midX, 0, width, height, null);
                // Draw the right half of the source mirrored to the left side of the destination
                g2d.drawImage(source, 0, 0, midX, height, width, 0, midX, height, null);

            } else {
                // Keep left half, mirror it to the right
                // Draw the left half of the source to the left side of the destination
                g2d.drawImage(source, 0, 0, midX, height, 0, 0, midX, height, null);
                // Draw the left half of the source mirrored to the right side of the destination
                g2d.drawImage(source, midX, 0, width, height, midX, 0, 0, height, null);
            }
        } else { // Horizontal mirror
            int midY = 1061;
            if (mirrorFirstHalf) { // Keep top half, mirror it to the bottom
                // 1. Draw top half normally to the top side of the destination
                g2d.drawImage(source, 0, 0, width, midY, 0, 0, width, midY, null);

                // 2. [THIS IS THE FIX] Draw the TOP half mirrored to the bottom side of the destination.
                // To flip vertically, the source Y coordinates are swapped (sy1=midY, sy2=0).
                // This reads the top half of the source (from y=0 to y=midY) in reverse.
                g2d.drawImage(source, 0, midY, width, height, 0, midY, width, 0, null);

            } else { // Keep bottom half, mirror it to the top
                // 1. Draw bottom half normally to the bottom side of the destination
                g2d.drawImage(source, 0, midY, width, height, 0, midY, width, height, null);

                // 2. Draw the BOTTOM half mirrored to the top side of the destination.
                // To flip vertically, source Y's are swapped (sy1=height, sy2=midY)
                g2d.drawImage(source, 0, 0, width, midY, 0, height, width, midY, null);
            }
        }

        g2d.dispose();
        return mirroredImage;
    }

    private void loadCurrentImageAsync() {
        if (totalCount < 0 || streamCount < 0 || streamCount > totalCount) {
            currentActImage = null;
            currentBslImage = null;
            currentDeltaImage = null;
            System.err.println("Warning: Invalid streamCount or file arrays empty/null.");
            repaint();
            return;
        }

        File actFile = actSceneFiles[streamCount];
        File bslFile = bslSceneFiles[streamCount];
        File deltaFile = deltaSceneFiles[streamCount];

        new SwingWorker<BufferedImage[], Void>() {
            @Override
            protected BufferedImage[] doInBackground() {
                BufferedImage act = loadImageFromFile(actFile);
                BufferedImage bsl = loadImageFromFile(bslFile);
                BufferedImage delta = loadImageFromFile(deltaFile);
                return new BufferedImage[]{act, bsl, delta};
            }

            @Override
            protected void done() {
                try {
                    BufferedImage[] result = get();
                    currentActImage = result[0];
                    currentBslImage = result[1];
                    currentDeltaImage = result[2];
                } catch (Exception e) {
                    System.err.println("Error retrieving images for Multithreading.");
                    e.printStackTrace();
                    currentActImage = null;
                    currentBslImage = null;
                    currentDeltaImage = null;
                }
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

    public void switchVariable(File[] actScene, File[] bslScene, File[] deltaScene, int count, String selectedView) {
        this.actSceneFiles = actScene;
        this.bslSceneFiles = bslScene;
        this.deltaSceneFiles = deltaScene;
        this.selectedView = selectedView;
        this.totalCount = (actScene != null && actScene.length > 0) ? actScene.length - 1 : -1;

        if (count != -1) {
            if (this.totalCount >= 0) {
                this.streamCount = Math.max(0, Math.min(count, this.totalCount));
            } else {
                this.streamCount = -1;
            }
        } else {
            if (this.totalCount >= 0) {
                this.streamCount = Math.max(0, Math.min(this.streamCount, this.totalCount));
            } else {
                this.streamCount = -1;
            }
        }

        loadCurrentImageAsync(); // Async load
    }

    public void toggleActive() {
        deltaSceneToggle = false;
        actSceneToggle = true;
        repaint();
    }

    public void toggleBaseline() {
        deltaSceneToggle = false;
        actSceneToggle = false;
        repaint();
    }

    public void toggleDelta() {
        deltaSceneToggle = true;
        actSceneToggle = false;
        repaint();
    }

    public void toggleStreamDown() {
        if (totalCount < 0) return;
        this.streamCount = (this.streamCount == this.totalCount) ? 0 : this.streamCount + 1;
        loadCurrentImageAsync(); // Async load
    }

    public void toggleStreamUp() {
        if (totalCount < 0) return;
        this.streamCount = (this.streamCount == 0) ? this.totalCount : this.streamCount - 1;
        loadCurrentImageAsync(); // Async load
    }

    private void resetView() {
        zoomFactor = 1.0;
        imageOffset = new Point(0, 0);
        repaint();
    }

    private void clampImageOffset() {
        int panelWidth = getWidth();
        int panelHeight = getHeight();
        int maxOffsetX = Math.max(0, (lastImageWidth - panelWidth) / 2);
        int maxOffsetY = Math.max(0, (lastImageHeight - panelHeight) / 2);

        imageOffset.x = Math.max(-maxOffsetX, Math.min(maxOffsetX, imageOffset.x));
        imageOffset.y = Math.max(-maxOffsetY, Math.min(maxOffsetY, imageOffset.y));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        BufferedImage imageToDraw;
        String textOverlay;
        String sceneSlice;
        File currentFile;

        if (deltaSceneToggle) {
            imageToDraw = currentDeltaImage;
            textOverlay = this.deltaSimName;
            currentFile = (deltaSceneFiles != null && streamCount >= 0 && streamCount < deltaSceneFiles.length) ? deltaSceneFiles[this.streamCount] : null;
        } else {
            if (actSceneToggle) {
                imageToDraw = currentActImage;
                textOverlay = this.actSimName;
                currentFile = (actSceneFiles != null && streamCount >= 0 && streamCount < actSceneFiles.length) ? actSceneFiles[this.streamCount] : null;
            } else {
                imageToDraw = currentBslImage;
                textOverlay = this.bslSimName;
                currentFile = (bslSceneFiles != null && streamCount >= 0 && streamCount < bslSceneFiles.length) ? bslSceneFiles[this.streamCount] : null;
            }
        }

        // --- NEW: Apply mirroring if enabled ---
        if (mirrorMode && imageToDraw != null) {
            imageToDraw = createMirroredImage(imageToDraw);
        }
        // --- END NEW ---

        if (currentFile != null) {
            sceneSlice = currentFile.getName();
            int dotIndex = sceneSlice.lastIndexOf('.');
            if (dotIndex > 0) {
                sceneSlice = sceneSlice.substring(0, dotIndex);
            }
        } else {
            sceneSlice = "N/A";
        }

        if (imageToDraw != null) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int panelWidth = getWidth();
            int panelHeight = getHeight();
            int imgWidth = imageToDraw.getWidth();
            int imgHeight = imageToDraw.getHeight();

            double baseScale = Math.min((double) panelWidth / imgWidth, (double) panelHeight / imgHeight);
            double scale = baseScale * zoomFactor;
            int newWidth = (int) (imgWidth * scale);
            int newHeight = (int) (imgHeight * scale);

            lastImageWidth = newWidth;
            lastImageHeight = newHeight;

            // Centered then offset
            int x = (panelWidth - newWidth) / 2 + imageOffset.x;
            int y = (panelHeight - newHeight) / 2 + imageOffset.y;

            g2d.drawImage(imageToDraw, x, y, newWidth, newHeight, this);
            g2d.setColor(Color.BLACK);
            g2d.setFont(textFont);

            int textX = 5;
            g2d.drawString(textOverlay, textX, 18);
            g2d.drawString(sceneSlice, textX, 35);

            // --- NEW: Display mirror status on screen ---
            if (mirrorMode) {
                String mirrorAxisStr = mirrorVertical ? "Vertical" : "Horizontal";
                String mirrorSideStr;
                if (mirrorVertical) {
                    mirrorSideStr = mirrorFirstHalf ? "Right" : "Left";
                } else {
                    mirrorSideStr = mirrorFirstHalf ? "Top" : "Bottom";
                }
                String mirrorStatus = String.format("Mirror: ON | Axis: %s | Side: %s", mirrorAxisStr, mirrorSideStr);

                g2d.setColor(new Color(220, 50, 50)); // A noticeable red color
                g2d.drawString(mirrorStatus, textX, 52);
            }
            // --- END NEW ---

            // After drawing imageToDraw at (x,y) with size (newWidth, newHeight)
            if (showGrid) {

                // Physical size in mm
                double totalWidthMM;

                if (Objects.equals(this.selectedView, "AftFore"))  totalWidthMM = 3542.0;
                else if (Objects.equals(this.selectedView, "TopBottom"))  totalWidthMM = 4498.0;
                else {
                    totalWidthMM = 3919.0;
                }
                double majorGridMM = 100.0;
                double minorGridMM = 10.0;

                // Convert mm to image pixels (original scale)
                double majorStepPxOriginal = (majorGridMM * imgWidth) / totalWidthMM;
                double minorStepPxOriginal = (minorGridMM * imgWidth) / totalWidthMM;

                // Scaled pixel size
                double majorStepPx = majorStepPxOriginal * scale;
                double minorStepPx = minorStepPxOriginal * scale;

                // Offsets in original pixels (then scaled)
                // I don't know why the car is not centered but it isn't so offsets are needed
                double xOffsetPx;
                double yOffsetPx;
                if (Objects.equals(this.selectedView, "AftFore")) {
                    xOffsetPx = 0.75 * minorStepPx;
                    yOffsetPx = -2.95 * minorStepPx;
                }
                else if (Objects.equals(this.selectedView, "TopBottom")) {
                    xOffsetPx = 0 * minorStepPx;
                    yOffsetPx = -2.85 * minorStepPx;
                }

                else{
                    xOffsetPx = 0 * minorStepPx;
                    yOffsetPx = -5.1 * minorStepPx;
                }

                // Center of the image in panel coordinates
                double imgCenterX = x + (imgWidth / 2.0) * scale + xOffsetPx;
                double imgCenterY = y + (imgHeight / 2.0) * scale + yOffsetPx;

                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

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

                //Major grid
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

                g2d.dispose();
            }

            g2d.dispose();
        } else {
            g.setColor(Color.BLACK);
            g.setFont(textFont);
            String errorMsg = "Loading...";
            FontMetrics fm = g.getFontMetrics();
            int msgWidth = fm.stringWidth(errorMsg);
            int msgHeight = fm.getAscent();
            g.drawString(errorMsg, (getWidth() - msgWidth) / 2, (getHeight() - msgHeight) / 2 + fm.getAscent());
        }

    }
}