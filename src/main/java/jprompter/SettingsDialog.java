package jprompter;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Non-modal settings window; every change applies live to the prompter. */
public class SettingsDialog extends JDialog {

    private final Settings s;
    private final Runnable onChange;
    private final List<Runnable> swatchRefreshers = new ArrayList<>();
    private JPanel form;
    private int row;

    public SettingsDialog(Frame owner, Settings settings, Runnable onChange, Runnable onReset) {
        super(owner, "Settings", false);
        this.s = settings;
        this.onChange = onChange;

        JTabbedPane tabs = new JTabbedPane();
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT, 40);
        tabs.addTab("Text", Icons.get("text", 16), textTab());
        tabs.addTab("Colors", Icons.get("palette", 16), colorsTab());
        tabs.addTab("Display", Icons.get("display", 16), displayTab());
        tabs.addTab("Playback", Icons.get("play", 16), playbackTab());

        JButton reset = new JButton("Reset to defaults", Icons.get("restart", 16));
        reset.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Reset all prompter settings to their defaults?",
                    "Reset settings", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                s.resetPrompterDefaults();
                onChange.run();
                dispose();
                onReset.run();
            }
        });
        JButton close = new JButton("Done");
        close.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        close.addActionListener(e -> setVisible(false));

        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(new EmptyBorder(8, 12, 12, 12));
        south.add(reset, BorderLayout.WEST);
        south.add(close, BorderLayout.EAST);

        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> setVisible(false),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setMinimumSize(new Dimension(Math.max(getWidth(), 480), getHeight()));
        setLocationRelativeTo(owner);
    }

    // ---------------------------------------------------------------- tabs

    private JComponent textTab() {
        newForm();
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> font = new JComboBox<>(families);
        font.setSelectedItem(s.fontFamily);
        font.setMaximumRowCount(16);
        font.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                super.getListCellRendererComponent(l, v, i, sel, f);
                // Preview each family in its own face.
                if (i >= 0 && v != null) setFont(new Font((String) v, Font.PLAIN, 14));
                return this;
            }
        });
        font.addActionListener(e -> { s.fontFamily = (String) font.getSelectedItem(); changed(); });
        add("Font", font);

        add("Size", intSpinner(s.fontSize, Settings.MIN_FONT, Settings.MAX_FONT, 2,
                () -> s.fontSize, v -> s.fontSize = v));
        add("Bold", toggle(s.bold, v -> s.bold = v));

        JSpinner spacing = new JSpinner(new SpinnerNumberModel((double) s.lineSpacing, 0.8, 3.0, 0.1));
        spacing.addChangeListener(e -> { s.lineSpacing = ((Number) spacing.getValue()).floatValue(); changed(); });
        add("Line spacing", spacing);

        add("Side margins (%)", intSpinner(s.marginPercent, 0, 40, 1, () -> s.marginPercent, v -> s.marginPercent = v));

        JComboBox<Settings.Align> align = new JComboBox<>(Settings.Align.values());
        align.setSelectedItem(s.align);
        align.addActionListener(e -> { s.align = (Settings.Align) align.getSelectedItem(); changed(); });
        add("Alignment", align);
        hint("Tip: text inside [square brackets] is shown as a highlighted cue.");
        return wrap();
    }

    private JComponent colorsTab() {
        newForm();
        add("Text", colorButton("Text color", () -> s.textColor, c -> s.textColor = c));
        add("Background", colorButton("Background color", () -> s.backgroundColor, c -> s.backgroundColor = c));
        add("Cues [like this]", colorButton("Cue color", () -> s.cueColor, c -> s.cueColor = c));
        add("Reading guide", colorButton("Guide color", () -> s.guideColor, c -> s.guideColor = c));

        JPanel presets = new JPanel(new GridLayout(1, 0, 6, 0));
        presets.add(preset("Classic", Color.WHITE, Color.BLACK));
        presets.add(preset("Amber", new Color(0xFFD54F), Color.BLACK));
        presets.add(preset("Paper", new Color(0x1A1A1A), new Color(0xFAFAF7)));
        presets.add(preset("Night", new Color(0xCFE3FF), new Color(0x0B1220)));
        add("Presets", presets);
        return wrap();
    }

    private JComponent displayTab() {
        newForm();
        add("Mirror horizontally", toggle(s.mirrorHorizontal, v -> s.mirrorHorizontal = v));
        add("Mirror vertically", toggle(s.mirrorVertical, v -> s.mirrorVertical = v));
        add("Reading guide", toggle(s.showGuide, v -> s.showGuide = v));

        JSlider guide = new JSlider(5, 80, Math.round(s.guidePosition * 100));
        guide.addChangeListener(e -> { s.guidePosition = guide.getValue() / 100f; changed(); });
        add("Guide position", guide);

        add("Fade top and bottom", toggle(s.fadeEdges, v -> s.fadeEdges = v));
        add("Status overlay", toggle(s.showHud, v -> s.showHud = v));
        return wrap();
    }

    private JComponent playbackTab() {
        newForm();
        add("Countdown (seconds)", intSpinner(s.countdownSeconds, 0, 10, 1,
                () -> s.countdownSeconds, v -> s.countdownSeconds = v));
        add("Speed", intSpinner(s.speed, Settings.MIN_SPEED, Settings.MAX_SPEED, 1, () -> s.speed, v -> s.speed = v));
        hint("Speed 20 scrolls about one line per second at any text size.");
        return wrap();
    }

    // ---------------------------------------------------------------- form helpers

    private void changed() {
        onChange.run();
    }

    private void newForm() {
        form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 20, 16, 20));
        row = 0;
    }

    private JComponent wrap() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(form, BorderLayout.NORTH);
        return p;
    }

    private void add(String label, JComponent comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(5, 0, 5, 24);
        form.add(new JLabel(label), c);
        c = new GridBagConstraints();
        c.gridx = 1; c.gridy = row++; c.weightx = 1; c.insets = new Insets(5, 0, 5, 0);
        if (comp instanceof AbstractButton && !(comp instanceof JButton)) {
            c.anchor = GridBagConstraints.EAST;
        } else {
            c.fill = GridBagConstraints.HORIZONTAL;
        }
        form.add(comp, c);
    }

    private void hint(String text) {
        JLabel l = new JLabel(text, Icons.muted("info", 14), SwingConstants.LEADING);
        l.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        l.setForeground(UIManager.getColor("Label.disabledForeground"));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row++; c.gridwidth = 2; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(14, 0, 0, 0);
        form.add(l, c);
    }

    /** A check box aligned to the right of its label. */
    private JCheckBox toggle(boolean initial, Consumer<Boolean> setter) {
        JCheckBox cb = new JCheckBox();
        cb.setSelected(initial);
        cb.addActionListener(e -> { setter.accept(cb.isSelected()); changed(); });
        return cb;
    }

    private JSpinner intSpinner(int value, int min, int max, int step, Supplier<Integer> getter, Consumer<Integer> setter) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        sp.addChangeListener(e -> {
            int v = (Integer) sp.getValue();
            if (v != getter.get()) { setter.accept(v); changed(); }
        });
        return sp;
    }

    private JButton colorButton(String title, Supplier<Color> getter, Consumer<Color> setter) {
        JButton b = new JButton();
        b.setIcon(new Swatch(getter));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        Runnable refresh = () -> {
            Color c = getter.get();
            b.setText(String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
            b.repaint();
        };
        refresh.run();
        swatchRefreshers.add(refresh);
        b.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, title, getter.get());
            if (c != null) { setter.accept(c); refresh.run(); changed(); }
        });
        return b;
    }

    private JButton preset(String name, Color fg, Color bg) {
        JButton b = new JButton(name);
        b.putClientProperty(FlatClientProperties.STYLE, String.format(
                "background: #%06X; foreground: #%06X; hoverBackground: #%06X; font: bold",
                bg.getRGB() & 0xFFFFFF, fg.getRGB() & 0xFFFFFF, bg.getRGB() & 0xFFFFFF));
        b.addActionListener(e -> {
            s.textColor = fg;
            s.backgroundColor = bg;
            swatchRefreshers.forEach(Runnable::run);
            changed();
        });
        return b;
    }

    /** A rounded color chip used as the icon of color buttons. */
    private static final class Swatch implements Icon {
        private final Supplier<Color> color;

        Swatch(Supplier<Color> color) { this.color = color; }

        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color.get());
            g.fillRoundRect(x, y, getIconWidth(), getIconHeight(), 8, 8);
            g.setColor(new Color(128, 128, 128, 120));
            g.drawRoundRect(x, y, getIconWidth() - 1, getIconHeight() - 1, 8, 8);
            g.dispose();
        }

        @Override public int getIconWidth() { return 28; }
        @Override public int getIconHeight() { return 18; }
    }
}
