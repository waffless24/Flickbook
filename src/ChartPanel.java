import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/**
 * A self-contained Swing panel that renders line/XY/grouped-bar charts
 * using pure Java2D — no external libraries required.
 *
 * Supports:
 *   - Multiple named series with configurable color, stroke, alpha
 *   - Grouped bar chart mode (histogram comparison)
 *   - Zoom (scroll wheel) + pan (drag) + reset (R or double-click)
 *   - Log Y-axis mode for residual/multi-decade data
 *   - Auto-scaled axes with nice tick intervals + true minor grid
 *   - Zoom-out hard clamp at initial view extents
 *   - Cursor crosshair with data coordinate tooltip
 *   - Legend (top-right, inside the plot area)
 */
public class ChartPanel extends JPanel {

    // ---- Series descriptor ------------------------------------------------
    public static class SeriesSpec {
        public final String   label;
        public final double[] x;
        public final double[] y;
        public final Color    color;
        public final float    strokeWidth;
        public final float    alpha;
        public final boolean  isBar;

        public SeriesSpec(String label, double[] x, double[] y,
                          Color color, float strokeWidth, float alpha) {
            this.label       = label;
            this.x           = x;
            this.y           = y;
            this.color       = color;
            this.strokeWidth = strokeWidth;
            this.alpha       = alpha;
            this.isBar       = false;
        }

        // Bar variant
        SeriesSpec(String label, double[] x, double[] y,
                   Color color, float alpha, boolean bar) {
            this.label       = label;
            this.x           = x;
            this.y           = y;
            this.color       = color;
            this.strokeWidth = 1f;
            this.alpha       = alpha;
            this.isBar       = bar;
        }
    }

    // ---- Fields -----------------------------------------------------------
    private final List<SeriesSpec> series = new ArrayList<>();
    private String title  = "";
    private String xLabel = "";
    private String yLabel = "";

    private boolean barMode  = false;
    private boolean logYMode = false;   // true → Y axis uses log10 scale

    // Data ranges (auto-computed from series)
    private double xMin, xMax, yMin, yMax;
    private boolean rangesComputed = false;

    // Initial (reset) view extents — used for zoom-out hard clamp
    private double initXMin, initXMax, initYMin, initYMax;

    // Current view
    private double viewXMin, viewXMax, viewYMin, viewYMax;
    private boolean viewSet = false;
    private Point   dragStart = null;

    // Cursor tooltip
    private Point  cursorPoint = null;
    private String cursorLabel = null;

    // Margins
    private static final int ML = 80, MR = 20, MT = 36, MB = 52;

    // Fonts
    private static final Font TITLE_FONT  = new Font("Source Sans Pro", Font.BOLD,  13);
    private static final Font LABEL_FONT  = new Font("Source Sans Pro", Font.PLAIN, 11);
    private static final Font TICK_FONT   = new Font("Monospaced",      Font.PLAIN, 10);
    private static final Font LEGEND_FONT = new Font("Source Sans Pro", Font.PLAIN, 11);

