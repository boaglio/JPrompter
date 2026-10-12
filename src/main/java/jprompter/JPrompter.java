package jprompter;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Main window: script editor + prompter view, toolbar, menus and keyboard shortcuts. */
public class JPrompter extends JFrame {

    static final String VERSION = "1.0.0";
    static final String WEBSITE = "https://github.com/boaglio/JPrompter";
    static final String ACCENT = "#7C6CF2";

    private static final String EDITOR = "editor", PROMPTER = "prompter";
    private static final int WORDS_PER_MINUTE = 140;

    private static final String SAMPLE = """
            Welcome to JPrompter.

            Paste or type your script in the editor, or open a text file (you can also drag and drop one onto the window). Press F5 or the Play button to start prompting.

            [Pause — smile] Anything inside square brackets is shown as a cue in a different color, so you can add stage directions that stand out.

            Keyboard shortcuts while prompting:
            Space starts and pauses. Up and Down change the speed. Left and Right nudge the text one line. Page Up and Page Down jump between paragraphs. Home goes back to the start. Plus and minus change the text size. M mirrors the text, V flips it vertically, G toggles the reading guide and H the status overlay. F11 toggles full screen and Escape leaves it. Ctrl+E switches between the editor and the prompter.

            You can also scroll with the mouse wheel or drag the text, which works even while it is playing.

            Most USB presentation clickers and foot pedals send Page Up, Page Down or Space, so they work out of the box.

            Good luck with your recording!
            """;

    private final Settings settings;
    private final PrompterPanel prompter;
    private final JTextArea editor = new JTextArea();
    private final UndoManager undo = new UndoManager();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JToolBar toolbar = new JToolBar();
    private final JPanel statusBar = new JPanel(new BorderLayout());

    private JButton playButton, themeButton;
    private JToggleButton editButton, promptButton, mirrorButton;
    private JSlider speedSlider, sizeSlider;
    private JLabel speedValue, sizeValue;
    private JLabel fileLabel, wordsLabel, readTimeLabel;
    private JLabel modeStatus, elapsedStatus, remainingStatus, percentStatus;
    private JProgressBar progressBar;
    private SettingsDialog settingsDialog;

    private File currentFile;
    private boolean modified;
    private boolean prompterShown;
    private boolean fullscreen;
    private Rectangle windowedBounds;
    private boolean updatingControls;

