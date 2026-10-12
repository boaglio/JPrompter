package jprompter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scrolling prompter view. Text is word-wrapped into lines and drawn relative to
 * {@link #offset}, the document y-coordinate currently sitting on the reading guide.
 * Text inside [square brackets] is rendered as a cue (italic, cue color).
 */
public class PrompterPanel extends JComponent {

    private static final Pattern CUE = Pattern.compile("\\[[^\\]\\n]*\\]");

    private record Line(TextLayout layout, float top, float x) {}

    private final Settings settings;
    private Runnable stateListener = () -> {};

    private String text = "";
    private List<Line> lines = List.of();
    private List<Float> paragraphTops = List.of();
    private float docHeight;
    private float lineHeight = 1;
    private float ascent;
    private boolean layoutDirty = true;
    private int layoutWidth = -1;

    private double offset;
    private boolean playing;
    private double countdownLeft;
    private double elapsedSeconds;
    private long lastTick;
    private final Timer timer = new Timer(1000 / 120, e -> tick());

    private static final Color HUD_TEXT = new Color(235, 235, 240);
    private final Icon hudPlay = Icons.colored("play", 14, HUD_TEXT);
    private final Icon hudPause = Icons.colored("pause", 14, HUD_TEXT);
    private final Icon hudSpeed = Icons.colored("speed", 14, HUD_TEXT);
    private final Icon hudClock = Icons.colored("clock", 14, HUD_TEXT);
    private final Icon hudTimer = Icons.colored("timer", 14, HUD_TEXT);
    private final Icon emptyLogo = Icons.logo(112);

    private Point dragStart;
    private double dragStartOffset;

    public PrompterPanel(Settings settings) {
        this.settings = settings;
        setFocusable(true);
        setOpaque(true);
        timer.setCoalesce(true);
        installMouse();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { layoutDirty = true; }
        });
    }

    // ---------------------------------------------------------------- public API

    public void setStateListener(Runnable listener) { this.stateListener = listener; }

    public void setText(String newText) {
        if (newText.equals(text)) return;
        text = newText;
        layoutDirty = true;
        ensureLayout();
        offset = Math.min(offset, docHeight);
        repaint();
        stateListener.run();
    }

    /** Call after any setting changed; keeps the relative reading position. */
    public void settingsChanged() {
        layoutDirty = true;
        repaint();
        stateListener.run();
    }

    public boolean isPlaying() { return playing || countdownLeft > 0; }

    public void togglePlay() {
        if (isPlaying()) pause(); else play();
    }

    public void play() {
        if (isPlaying()) return;
        if (offset >= docHeight && docHeight > 0) offset = 0;   // restart when at the end
        countdownLeft = settings.countdownSeconds;
        playing = true;
        lastTick = System.nanoTime();
        timer.start();
        stateListener.run();
        repaint();
    }

    public void pause() {
        playing = false;
        countdownLeft = 0;
        timer.stop();
        stateListener.run();
        repaint();
    }

    public void reset() {
        pause();
        offset = 0;
        elapsedSeconds = 0;
        stateListener.run();
        repaint();
    }

    public void changeSpeed(int delta) {
        settings.speed = Settings.clamp(settings.speed + delta, Settings.MIN_SPEED, Settings.MAX_SPEED);
        stateListener.run();
        repaint();
    }

    public void changeFontSize(int delta) {
        settings.fontSize = Settings.clamp(settings.fontSize + delta, Settings.MIN_FONT, Settings.MAX_FONT);
        settingsChanged();
    }

    public void scrollBy(double pixels) {
        ensureLayout();
        offset = Math.max(0, Math.min(docHeight, offset + pixels));
        repaint();
        stateListener.run();
    }

    public void scrollLines(int n) { ensureLayout(); scrollBy(n * lineHeight); }

    public void scrollPages(int n) {
        ensureLayout();
        scrollBy(n * Math.max(lineHeight, getHeight() * 0.8));
    }

    public void jumpToEnd() { ensureLayout(); offset = docHeight; repaint(); stateListener.run(); }

    /** Jump to the next (dir > 0) or previous (dir < 0) paragraph start. */
    public void jumpParagraph(int dir) {
        ensureLayout();
        double target = dir > 0 ? docHeight : 0;
        if (dir > 0) {
            for (float t : paragraphTops) if (t > offset + 1) { target = t; break; }
        } else {
            for (int i = paragraphTops.size() - 1; i >= 0; i--) {
                if (paragraphTops.get(i) < offset - 1) { target = paragraphTops.get(i); break; }
            }
        }
        offset = target;
        repaint();
        stateListener.run();
    }

    public double progress() { return docHeight <= 0 ? 0 : offset / docHeight; }

    public double elapsedSeconds() { return elapsedSeconds; }

    public double pixelsPerSecond() {
        // speed 20 == one line per second; scales with font size so reading pace is stable.
        return settings.speed * lineHeight / 20.0;
    }

    public double remainingSeconds() {
        double pps = pixelsPerSecond();
        return pps <= 0 ? 0 : Math.max(0, docHeight - offset) / pps;
    }

    // ---------------------------------------------------------------- animation

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastTick) / 1e9);
        lastTick = now;

        if (countdownLeft > 0) {
            countdownLeft -= dt;
            if (countdownLeft <= 0) countdownLeft = 0;
            repaint();
            return;
        }
        if (!playing) return;

        ensureLayout();
        offset += pixelsPerSecond() * dt;
        elapsedSeconds += dt;
        if (offset >= docHeight) {
            offset = docHeight;
            pause();
        }
        repaint();
        stateListener.run();
    }

    // ---------------------------------------------------------------- layout

    private void ensureLayout() {
        if (!layoutDirty && layoutWidth == getWidth()) return;
        double oldProgress = progress();
        relayout(new FontRenderContext(null, true, true));
        offset = oldProgress * docHeight;
    }

    private void relayout(FontRenderContext frc) {
        int width = Math.max(getWidth(), 200);
        float margin = width * settings.marginPercent / 100f;
        float wrapWidth = Math.max(50, width - 2 * margin);

        Font base = settings.font();
        Font cueFont = base.deriveFont(base.getStyle() | Font.ITALIC);
        LineMetrics lm = base.getLineMetrics("Ág", frc);
        ascent = lm.getAscent();
        lineHeight = (lm.getAscent() + lm.getDescent() + lm.getLeading()) * settings.lineSpacing;

        List<Line> out = new ArrayList<>();
        List<Float> paras = new ArrayList<>();
        float y = 0;
        for (String para : text.split("\n", -1)) {
            para = para.replace("\r", "").replace("\t", "    ");
            if (para.isBlank()) { y += lineHeight; continue; }
            paras.add(y);

            AttributedString as = new AttributedString(para);
            as.addAttribute(TextAttribute.FONT, base);
            as.addAttribute(TextAttribute.FOREGROUND, settings.textColor);
            Matcher m = CUE.matcher(para);
            while (m.find()) {
                as.addAttribute(TextAttribute.FONT, cueFont, m.start(), m.end());
                as.addAttribute(TextAttribute.FOREGROUND, settings.cueColor, m.start(), m.end());
            }

            LineBreakMeasurer lbm = new LineBreakMeasurer(as.getIterator(), frc);
            while (lbm.getPosition() < para.length()) {
                TextLayout tl = lbm.nextLayout(wrapWidth);
                float adv = tl.getVisibleAdvance();
                float x = switch (settings.align) {
                    case LEFT -> margin;
                    case CENTER -> margin + (wrapWidth - adv) / 2;
                    case RIGHT -> margin + wrapWidth - adv;
                };
                out.add(new Line(tl, y, x));
                y += lineHeight;
            }
        }
        lines = out;
        paragraphTops = paras;
        docHeight = y;
        layoutDirty = false;
        layoutWidth = getWidth();
    }

    // ---------------------------------------------------------------- painting

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int w = getWidth(), h = getHeight();
            g.setColor(settings.backgroundColor);
            g.fillRect(0, 0, w, h);
            ensureLayout();

            if (text.isBlank()) {
                paintEmpty(g, w, h);
                return;
            }

            AffineTransform plain = g.getTransform();
            if (settings.mirrorHorizontal) { g.translate(w, 0); g.scale(-1, 1); }
            if (settings.mirrorVertical) { g.translate(0, h); g.scale(1, -1); }

            float guideY = h * settings.guidePosition;
            // Fractional offset keeps the scroll smooth instead of jumping whole pixels.
            float shift = (float) (guideY - offset);

            if (settings.showGuide) paintGuide(g, w, guideY);

            for (Line line : lines) {
                float top = line.top + shift;
                if (top + lineHeight < 0) continue;
                if (top > h) break;
                line.layout.draw(g, line.x, top + ascent);
            }

            if (settings.fadeEdges) paintFades(g, w, h);
            if (countdownLeft > 0) paintCountdown(g, w, h);

            g.setTransform(plain);
            if (settings.showHud) paintHud(g, w, h);
        } finally {
            g.dispose();
        }
        Toolkit.getDefaultToolkit().sync();
    }

    private void paintGuide(Graphics2D g, int w, float guideY) {
        Color c = settings.guideColor;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        g.fillRect(0, Math.round(guideY), w, Math.round(lineHeight));

        float size = Math.max(10, lineHeight * 0.35f);
        float cy = guideY + lineHeight / 2;
        g.setColor(c);
        Path2D left = new Path2D.Float();
        left.moveTo(4, cy - size);
        left.lineTo(4 + size * 1.2, cy);
        left.lineTo(4, cy + size);
        left.closePath();
        g.fill(left);
        Path2D right = new Path2D.Float();
        right.moveTo(w - 4, cy - size);
        right.lineTo(w - 4 - size * 1.2, cy);
        right.lineTo(w - 4, cy + size);
        right.closePath();
        g.fill(right);
    }

    private void paintFades(Graphics2D g, int w, int h) {
        Color bg = settings.backgroundColor;
        Color clear = new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 0);
        int fade = h / 6;
        g.setPaint(new GradientPaint(0, 0, bg, 0, fade, clear));
        g.fillRect(0, 0, w, fade);
        g.setPaint(new GradientPaint(0, h - fade, clear, 0, h, bg));
        g.fillRect(0, h - fade, w, fade);
    }

    private void paintCountdown(Graphics2D g, int w, int h) {
        Color bg = settings.backgroundColor;
        g.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 210));
        g.fillRect(0, 0, w, h);

        float d = Math.min(w, h) * 0.5f;
        float cx = w / 2f, cy = h / 2f;
        Color c = settings.guideColor;
        g.setStroke(new BasicStroke(Math.max(4, d / 28), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 50));
        g.draw(new Ellipse2D.Float(cx - d / 2, cy - d / 2, d, d));
        double fraction = countdownLeft - Math.floor(countdownLeft);
        if (fraction == 0) fraction = 1;
        g.setColor(c);
        g.draw(new Arc2D.Double(cx - d / 2, cy - d / 2, d, d, 90, 360 * fraction, Arc2D.OPEN));

        String n = String.valueOf((int) Math.ceil(countdownLeft));
        g.setFont(settings.font().deriveFont(Font.BOLD, d * 0.55f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(settings.textColor);
        g.drawString(n, cx - fm.stringWidth(n) / 2f, cy - fm.getHeight() / 2f + fm.getAscent());
    }

    private void paintEmpty(Graphics2D g, int w, int h) {
        int size = emptyLogo.getIconHeight();
        int y = h / 2 - size;
        emptyLogo.paintIcon(this, g, (w - size) / 2, y);
        y += size + 36;

        Color fg = settings.textColor;
        g.setColor(fg);
        g.setFont(new Font("Inter", Font.BOLD, 26));
        centered(g, "Nothing to prompt yet", w, y);
        g.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150));
        g.setFont(new Font("Inter", Font.PLAIN, 16));
        centered(g, "Write or paste your script in the editor (Ctrl+E), open a file, or drop one here.", w, y + 34);
    }

    private static void centered(Graphics2D g, String s, int w, int baseline) {
        g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, baseline);
    }

    private void paintHud(Graphics2D g, int w, int h) {
        int barH = 4;
        g.setColor(new Color(255, 255, 255, 30));
        g.fillRect(0, h - barH, w, barH);
        g.setColor(settings.guideColor);
        g.fillRect(0, h - barH, (int) Math.round(w * progress()), barH);

        String[] texts = {
                countdownLeft > 0 ? "Starting" : playing ? "Playing" : "Paused",
                String.valueOf(settings.speed),
                fmt(elapsedSeconds),
                "-" + fmt(remainingSeconds()),
                Math.round(progress() * 100) + "%"
        };
        Icon[] icons = { playing ? hudPlay : hudPause, hudSpeed, hudClock, hudTimer, null };

        g.setFont(new Font("Inter", Font.BOLD, 13));
        FontMetrics fm = g.getFontMetrics();
        int pad = 14, gap = 18, iconGap = 6;
        int total = pad * 2 - gap;
        for (int i = 0; i < texts.length; i++) {
            total += gap + fm.stringWidth(texts[i]) + (icons[i] != null ? icons[i].getIconWidth() + iconGap : 0);
        }
        int pillH = 30;
        int x = w - total - 12, y = h - barH - pillH - 10;
        g.setColor(new Color(20, 20, 28, 185));
        g.fill(new RoundRectangle2D.Float(x, y, total, pillH, pillH, pillH));
        g.setColor(new Color(255, 255, 255, 28));
        g.draw(new RoundRectangle2D.Float(x + .5f, y + .5f, total - 1, pillH - 1, pillH, pillH));

        int cx = x + pad;
        int baseline = y + (pillH - fm.getHeight()) / 2 + fm.getAscent();
        for (int i = 0; i < texts.length; i++) {
            if (icons[i] != null) {
                icons[i].paintIcon(this, g, cx, y + (pillH - icons[i].getIconHeight()) / 2);
                cx += icons[i].getIconWidth() + iconGap;
            }
            g.setColor(HUD_TEXT);
            g.drawString(texts[i], cx, baseline);
            cx += fm.stringWidth(texts[i]) + gap;
        }
    }

    static String fmt(double seconds) {
        long s = Math.round(seconds);
        return s >= 3600
                ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format("%02d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- mouse

    private void installMouse() {
        addMouseWheelListener(e -> {
            double dir = settings.mirrorVertical ? -1 : 1;
            scrollBy(dir * e.getPreciseWheelRotation() * lineHeight);
        });
        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                dragStart = e.getPoint();
                dragStartOffset = offset;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                double dy = e.getY() - dragStart.y;
                if (settings.mirrorVertical) dy = -dy;
                offset = Math.max(0, Math.min(docHeight, dragStartOffset - dy));
                repaint();
                stateListener.run();
            }
            @Override public void mouseReleased(MouseEvent e) { dragStart = null; }
        };
        addMouseListener(drag);
        addMouseMotionListener(drag);
    }
}