    // ---- Constructor -------------------------------------------------------
    public ChartPanel() {
        setBackground(Color.WHITE);
        setOpaque(true);

        // Scroll to zoom — with hard clamp at initial extents
        addMouseWheelListener(e -> {
            ensureView();
            Rectangle2D plot = plotRect();
            if (!plot.contains(e.getPoint())) return;

            double factor = e.getPreciseWheelRotation() > 0 ? 1.12 : 1.0 / 1.12;

            // Only allow zoom-in (factor < 1) if already at initial bounds;
            // always allow zoom-out unless it would exceed initial extents.
            double mx = toDataX(e.getX(), plot);
            double my = toDataY(e.getY(), plot);

            double newXMin = mx + (viewXMin - mx) * factor;
            double newXMax = mx + (viewXMax - mx) * factor;
            double newYMin = my + (viewYMin - my) * factor;
            double newYMax = my + (viewYMax - my) * factor;

            // Clamp: never zoom out past initial extents
            if (newXMin < initXMin) newXMin = initXMin;
            if (newXMax > initXMax) newXMax = initXMax;
            if (newYMin < initYMin) newYMin = initYMin;
            if (newYMax > initYMax) newYMax = initYMax;

            viewXMin = newXMin; viewXMax = newXMax;
            viewYMin = newYMin; viewYMax = newYMax;
            repaint();
        });

        // Drag to pan (clamped to initial extents) + double-click reset + mouse exit
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) dragStart = e.getPoint();
                requestFocusInWindow();
            }
            public void mouseReleased(MouseEvent e) { dragStart = null; }
            public void mouseClicked(MouseEvent e)  {
                if (e.getClickCount() == 2) resetView();
            }
            public void mouseExited(MouseEvent e) {
                cursorPoint = null; cursorLabel = null; repaint();
            }
        });

        addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || dragStart == null) return;
                ensureView();
                Rectangle2D plot = plotRect();
                double dx = -(e.getX() - dragStart.x) / plot.getWidth()  * (viewXMax - viewXMin);
                double dy =  (e.getY() - dragStart.y) / plot.getHeight() * (viewYMax - viewYMin);

                // Apply pan then clamp so we never pan outside initial extents
                double spanX = viewXMax - viewXMin;
                double spanY = viewYMax - viewYMin;
                double nx0 = viewXMin + dx, nx1 = viewXMax + dx;
                double ny0 = viewYMin + dy, ny1 = viewYMax + dy;
                if (nx0 < initXMin) { nx0 = initXMin; nx1 = nx0 + spanX; }
                if (nx1 > initXMax) { nx1 = initXMax; nx0 = nx1 - spanX; }
                if (ny0 < initYMin) { ny0 = initYMin; ny1 = ny0 + spanY; }
                if (ny1 > initYMax) { ny1 = initYMax; ny0 = ny1 - spanY; }
                viewXMin = nx0; viewXMax = nx1;
                viewYMin = ny0; viewYMax = ny1;

                dragStart = e.getPoint();
                repaint();
            }
            public void mouseMoved(MouseEvent e) {
                cursorPoint = e.getPoint();
                updateCursorLabel();
                repaint();
            }
        });

        // R to reset
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_R) resetView();
            }
        });
    }

    // ---- Public API -------------------------------------------------------

    public void setSeries(List<SeriesSpec> specs, String title, String xLabel, String yLabel) {
        this.series.clear();
        this.series.addAll(specs);
        this.title   = title;
        this.xLabel  = xLabel;
        this.yLabel  = yLabel;
        this.barMode = specs.stream().anyMatch(s -> s.isBar);
        this.rangesComputed = false;
        this.viewSet = false;
        repaint();
    }

    /** Call after setSeries to enable logarithmic Y axis. */
    public void setLogY(boolean log) {
        this.logYMode = log;
        this.rangesComputed = false;
        this.viewSet = false;
        repaint();
    }

    public void resetView() { viewSet = false; repaint(); }

    // ---- Painting ---------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (series.isEmpty()) {
            drawCentred(g, "No data", getWidth() / 2, getHeight() / 2);
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);

        computeRanges();
        ensureView();
        Rectangle2D plot = plotRect();

        // Background
        g2.setColor(new Color(250, 250, 252));
        g2.fill(plot);

        drawGrid(g2, plot);
        drawAxes(g2, plot);

        g2.setClip(plot);
        if (barMode) drawBars(g2, plot);
        else         drawLines(g2, plot);
        g2.setClip(null);

        drawLabels(g2, plot);
        drawLegend(g2, plot);
        drawCrosshair(g2, plot);
        g2.dispose();
    }

    // ---- Bars (grouped histogram) ----------------------------------------

    private void drawBars(Graphics2D g2, Rectangle2D plot) {
        int n = series.size();
        if (n == 0) return;

        double[] binX = series.get(0).x;
        int      bins = binX.length;
        if (bins < 2) return;

        double binW   = (binX[bins-1] - binX[0]) / (bins - 1);
        double gap    = binW * 0.08;
        double groupW = binW - gap * 2;
        double barW   = groupW / n;

        for (int si = 0; si < n; si++) {
            SeriesSpec sp = series.get(si);
            Color c = new Color(sp.color.getRed(), sp.color.getGreen(), sp.color.getBlue(),
                    (int)(sp.alpha * 255));
            g2.setColor(c);

            double[] yArr = sp.y, xArr = sp.x;
            for (int bi = 0; bi < Math.min(xArr.length, yArr.length); bi++) {
                double xCenter = xArr[bi], yVal = yArr[bi];
                if (Double.isNaN(yVal) || yVal <= 0) continue;

                double xLeft  = xCenter - groupW / 2.0 + si * barW;
                double xRight = xLeft + barW * 0.92;
                double px1 = toScreenX(xLeft,  plot);
                double px2 = toScreenX(xRight, plot);
                double py0 = toScreenY(0, plot);
                double py1 = toScreenY(yVal, plot);

                if (px2 < plot.getMinX() || px1 > plot.getMaxX()) continue;
                px1 = Math.max(px1, plot.getMinX());
                px2 = Math.min(px2, plot.getMaxX());
                double yTop    = Math.min(py0, py1);
                double yBottom = Math.max(py0, py1);

                g2.fillRect((int)px1, (int)yTop, (int)(px2-px1), (int)(yBottom-yTop));
                g2.setColor(sp.color.darker());
                g2.drawRect((int)px1, (int)yTop, (int)(px2-px1), (int)(yBottom-yTop));
                g2.setColor(c);
            }
        }
    }

    // ---- Lines -----------------------------------------------------------

    private void drawLines(Graphics2D g2, Rectangle2D plot) {
        for (SeriesSpec sp : series) {
            if (sp.x == null || sp.y == null || sp.x.length == 0) continue;

            Color c = new Color(sp.color.getRed(), sp.color.getGreen(), sp.color.getBlue(),
                    (int)(sp.alpha * 255));
            g2.setColor(c);
            g2.setStroke(new BasicStroke(sp.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            GeneralPath path = new GeneralPath();
            boolean started = false;
            int n = Math.min(sp.x.length, sp.y.length);
            for (int i = 0; i < n; i++) {
                double xi = sp.x[i], yi = sp.y[i];
                if (Double.isNaN(xi) || Double.isNaN(yi)) { started = false; continue; }
                if (logYMode && yi <= 0) { started = false; continue; }

                double sx = toScreenX(xi, plot);
                double sy = logYMode ? toScreenYLog(Math.log10(yi), plot) : toScreenY(yi, plot);
                if (!started) { path.moveTo(sx, sy); started = true; }
                else            path.lineTo(sx, sy);
            }
            g2.draw(path);
        }
    }

    // ---- Grid ------------------------------------------------------------

    private void drawGrid(Graphics2D g2, Rectangle2D plot) {
        if (logYMode) {
            drawGridLogY(g2, plot);
        } else {
            drawGridLinear(g2, plot);
        }
    }

    private void drawGridLinear(Graphics2D g2, Rectangle2D plot) {
        double[] xTicks = niceTicks(viewXMin, viewXMax, 8);
        double[] yTicks = niceTicks(viewYMin, viewYMax, 7);

        double xMinorStep = xTicks.length >= 2 ? (xTicks[1] - xTicks[0]) / 5.0 : 0;
        double yMinorStep = yTicks.length >= 2 ? (yTicks[1] - yTicks[0]) / 5.0 : 0;

        // Minor grid
        g2.setColor(new Color(235, 235, 235));
        g2.setStroke(new BasicStroke(0.4f));
        if (xMinorStep > 0) {
            double xStart = xTicks.length > 0 ? xTicks[0] - xMinorStep * 20 : viewXMin;
            for (double t = xStart; t <= viewXMax + xMinorStep * 0.5; t += xMinorStep) {
                double sx = toScreenX(t, plot);
                if (sx < plot.getMinX() || sx > plot.getMaxX()) continue;
                boolean isMajor = false;
                for (double mt : xTicks) { if (Math.abs(t - mt) < xMinorStep * 0.01) { isMajor = true; break; } }
                if (!isMajor) g2.drawLine((int)sx, (int)plot.getMinY(), (int)sx, (int)plot.getMaxY());
            }
        }
        if (yMinorStep > 0) {
            double yStart = yTicks.length > 0 ? yTicks[0] - yMinorStep * 20 : viewYMin;
            for (double t = yStart; t <= viewYMax + yMinorStep * 0.5; t += yMinorStep) {
                double sy = toScreenY(t, plot);
                if (sy < plot.getMinY() || sy > plot.getMaxY()) continue;
                boolean isMajor = false;
                for (double mt : yTicks) { if (Math.abs(t - mt) < yMinorStep * 0.01) { isMajor = true; break; } }
                if (!isMajor) g2.drawLine((int)plot.getMinX(), (int)sy, (int)plot.getMaxX(), (int)sy);
            }
        }

        // Major grid
        g2.setColor(new Color(210, 210, 215));
        g2.setStroke(new BasicStroke(0.6f));
        for (double t : xTicks) {
            double sx = toScreenX(t, plot);
            if (sx < plot.getMinX() || sx > plot.getMaxX()) continue;
            g2.drawLine((int)sx, (int)plot.getMinY(), (int)sx, (int)plot.getMaxY());
        }
        for (double t : yTicks) {
            double sy = toScreenY(t, plot);
            if (sy < plot.getMinY() || sy > plot.getMaxY()) continue;
            g2.drawLine((int)plot.getMinX(), (int)sy, (int)plot.getMaxX(), (int)sy);
        }

        // Zero lines
        g2.setColor(new Color(160, 160, 170));
        g2.setStroke(new BasicStroke(0.8f));
        if (viewXMin <= 0 && viewXMax >= 0) {
            double sx = toScreenX(0, plot);
            g2.drawLine((int)sx, (int)plot.getMinY(), (int)sx, (int)plot.getMaxY());
        }
        if (viewYMin <= 0 && viewYMax >= 0) {
            double sy = toScreenY(0, plot);
            g2.drawLine((int)plot.getMinX(), (int)sy, (int)plot.getMaxX(), (int)sy);
        }
    }

    /**
     * Log Y grid: major lines at every power of 10, minor lines at 2–9× within each decade.
     * Uses the log-space view bounds viewYMin/viewYMax (stored as log10 values when logYMode=true).
     */
    private void drawGridLogY(Graphics2D g2, Rectangle2D plot) {
        // X grid is always linear
        double[] xTicks = niceTicks(viewXMin, viewXMax, 8);
        double xMinorStep = xTicks.length >= 2 ? (xTicks[1] - xTicks[0]) / 5.0 : 0;

        g2.setColor(new Color(235, 235, 235));
        g2.setStroke(new BasicStroke(0.4f));
        if (xMinorStep > 0) {
            double xStart = xTicks.length > 0 ? xTicks[0] - xMinorStep * 20 : viewXMin;
            for (double t = xStart; t <= viewXMax + xMinorStep * 0.5; t += xMinorStep) {
                double sx = toScreenX(t, plot);
                if (sx < plot.getMinX() || sx > plot.getMaxX()) continue;
                boolean isMajor = false;
                for (double mt : xTicks) { if (Math.abs(t - mt) < xMinorStep * 0.01) { isMajor = true; break; } }
                if (!isMajor) g2.drawLine((int)sx, (int)plot.getMinY(), (int)sx, (int)plot.getMaxY());
            }
        }
        g2.setColor(new Color(210, 210, 215));
        g2.setStroke(new BasicStroke(0.6f));
        for (double t : xTicks) {
            double sx = toScreenX(t, plot);
            if (sx < plot.getMinX() || sx > plot.getMaxX()) continue;
            g2.drawLine((int)sx, (int)plot.getMinY(), (int)sx, (int)plot.getMaxY());
        }

        // Y log grid: iterate over decade range
        int loExp = (int)Math.floor(viewYMin);
        int hiExp = (int)Math.ceil(viewYMax);

        // Minor lines first (2–9 within each decade)
        g2.setColor(new Color(235, 235, 235));
        g2.setStroke(new BasicStroke(0.4f));
        for (int exp = loExp - 1; exp <= hiExp; exp++) {
            for (int mult = 2; mult <= 9; mult++) {
                double logVal = exp + Math.log10(mult);
                if (logVal < viewYMin || logVal > viewYMax) continue;
                double sy = toScreenYLog(logVal, plot);
                g2.drawLine((int)plot.getMinX(), (int)sy, (int)plot.getMaxX(), (int)sy);
            }
        }

        // Major lines at each decade
        g2.setColor(new Color(210, 210, 215));
        g2.setStroke(new BasicStroke(0.6f));
        for (int exp = loExp; exp <= hiExp; exp++) {
            double logVal = exp;
            if (logVal < viewYMin || logVal > viewYMax) continue;
            double sy = toScreenYLog(logVal, plot);
            g2.drawLine((int)plot.getMinX(), (int)sy, (int)plot.getMaxX(), (int)sy);
        }
    }

    // ---- Axes ------------------------------------------------------------

    private void drawAxes(Graphics2D g2, Rectangle2D plot) {
        g2.setColor(new Color(60, 60, 70));
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(plot);

        g2.setFont(TICK_FONT);
        FontMetrics fm = g2.getFontMetrics();

        // X ticks (always linear)
        double[] xTicks = niceTicks(viewXMin, viewXMax, 8);
        for (double t : xTicks) {
            double sx = toScreenX(t, plot);
            if (sx < plot.getMinX() || sx > plot.getMaxX()) continue;
            g2.setColor(new Color(60, 60, 70));
            g2.drawLine((int)sx, (int)plot.getMaxY(), (int)sx, (int)plot.getMaxY() + 4);
            String lbl = formatTick(t);
            g2.drawString(lbl, (int)(sx - fm.stringWidth(lbl) / 2.0), (int)plot.getMaxY() + 15);
        }

        // Y ticks — log or linear
        if (logYMode) {
            drawYTicksLog(g2, plot, fm);
        } else {
            double[] yTicks = niceTicks(viewYMin, viewYMax, 7);
            for (double t : yTicks) {
                double sy = toScreenY(t, plot);
                if (sy < plot.getMinY() || sy > plot.getMaxY()) continue;
                g2.setColor(new Color(60, 60, 70));
                g2.drawLine((int)plot.getMinX() - 4, (int)sy, (int)plot.getMinX(), (int)sy);
                String lbl = formatTick(t);
                g2.drawString(lbl, (int)plot.getMinX() - fm.stringWidth(lbl) - 6,
                        (int)(sy + fm.getAscent() / 2.0 - 1));
            }
        }
    }

    private void drawYTicksLog(Graphics2D g2, Rectangle2D plot, FontMetrics fm) {
        int loExp = (int)Math.floor(viewYMin);
        int hiExp = (int)Math.ceil(viewYMax);

        g2.setColor(new Color(60, 60, 70));

        for (int exp = loExp; exp <= hiExp; exp++) {
            double logVal = exp;
            if (logVal < viewYMin - 0.001 || logVal > viewYMax + 0.001) continue;
            double sy = toScreenYLog(logVal, plot);
            g2.drawLine((int)plot.getMinX() - 4, (int)sy, (int)plot.getMinX(), (int)sy);
            String lbl = formatTickLog(exp);
            g2.drawString(lbl, (int)plot.getMinX() - fm.stringWidth(lbl) - 6,
                    (int)(sy + fm.getAscent() / 2.0 - 1));
        }

        // Minor tick marks at 2,3,5 within each decade (no labels)
        for (int exp = loExp - 1; exp <= hiExp; exp++) {
            for (int mult : new int[]{2, 3, 5}) {
                double logVal = exp + Math.log10(mult);
                if (logVal < viewYMin || logVal > viewYMax) continue;
                double sy = toScreenYLog(logVal, plot);
                g2.drawLine((int)plot.getMinX() - 2, (int)sy, (int)plot.getMinX(), (int)sy);
            }
        }
    }

    // ---- Labels + Legend --------------------------------------------------

    private void drawLabels(Graphics2D g2, Rectangle2D plot) {
        g2.setColor(new Color(30, 30, 40));

        g2.setFont(TITLE_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(title);
        g2.drawString(title, (int)(plot.getCenterX() - tw / 2.0), MT - 10);

        g2.setFont(LABEL_FONT);
        fm = g2.getFontMetrics();
        int xlw = fm.stringWidth(xLabel);
        g2.drawString(xLabel, (int)(plot.getCenterX() - xlw / 2.0), getHeight() - 8);

        AffineTransform orig = g2.getTransform();
        g2.translate(14, plot.getCenterY());
        g2.rotate(-Math.PI / 2);
        int ylw = fm.stringWidth(yLabel);
        g2.drawString(yLabel, -ylw / 2, 0);
        g2.setTransform(orig);
    }

    private void drawLegend(Graphics2D g2, Rectangle2D plot) {
        if (series.isEmpty()) return;
        g2.setFont(LEGEND_FONT);
        FontMetrics fm = g2.getFontMetrics();

        int lineH = 18, boxW = 14, pad = 8;
        int maxLabelW = 0;
        for (SeriesSpec sp : series)
            maxLabelW = Math.max(maxLabelW, fm.stringWidth(sp.label));

        int lgW = boxW + pad + maxLabelW + pad * 2;
        int lgH = series.size() * lineH + pad * 2;
        int lgX = (int)(plot.getMaxX() - lgW - 8);
        int lgY = (int)(plot.getMinY() + 8);

        g2.setColor(new Color(255, 255, 255, 210));
        g2.fillRoundRect(lgX, lgY, lgW, lgH, 6, 6);
        g2.setColor(new Color(160, 160, 170));
        g2.setStroke(new BasicStroke(0.8f));
        g2.drawRoundRect(lgX, lgY, lgW, lgH, 6, 6);

        for (int i = 0; i < series.size(); i++) {
            SeriesSpec sp = series.get(i);
            int rowY = lgY + pad + i * lineH + lineH / 2;
            Color c = new Color(sp.color.getRed(), sp.color.getGreen(), sp.color.getBlue(),
                    Math.min(255, (int)(sp.alpha * 255) + 80));
            if (sp.isBar) {
                g2.setColor(c);
                g2.fillRect(lgX + pad, rowY - 5, boxW, 10);
                g2.setColor(c.darker());
                g2.setStroke(new BasicStroke(0.8f));
                g2.drawRect(lgX + pad, rowY - 5, boxW, 10);
            } else {
                g2.setColor(c);
                g2.setStroke(new BasicStroke(sp.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(lgX + pad, rowY, lgX + pad + boxW, rowY);
            }
            g2.setColor(new Color(30, 30, 40));
            g2.setFont(LEGEND_FONT);
            g2.drawString(sp.label, lgX + pad + boxW + 5, rowY + fm.getAscent() / 2 - 1);
        }
    }

    // ---- Crosshair / cursor tooltip --------------------------------------

    private void updateCursorLabel() {
        if (cursorPoint == null || !viewSet) { cursorLabel = null; return; }
        Rectangle2D plot = plotRect();
        if (!plot.contains(cursorPoint)) { cursorLabel = null; return; }
        double dataX = toDataX(cursorPoint.x, plot);
        double dataY;
        if (logYMode) {
            double logY = viewYMin + (plot.getMaxY() - cursorPoint.y) / plot.getHeight() * (viewYMax - viewYMin);
            dataY = Math.pow(10, logY);
        } else {
            dataY = toDataY(cursorPoint.y, plot);
        }
        String yStr = logYMode ? String.format("%.3e", dataY) : formatTick(dataY);
        cursorLabel = "x=" + formatTick(dataX) + "  y=" + yStr;
    }

    private void drawCrosshair(Graphics2D g2, Rectangle2D plot) {
        if (cursorPoint == null || cursorLabel == null) return;
        if (!plot.contains(cursorPoint)) return;

        int cx = cursorPoint.x, cy = cursorPoint.y;

        g2.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1f, new float[]{4f, 4f}, 0f));
        g2.setColor(new Color(80, 80, 90, 160));
        g2.setClip(plot);
        g2.drawLine(cx, (int)plot.getMinY(), cx, (int)plot.getMaxY());
        g2.drawLine((int)plot.getMinX(), cy, (int)plot.getMaxX(), cy);
        g2.setClip(null);

        g2.setFont(TICK_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(cursorLabel), th = fm.getAscent() + fm.getDescent();
        int pad = 4, bw = tw + pad * 2, bh = th + pad * 2;
        int bx = cx + 8, by = cy - bh - 4;
        if (bx + bw > getWidth() - 4) bx = cx - bw - 8;
        if (by < (int)plot.getMinY()) by = cy + 6;

        g2.setColor(new Color(30, 30, 30, 210));
        g2.fillRoundRect(bx, by, bw, bh, 5, 5);
        g2.setColor(new Color(255, 255, 255, 180));
        g2.drawRoundRect(bx, by, bw, bh, 5, 5);
        g2.setColor(Color.WHITE);
        g2.drawString(cursorLabel, bx + pad, by + pad + fm.getAscent());
    }

    // ---- Coordinate conversion -------------------------------------------

    private double toScreenX(double dataX, Rectangle2D plot) {
        return plot.getMinX() + (dataX - viewXMin) / (viewXMax - viewXMin) * plot.getWidth();
    }

    /** Linear Y → screen. */
    private double toScreenY(double dataY, Rectangle2D plot) {
        return plot.getMaxY() - (dataY - viewYMin) / (viewYMax - viewYMin) * plot.getHeight();
    }

    /**
     * Log Y → screen.  logVal is already log10(dataY); viewYMin/Max are also in log10 space.
     */
    private double toScreenYLog(double logVal, Rectangle2D plot) {
        return plot.getMaxY() - (logVal - viewYMin) / (viewYMax - viewYMin) * plot.getHeight();
    }

    private double toDataX(double sx, Rectangle2D plot) {
        return viewXMin + (sx - plot.getMinX()) / plot.getWidth() * (viewXMax - viewXMin);
    }

    private double toDataY(double sy, Rectangle2D plot) {
        return viewYMin + (plot.getMaxY() - sy) / plot.getHeight() * (viewYMax - viewYMin);
    }

    private Rectangle2D plotRect() {
        return new Rectangle2D.Double(ML, MT, getWidth() - ML - MR, getHeight() - MT - MB);
    }

    // ---- Range helpers ---------------------------------------------------

    private void computeRanges() {
        if (rangesComputed) return;
        double xlo = Double.MAX_VALUE, xhi = -Double.MAX_VALUE;
        double ylo = Double.MAX_VALUE, yhi = -Double.MAX_VALUE;

        for (SeriesSpec sp : series) {
            for (double v : sp.x) {
                if (!Double.isNaN(v)) { xlo = Math.min(xlo, v); xhi = Math.max(xhi, v); }
            }
            for (double v : sp.y) {
                if (Double.isNaN(v)) continue;
                if (logYMode) {
                    if (v > 0) { ylo = Math.min(ylo, Math.log10(v)); yhi = Math.max(yhi, Math.log10(v)); }
                } else {
                    ylo = Math.min(ylo, v); yhi = Math.max(yhi, v);
                }
            }
        }

        if (xlo == Double.MAX_VALUE) { xlo = 0; xhi = 1; }
        if (ylo == Double.MAX_VALUE) { ylo = 0; yhi = 1; }

        double xpad = (xhi - xlo) * 0.02; if (xpad == 0) xpad = 1;
        double ypad, ypadFrac;
        if (logYMode) {
            // Snap to clean decade boundaries
            ylo = Math.floor(ylo);
            yhi = Math.ceil(yhi);
            ypad = 0;
        } else {
            ypad = (yhi - ylo) * 0.05; if (ypad == 0) ypad = 0.1;
        }

        xMin = xlo - xpad; xMax = xhi + xpad;
        yMin = ylo - (logYMode ? 0 : ypad);
        yMax = yhi + (logYMode ? 0 : ypad);

        rangesComputed = true;
    }

    private void ensureView() {
        if (!viewSet) {
            computeRanges();
            viewXMin = xMin; viewXMax = xMax;
            viewYMin = yMin; viewYMax = yMax;
            // Store initial extents for zoom-out hard clamp
            initXMin = xMin; initXMax = xMax;
            initYMin = yMin; initYMax = yMax;
            viewSet = true;
        }
    }

    // ---- Tick / label generation -----------------------------------------

    private double[] niceTicks(double lo, double hi, int targetCount) {
        if (hi <= lo) return new double[]{lo, hi};
        double range    = hi - lo;
        double rawStep  = range / targetCount;
        double mag      = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double step     = roundNice(rawStep / mag) * mag;
        double start    = Math.ceil(lo / step) * step;
        List<Double> ticks = new ArrayList<>();
        for (double t = start; t <= hi + step * 0.001; t += step) ticks.add(t);
        return ticks.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private double roundNice(double v) {
        if (v < 1.5) return 1;
        if (v < 3)   return 2;
        if (v < 7)   return 5;
        return 10;
    }

    private String formatTick(double v) {
        if (v == 0) return "0";
        double abs = Math.abs(v);
        if (abs >= 1e4 || abs < 1e-3) return String.format("%.2e", v);
        if (abs >= 100) return String.format("%.0f", v);
        if (abs >= 10)  return String.format("%.1f", v);
        if (abs >= 1)   return String.format("%.2f", v);
        return String.format("%.3f", v);
    }

    /** Format a log10 exponent as "1e+02", "1e-04", etc. */
    private String formatTickLog(int exp) {
        if (exp >= 0) return String.format("1e+%02d", exp);
        return String.format("1e%03d", exp);   // e.g. 1e-07
    }

    private void drawCentred(Graphics g, String text, int cx, int cy) {
        g.setFont(LABEL_FONT);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(Color.DARK_GRAY);
        g.drawString(text, cx - fm.stringWidth(text) / 2, cy);
    }
}