    public JPrompter(Settings settings) {
        super("JPrompter");
        this.settings = settings;
        this.prompter = new PrompterPanel(settings);
        setIconImages(Icons.windowIcons());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { exit(); }
        });

        buildToolbar();
        setJMenuBar(buildMenu());
        center.add(buildEditorPage(), EDITOR);
        center.add(prompter, PROMPTER);
        buildStatusBar();
        installPrompterKeys();
        installGlobalKeys();

        getContentPane().add(toolbar, BorderLayout.NORTH);
        getContentPane().add(center, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        prompter.setStateListener(this::syncControls);
        editor.setText(SAMPLE);
        editor.setCaretPosition(0);
        undo.discardAllEdits();
        modified = false;

        setMinimumSize(new Dimension(900, 560));
        setSize(1240, 800);
        setLocationRelativeTo(null);
        showEditor();
        updateTitle();
    }

    // ---------------------------------------------------------------- UI construction

    private void buildToolbar() {
        toolbar.setFloatable(false);
        toolbar.setBorder(new EmptyBorder(6, 10, 6, 10));

        toolbar.add(iconButton("new", "New script (Ctrl+N)", this::newFile));
        toolbar.add(iconButton("open", "Open a text file (Ctrl+O)", this::openFile));
        toolbar.add(iconButton("save", "Save (Ctrl+S)", this::save));
        toolbar.addSeparator(new Dimension(18, 24));

        editButton = modeToggle("Edit", "edit", "Edit the script (Ctrl+E)", this::showEditor);
        promptButton = modeToggle("Prompt", "prompter", "Show the prompter (Ctrl+E)", this::showPrompter);
        ButtonGroup modes = new ButtonGroup();
        modes.add(editButton);
        modes.add(promptButton);
        toolbar.add(editButton);
        toolbar.add(promptButton);
        toolbar.addSeparator(new Dimension(18, 24));

        toolbar.add(iconButton("restart", "Back to the beginning (Home)", () -> { showPrompter(); prompter.reset(); }));
        playButton = new JButton("Play", Icons.colored("play", 16, Color.WHITE));
        playButton.setToolTipText("Start / pause (Space, F5)");
        playButton.setFocusable(false);
        playButton.setIconTextGap(8);
        playButton.putClientProperty(FlatClientProperties.STYLE,
                "arc: 999; background: " + ACCENT + "; foreground: #FFFFFF; hoverBackground: #8D7FF5;"
                        + " pressedBackground: #6A5AE0; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0;"
                        + " margin: 6,16,6,20; font: bold +1");
        playButton.addActionListener(e -> playPause());
        // Wrapped in a panel so FlatLaf paints it as a regular (filled) button, not a flat toolbar button.
        JPanel playWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        playWrap.setOpaque(false);
        playWrap.add(playButton);
        playWrap.setMaximumSize(playWrap.getPreferredSize());
        toolbar.add(playWrap);
        toolbar.addSeparator(new Dimension(18, 24));

        speedValue = valueLabel();
        speedSlider = slider(Settings.MIN_SPEED, Settings.MAX_SPEED, settings.speed, v -> {
            settings.speed = v;
            syncControls();
        });
        toolbar.add(labeled("speed", "Scroll speed (Up / Down)", speedSlider, speedValue));
        toolbar.add(Box.createHorizontalStrut(10));

        sizeValue = valueLabel();
        sizeSlider = slider(Settings.MIN_FONT, Settings.MAX_FONT, settings.fontSize, v -> {
            settings.fontSize = v;
            prompter.settingsChanged();
        });
        toolbar.add(labeled("textsize", "Text size (+ / -)", sizeSlider, sizeValue));

        toolbar.add(Box.createHorizontalGlue());

        mirrorButton = new JToggleButton(Icons.get("mirror", 20));
        mirrorButton.setToolTipText("Mirror horizontally for teleprompter glass (M)");
        mirrorButton.setFocusable(false);
        mirrorButton.addActionListener(e -> { settings.mirrorHorizontal = mirrorButton.isSelected(); prompter.settingsChanged(); });
        toolbar.add(mirrorButton);
        toolbar.add(iconButton("fullscreen", "Full screen (F11, Esc to leave)", this::toggleFullscreen));
        themeButton = iconButton(settings.darkTheme ? "sun" : "moon", "Switch light / dark theme", this::toggleTheme);
        toolbar.add(themeButton);
        toolbar.add(iconButton("settings", "Settings (Ctrl+,)", this::openSettings));
    }

    private JComponent buildEditorPage() {
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setFont(new Font(FlatInterFont.FAMILY, Font.PLAIN, 18));
        editor.setMargin(new Insets(18, 22, 18, 22));
        editor.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Write or paste your script here…");
        editor.getDocument().addUndoableEditListener(e -> undo.addEdit(e.getEdit()));
        editor.getDocument().addDocumentListener(new DocumentListener() {
            void changed() {
                if (!modified) { modified = true; updateTitle(); }
                updateEditorStats();
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        editor.setTransferHandler(new FileDropHandler(editor.getTransferHandler()));
        prompter.setTransferHandler(new FileDropHandler(null));

        InputMap im = editor.getInputMap();
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, menu), "redo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | InputEvent.SHIFT_DOWN_MASK), "redo");
        editor.getActionMap().put("undo", action(() -> { if (undo.canUndo()) undo.undo(); }));
        editor.getActionMap().put("redo", action(() -> { if (undo.canRedo()) undo.redo(); }));

        // Header: title + file name on the left, stats on the right.
        JLabel title = new JLabel("Script");
        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        fileLabel = new JLabel();
        fileLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        titles.add(title);
        titles.add(Box.createVerticalStrut(2));
        titles.add(fileLabel);

        wordsLabel = chip("words");
        readTimeLabel = chip("clock");
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        stats.setOpaque(false);
        stats.add(wordsLabel);
        stats.add(readTimeLabel);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(16, 22, 12, 22));
        header.add(titles, BorderLayout.WEST);
        header.add(stats, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(editor);
        scroll.putClientProperty(FlatClientProperties.STYLE, "arc: 14");
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(0, 22, 0, 22));
        body.add(scroll);

        JLabel hint = new JLabel("Press F5 to start prompting  ·  [Text in brackets] becomes a highlighted cue  ·  Drop a .txt file to open it",
                Icons.muted("info", 14), SwingConstants.LEADING);
        hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(new EmptyBorder(10, 24, 14, 22));

        JPanel page = new JPanel(new BorderLayout());
        page.add(header, BorderLayout.NORTH);
        page.add(body, BorderLayout.CENTER);
        page.add(hint, BorderLayout.SOUTH);
        return page;
    }

    private void buildStatusBar() {
        modeStatus = statusLabel("edit");
        elapsedStatus = statusLabel("clock");
        elapsedStatus.setToolTipText("Elapsed time");
        remainingStatus = statusLabel("timer");
        remainingStatus.setToolTipText("Estimated time remaining at the current speed");
        percentStatus = statusLabel(null);

        progressBar = new JProgressBar(0, 1000);
        progressBar.putClientProperty(FlatClientProperties.STYLE, "arc: 6");
        progressBar.setPreferredSize(new Dimension(160, 6));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);
        right.add(elapsedStatus);
        right.add(remainingStatus);
        JPanel progress = new JPanel(new GridBagLayout());
        progress.setOpaque(false);
        progress.add(progressBar);
        right.add(progress);
        right.add(percentStatus);

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(5, 14, 5, 6));
        content.add(modeStatus, BorderLayout.WEST);
        content.add(right, BorderLayout.EAST);

        statusBar.add(new JSeparator(), BorderLayout.NORTH);
        statusBar.add(content, BorderLayout.CENTER);
    }

    private JMenuBar buildMenu() {
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(item("New", "new", KeyStroke.getKeyStroke(KeyEvent.VK_N, menu), this::newFile));
        file.add(item("Open…", "open", KeyStroke.getKeyStroke(KeyEvent.VK_O, menu), this::openFile));
        file.add(item("Save", "save", KeyStroke.getKeyStroke(KeyEvent.VK_S, menu), this::save));
        file.add(item("Save As…", null, KeyStroke.getKeyStroke(KeyEvent.VK_S, menu | InputEvent.SHIFT_DOWN_MASK), this::saveAs));
        file.addSeparator();
        file.add(item("Paste from clipboard and prompt", "clipboard", null, this::pasteAndPrompt));
        file.addSeparator();
        file.add(item("Exit", "exit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, menu), this::exit));
        bar.add(file);

        JMenu view = new JMenu("View");
        view.setMnemonic('V');
        view.add(item("Editor / Prompter", "prompter", KeyStroke.getKeyStroke(KeyEvent.VK_E, menu), this::toggleMode));
        view.add(item("Full screen", "fullscreen", KeyStroke.getKeyStroke("F11"), this::toggleFullscreen));
        view.add(item("Mirror", "mirror", null, () -> toggle(() -> settings.mirrorHorizontal = !settings.mirrorHorizontal)));
        view.add(item("Reading guide", "guide", null, () -> toggle(() -> settings.showGuide = !settings.showGuide)));
        view.addSeparator();
        view.add(item("Light / dark theme", "moon", null, this::toggleTheme));
        view.add(item("Settings…", "settings", KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, menu), this::openSettings));
        bar.add(view);

        JMenu play = new JMenu("Playback");
        play.setMnemonic('P');
        play.add(item("Play / Pause", "play", KeyStroke.getKeyStroke("F5"), this::playPause));
        play.add(item("Restart", "restart", null, () -> { showPrompter(); prompter.reset(); }));
        play.addSeparator();
        play.add(item("Faster", null, null, () -> prompter.changeSpeed(1)));
        play.add(item("Slower", null, null, () -> prompter.changeSpeed(-1)));
        bar.add(play);

        JMenu help = new JMenu("Help");
        help.add(item("Keyboard shortcuts", "keyboard", KeyStroke.getKeyStroke("F1"), this::showHelp));
        help.add(item("About JPrompter", "info", null, this::showAbout));
        bar.add(help);
        return bar;
    }

    /** Keys that only apply while the prompter has focus (so they don't interfere with typing). */
    private void installPrompterKeys() {
        bind("SPACE", prompter::togglePlay);
        bind("ENTER", prompter::togglePlay);
        bind("UP", () -> prompter.changeSpeed(1));
        bind("DOWN", () -> prompter.changeSpeed(-1));
        bind("shift UP", () -> prompter.changeSpeed(5));
        bind("shift DOWN", () -> prompter.changeSpeed(-5));
        bind("LEFT", () -> prompter.scrollLines(-1));
        bind("RIGHT", () -> prompter.scrollLines(1));
        bind("PAGE_UP", () -> prompter.jumpParagraph(-1));
        bind("PAGE_DOWN", () -> prompter.jumpParagraph(1));
        bind("shift PAGE_UP", () -> prompter.scrollPages(-1));
        bind("shift PAGE_DOWN", () -> prompter.scrollPages(1));
        bind("HOME", prompter::reset);
        bind("END", prompter::jumpToEnd);
        bind("typed +", () -> prompter.changeFontSize(4));
        bind("typed =", () -> prompter.changeFontSize(4));
        bind("typed -", () -> prompter.changeFontSize(-4));
        bind("ADD", () -> prompter.changeFontSize(4));
        bind("SUBTRACT", () -> prompter.changeFontSize(-4));
        bind("M", () -> toggle(() -> settings.mirrorHorizontal = !settings.mirrorHorizontal));
        bind("V", () -> toggle(() -> settings.mirrorVertical = !settings.mirrorVertical));
        bind("G", () -> toggle(() -> settings.showGuide = !settings.showGuide));
        bind("H", () -> toggle(() -> settings.showHud = !settings.showHud));
        bind("F", this::toggleFullscreen);
        bind("ESCAPE", () -> { if (fullscreen) toggleFullscreen(); else prompter.pause(); });
    }

    private void installGlobalKeys() {
        // Menu accelerators cover most; in full screen the menu bar is hidden, so mirror them on the root pane.
        JRootPane root = getRootPane();
        root.registerKeyboardAction(e -> toggleFullscreen(), KeyStroke.getKeyStroke("F11"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(e -> playPause(), KeyStroke.getKeyStroke("F5"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    // ---------------------------------------------------------------- actions

    private void playPause() {
        if (!prompterShown) {
            showPrompter();
            prompter.play();
        } else {
            prompter.togglePlay();
        }
    }

    private void toggleMode() {
        if (prompterShown) showEditor(); else showPrompter();
    }

    private void showPrompter() {
        prompter.setText(editor.getText());
        cards.show(center, PROMPTER);
        prompterShown = true;
        syncControls();
        prompter.requestFocusInWindow();
    }

    private void showEditor() {
        if (fullscreen) toggleFullscreen();
        prompter.pause();
        cards.show(center, EDITOR);
        prompterShown = false;
        syncControls();
        editor.requestFocusInWindow();
    }

    private void toggle(Runnable change) {
        change.run();
        prompter.settingsChanged();
    }

    private void toggleTheme() {
        settings.darkTheme = !settings.darkTheme;
        FlatAnimatedLafChange.showSnapshot();
        applyTheme(settings.darkTheme);
        FlatLaf.updateUI();
        FlatAnimatedLafChange.hideSnapshotWithAnimation();
        themeButton.setIcon(Icons.get(settings.darkTheme ? "sun" : "moon", 20));
        fileLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
    }

    private void toggleFullscreen() {
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        boolean wasPlaying = prompter.isPlaying();
        if (!fullscreen) {
            showPrompter();
            windowedBounds = getBounds();
            fullscreen = true;
            dispose();
            setUndecorated(true);
            setChromeVisible(false);
            if (device.isFullScreenSupported()) {
                device.setFullScreenWindow(this);
            } else {
                setBounds(getGraphicsConfiguration().getBounds());
                setVisible(true);
            }
            prompter.setCursor(blankCursor());
        } else {
            fullscreen = false;
            if (device.getFullScreenWindow() == this) device.setFullScreenWindow(null);
            dispose();
            setUndecorated(false);
            setChromeVisible(true);
            setBounds(windowedBounds);
            setVisible(true);
            prompter.setCursor(Cursor.getDefaultCursor());
        }
        if (wasPlaying && !prompter.isPlaying()) prompter.play();
        SwingUtilities.invokeLater(prompter::requestFocusInWindow);
    }

    private void setChromeVisible(boolean visible) {
        toolbar.setVisible(visible);
        statusBar.setVisible(visible);
        getJMenuBar().setVisible(visible);
    }

    private void openSettings() {
        if (settingsDialog != null) settingsDialog.dispose();
        // Recreated each time so it reflects changes made with keyboard shortcuts.
        settingsDialog = new SettingsDialog(this, settings, prompter::settingsChanged, this::openSettings);
        settingsDialog.setVisible(true);
    }

    private void showHelp() {
        String[][] keys = {
                {"Space · Enter · F5", "Play / pause (with countdown)"},
                {"↑ ↓", "Faster / slower (Shift = ×5)"},
                {"← →", "Nudge back / forward one line"},
                {"PgUp · PgDn", "Previous / next paragraph (Shift = screen)"},
                {"Home · End", "Start / end of script"},
                {"+ −", "Larger / smaller text"},
                {"M · V", "Mirror horizontally / vertically"},
                {"G · H", "Reading guide / status overlay"},
                {"F · F11 · Esc", "Enter / leave full screen"},
                {"Ctrl+E", "Switch editor / prompter"},
                {"Wheel · drag", "Scroll manually"},
        };
        JPanel grid = new JPanel(new GridBagLayout());
        for (int i = 0; i < keys.length; i++) {
            JLabel key = new JLabel(keys[i][0]);
            key.putClientProperty(FlatClientProperties.STYLE,
                    "font: bold; border: 3,9,3,9,$Component.borderColor,1,8; background: $Panel.background");
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = i; c.anchor = GridBagConstraints.EAST; c.insets = new Insets(3, 0, 3, 14);
            grid.add(key, c);
            c = new GridBagConstraints();
            c.gridx = 1; c.gridy = i; c.anchor = GridBagConstraints.WEST;
            grid.add(new JLabel(keys[i][1]), c);
        }
        JOptionPane.showMessageDialog(this, grid, "Keyboard shortcuts", JOptionPane.PLAIN_MESSAGE, null);
    }

    private void showAbout() {
        JLabel logo = new JLabel(Icons.logo(96));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel name = new JLabel("JPrompter");
        name.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1");
        name.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel version = new JLabel("Version " + VERSION);
        version.setForeground(UIManager.getColor("Label.disabledForeground"));
        version.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel desc = new JLabel("A free and open source teleprompter for your desktop.");
        desc.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton link = new JButton(WEBSITE);
        link.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        link.setForeground(UIManager.getColor("Component.accentColor"));
        link.setAlignmentX(Component.CENTER_ALIGNMENT);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(URI.create(WEBSITE));
            } catch (Exception ex) {
                error("Could not open the browser: " + ex.getMessage());
            }
        });
        JLabel license = new JLabel("Licensed under the GNU GPL v3.0");
        license.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        license.setForeground(UIManager.getColor("Label.disabledForeground"));
        license.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 24, 4, 24));
        p.add(logo);
        p.add(Box.createVerticalStrut(12));
        p.add(name);
        p.add(version);
        p.add(Box.createVerticalStrut(12));
        p.add(desc);
        p.add(link);
        p.add(Box.createVerticalStrut(6));
        p.add(license);
        JOptionPane.showMessageDialog(this, p, "About JPrompter", JOptionPane.PLAIN_MESSAGE, null);
    }

    // ---------------------------------------------------------------- files

    private void newFile() {
        if (!confirmDiscard()) return;
        showEditor();
        editor.setText("");
        currentFile = null;
        undo.discardAllEdits();
        modified = false;
        prompter.reset();
        updateTitle();
    }

    private void openFile() {
        if (!confirmDiscard()) return;
        JFileChooser fc = chooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) load(fc.getSelectedFile());
    }

    private void load(File f) {
        try {
            String text;
            try {
                text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            } catch (MalformedInputException e) {
                text = Files.readString(f.toPath(), StandardCharsets.ISO_8859_1);
            }
            if (text.startsWith("﻿")) text = text.substring(1);
            editor.setText(text);
            editor.setCaretPosition(0);
            undo.discardAllEdits();
            currentFile = f;
            modified = false;
            rememberDir(f);
            prompter.setText(text);
            prompter.reset();
            updateTitle();
        } catch (IOException e) {
            error("Could not open " + f.getName() + ":\n" + e.getMessage());
        }
    }

    private boolean save() {
        return currentFile == null ? saveAs() : writeTo(currentFile);
    }

    private boolean saveAs() {
        JFileChooser fc = chooser();
        if (currentFile != null) fc.setSelectedFile(currentFile);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        File f = fc.getSelectedFile();
        if (!f.getName().contains(".")) f = new File(f.getParentFile(), f.getName() + ".txt");
        if (f.exists() && !f.equals(currentFile)
                && JOptionPane.showConfirmDialog(this, f.getName() + " exists. Overwrite?", "Save As",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return false;
        }
        return writeTo(f);
    }

    private boolean writeTo(File f) {
        try {
            Files.writeString(f.toPath(), editor.getText(), StandardCharsets.UTF_8);
            currentFile = f;
            modified = false;
            rememberDir(f);
            updateTitle();
            return true;
        } catch (IOException e) {
            error("Could not save " + f.getName() + ":\n" + e.getMessage());
            return false;
        }
    }

    private void pasteAndPrompt() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) { error("The clipboard has no text."); return; }
            if (!confirmDiscard()) return;
            editor.setText((String) cb.getData(DataFlavor.stringFlavor));
            currentFile = null;
            modified = true;
            prompter.reset();
            updateTitle();
            showPrompter();
        } catch (Exception e) {
            error("Could not read the clipboard: " + e.getMessage());
        }
    }

    private JFileChooser chooser() {
        JFileChooser fc = new JFileChooser(settings.lastDirectory);
        fc.setFileFilter(new FileNameExtensionFilter("Text files (*.txt, *.md)", "txt", "md", "text"));
        return fc;
    }

    private void rememberDir(File f) {
        if (f.getParentFile() != null) settings.lastDirectory = f.getParentFile().getAbsolutePath();
    }

    private boolean confirmDiscard() {
        if (!modified) return true;
        int r = JOptionPane.showConfirmDialog(this, "Save changes to the current script?", "Unsaved changes",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) return save();
        return r == JOptionPane.NO_OPTION;
    }

    private void exit() {
        if (fullscreen) toggleFullscreen();
        if (!confirmDiscard()) return;
        settings.save();
        dispose();
        System.exit(0);
    }

    /** Accepts dropped files; otherwise defers to the component's normal text handling. */
    private class FileDropHandler extends TransferHandler {
        private final TransferHandler delegate;

        FileDropHandler(TransferHandler delegate) { this.delegate = delegate; }

        @Override public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    || (delegate != null && delegate.canImport(s));
        }

        @Override @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            if (s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty() && confirmDiscard()) {
                        load(files.get(0));
                        return true;
                    }
                } catch (Exception e) {
                    error("Could not open the dropped file: " + e.getMessage());
                }
                return false;
            }
            return delegate != null && delegate.importData(s);
        }

        @Override public int getSourceActions(JComponent c) { return delegate == null ? NONE : delegate.getSourceActions(c); }
        @Override public void exportAsDrag(JComponent c, InputEvent e, int action) { if (delegate != null) delegate.exportAsDrag(c, e, action); }
        @Override public void exportToClipboard(JComponent c, Clipboard clip, int action) { if (delegate != null) delegate.exportToClipboard(c, clip, action); }
    }

    // ---------------------------------------------------------------- state sync

    private void syncControls() {
        if (updatingControls) return;
        updatingControls = true;
        try {
            boolean playing = prompter.isPlaying();
            playButton.setText(playing ? "Pause" : "Play");
            playButton.setIcon(Icons.colored(playing ? "pause" : "play", 16, Color.WHITE));
            editButton.setSelected(!prompterShown);
            promptButton.setSelected(prompterShown);
            mirrorButton.setSelected(settings.mirrorHorizontal);
            speedSlider.setValue(settings.speed);
            sizeSlider.setValue(settings.fontSize);
            speedValue.setText(String.valueOf(settings.speed));
            sizeValue.setText(String.valueOf(settings.fontSize));

            modeStatus.setText(prompterShown ? (playing ? "Prompting" : "Prompter · paused") : "Editing");
            modeStatus.setIcon(Icons.muted(prompterShown ? (playing ? "play" : "pause") : "edit", 14));
            elapsedStatus.setText(PrompterPanel.fmt(prompter.elapsedSeconds()));
            remainingStatus.setText("-" + PrompterPanel.fmt(prompter.remainingSeconds()));
            progressBar.setValue((int) Math.round(prompter.progress() * 1000));
            percentStatus.setText(Math.round(prompter.progress() * 100) + "%");
        } finally {
            updatingControls = false;
        }
    }

    private void updateEditorStats() {
        String t = editor.getText().strip();
        int words = t.isEmpty() ? 0 : t.split("\\s+").length;
        wordsLabel.setText(words + (words == 1 ? " word" : " words"));
        double minutes = (double) words / WORDS_PER_MINUTE;
        readTimeLabel.setText(minutes < 1 ? "< 1 min read" : "~" + Math.round(minutes) + " min read");
        readTimeLabel.setToolTipText("Estimated at " + WORDS_PER_MINUTE + " words per minute");
    }

    private void updateTitle() {
        String name = currentFile == null ? "Untitled" : currentFile.getName();
        setTitle((modified ? "• " : "") + name + " — JPrompter");
        fileLabel.setText(currentFile == null ? "Untitled script" : currentFile.getAbsolutePath());
        updateEditorStats();
    }

    // ---------------------------------------------------------------- component helpers

    private void bind(String key, Runnable r) {
        prompter.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        prompter.getActionMap().put(key, action(r));
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }

    private JButton iconButton(String icon, String tip, Runnable r) {
        JButton b = new JButton(Icons.get(icon, 20));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    private JToggleButton modeToggle(String text, String icon, String tip, Runnable r) {
        JToggleButton b = new JToggleButton(text, Icons.get(icon, 18));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setIconTextGap(6);
        b.putClientProperty(FlatClientProperties.STYLE, "margin: 4,10,4,12; font: bold");
        b.addActionListener(e -> r.run());
        return b;
    }

    private JPanel labeled(String icon, String tip, JSlider slider, JLabel value) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        p.setToolTipText(tip);
        slider.setToolTipText(tip);
        JLabel l = new JLabel(Icons.get(icon, 20));
        l.setToolTipText(tip);
        p.add(l);
        p.add(slider);
        p.add(value);
        p.setMaximumSize(p.getPreferredSize());
        return p;
    }

    private JSlider slider(int min, int max, int value, java.util.function.IntConsumer onChange) {
        JSlider s = new JSlider(min, max, value);
        s.setFocusable(false);
        s.setPreferredSize(new Dimension(120, s.getPreferredSize().height));
        s.addChangeListener(e -> { if (!updatingControls) onChange.accept(s.getValue()); });
        return s;
    }

    private static JLabel valueLabel() {
        JLabel l = new JLabel("000", SwingConstants.CENTER);
        l.putClientProperty(FlatClientProperties.STYLE,
                "font: bold; border: 2,6,2,6,$Component.borderColor,1,999");
        l.setPreferredSize(new Dimension(l.getPreferredSize().width + 6, l.getPreferredSize().height));
        return l;
    }

    private static JLabel chip(String icon) {
        JLabel l = new JLabel(" ", Icons.muted(icon, 14), SwingConstants.LEADING);
        l.setIconTextGap(6);
        l.putClientProperty(FlatClientProperties.STYLE, "border: 4,10,4,10,$Component.borderColor,1,999");
        return l;
    }

    private static JLabel statusLabel(String icon) {
        JLabel l = new JLabel(" ", icon == null ? null : Icons.muted(icon, 14), SwingConstants.LEADING);
        l.setIconTextGap(6);
        l.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        return l;
    }

    private static JMenuItem item(String text, String icon, KeyStroke key, Runnable r) {
        JMenuItem i = new JMenuItem(text, icon == null ? null : Icons.get(icon, 16));
        if (key != null) i.setAccelerator(key);
        i.addActionListener(e -> r.run());
        return i;
    }

    private static Cursor blankCursor() {
        return Toolkit.getDefaultToolkit().createCustomCursor(
                new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "JPrompter", JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------- startup

    static void setupLookAndFeel(boolean dark) {
        FlatInterFont.install();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", ACCENT));
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("TabbedPane.showTabSeparators", true);
        applyTheme(dark);
    }

    static void applyTheme(boolean dark) {
        if (dark) FlatMacDarkLaf.setup(); else FlatMacLightLaf.setup();
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("flatlaf.menuBarEmbedded", "true");
        Settings settings = Settings.load();
        SwingUtilities.invokeLater(() -> {
            setupLookAndFeel(settings.darkTheme);
            JPrompter app = new JPrompter(settings);
            app.setVisible(true);
            if (args.length > 0) app.load(new File(args[0]));
        });
    }
}
