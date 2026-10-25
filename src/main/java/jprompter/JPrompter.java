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

    static final String VERSION = "1.1.0";
    static final String WEBSITE = "https://github.com/boaglio/JPrompter";
    static final String ACCENT = "#7C6CF2";

    private static final String EDITOR = "editor", PROMPTER = "prompter";
    private static final int WORDS_PER_MINUTE = 140;


    private final Settings settings;
    private final PrompterPanel prompter;
    private final MarkdownEditor editor = new MarkdownEditor();
    private final Map<String, AbstractButton> formatButtons = new java.util.LinkedHashMap<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JToolBar toolbar = new JToolBar();
    private final JPanel statusBar = new JPanel(new BorderLayout());

    private JButton playButton, themeButton;
    private JToggleButton editButton, promptButton, mirrorButton;
    private JSlider speedSlider, sizeSlider;
    private JLabel speedValue, sizeValue, autoSpeedValue;
    private JToggleButton paceSpeedButton, paceTimeButton;
    private JRadioButtonMenuItem paceSpeedItem, paceTimeItem;
    private final CardLayout paceCards = new CardLayout();
    private final JPanel paceControls = new JPanel(paceCards);
    private DurationSpinner durationSpinner;
    private final java.util.Map<I18n.Language, JRadioButtonMenuItem> languageItems = new java.util.EnumMap<>(I18n.Language.class);
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
    /** Pushes editor text to the prompter shortly after typing stops, so time mode can size its speed. */
    private final Timer textSync;

    public JPrompter(Settings settings) {
        super("JPrompter");
        this.settings = settings;
        this.prompter = new PrompterPanel(settings);
        this.textSync = new Timer(300, e -> prompter.setText(editor.getText()));
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

        textSync.setRepeats(false);
        prompter.setStateListener(this::syncControls);
        editor.setText(I18n.t("sample"));
        editor.setCaretPosition(0);
        editor.discardUndo();
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

        toolbar.add(iconButton("new", I18n.t("toolbar.new"), this::newFile));
        toolbar.add(iconButton("open", I18n.t("toolbar.open"), this::openFile));
        toolbar.add(iconButton("save", I18n.t("toolbar.save"), this::save));
        toolbar.addSeparator(new Dimension(18, 24));

        editButton = modeToggle(I18n.t("mode.edit"), "edit", I18n.t("mode.edit.tip"), this::showEditor);
        promptButton = modeToggle(I18n.t("mode.prompt"), "prompter", I18n.t("mode.prompt.tip"), this::showPrompter);
        ButtonGroup modes = new ButtonGroup();
        modes.add(editButton);
        modes.add(promptButton);
        toolbar.add(editButton);
        toolbar.add(promptButton);
        toolbar.addSeparator(new Dimension(18, 24));

        toolbar.add(iconButton("restart", I18n.t("toolbar.restart"), () -> { showPrompter(); prompter.reset(); }));
        playButton = new JButton(I18n.t("play.play"), Icons.colored("play", 16, Color.WHITE));
        playButton.setToolTipText(I18n.t("play.tip"));
        playButton.setFocusable(false);
        playButton.setIconTextGap(8);
        playButton.putClientProperty(FlatClientProperties.STYLE,
                "arc: 999; background: " + ACCENT + "; foreground: #FFFFFF; hoverBackground: #8D7FF5;"
                        + " pressedBackground: #6A5AE0; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0;"
                        + " margin: 6,16,6,20; font: bold +1");
        playButton.addActionListener(e -> playPause());
        // Size for the longer of the two labels so the button doesn't change width when toggled.
        playButton.setText(I18n.t("play.pause"));
        Dimension pauseSize = playButton.getPreferredSize();
        playButton.setText(I18n.t("play.play"));
        Dimension playSize = playButton.getPreferredSize();
        playButton.setPreferredSize(new Dimension(Math.max(pauseSize.width, playSize.width), playSize.height));
        // Wrapped in a panel so FlatLaf paints it as a regular (filled) button, not a flat toolbar button.
        JPanel playWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        playWrap.setOpaque(false);
        playWrap.add(playButton);
        playWrap.setMaximumSize(playWrap.getPreferredSize());
        toolbar.add(playWrap);
        toolbar.addSeparator(new Dimension(18, 24));

        // Pace: choose the speed by hand, or set a duration and let the speed follow from it.
        paceSpeedButton = paceToggle(I18n.t("pace.speed"), I18n.t("pace.speed.tip"), Settings.Pace.SPEED);
        paceTimeButton = paceToggle(I18n.t("pace.time"), I18n.t("pace.time.tip"), Settings.Pace.TIME);
        ButtonGroup paces = new ButtonGroup();
        paces.add(paceSpeedButton);
        paces.add(paceTimeButton);
        toolbar.add(paceSpeedButton);
        toolbar.add(paceTimeButton);
        toolbar.add(Box.createHorizontalStrut(6));

        speedValue = valueLabel();
        speedSlider = slider(Settings.MIN_SPEED, Settings.MAX_SPEED, settings.speed, v -> {
            settings.speed = v;
            syncControls();
        });
        paceControls.setOpaque(false);
        paceControls.add(labeled("speed", I18n.t("speed.tip"), speedSlider, speedValue), Settings.Pace.SPEED.name());

        durationSpinner = new DurationSpinner(prompter.targetSeconds(), v -> {
            if (updatingControls) return;
            settings.targetSeconds = v;
            prompter.settingsChanged();
        });
        String timeTip = I18n.t("time.tip");
        durationSpinner.setToolTipText(timeTip);
        JFormattedTextField durationField = durationSpinner.getTextField();
        durationField.setToolTipText(timeTip);
        // Enter commits the time and hands the keyboard back to the prompter.
        durationField.addActionListener(e -> { durationSpinner.commit(); focusPrompter(); });
        autoSpeedValue = valueLabel();
        autoSpeedValue.setToolTipText(I18n.t("time.autoSpeed.tip"));
        JPanel timePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        timePanel.setOpaque(false);
        JLabel timerIcon = new JLabel(Icons.get("timer", 20));
        timerIcon.setToolTipText(timeTip);
        timePanel.add(timerIcon);
        timePanel.add(durationSpinner);
        timePanel.add(autoSpeedValue);
        paceControls.add(timePanel, Settings.Pace.TIME.name());
        Dimension pc = paceControls.getPreferredSize();
        paceControls.setMaximumSize(pc);
        toolbar.add(paceControls);
        toolbar.addSeparator(new Dimension(18, 24));

        sizeValue = valueLabel();
        sizeSlider = slider(Settings.MIN_FONT, Settings.MAX_FONT, settings.fontSize, v -> {
            settings.fontSize = v;
            prompter.settingsChanged();
        });
        toolbar.add(labeled("textsize", I18n.t("size.tip"), sizeSlider, sizeValue));

        toolbar.add(Box.createHorizontalGlue());

        mirrorButton = new JToggleButton(Icons.get("mirror", 20));
        mirrorButton.setToolTipText(I18n.t("toolbar.mirror"));
        mirrorButton.setFocusable(false);
        mirrorButton.addActionListener(e -> {
            settings.mirrorHorizontal = mirrorButton.isSelected();
            prompter.settingsChanged();
            focusPrompter();
        });
        toolbar.add(mirrorButton);
        toolbar.add(iconButton("fullscreen", I18n.t("toolbar.fullscreen"), this::toggleFullscreen));
        themeButton = iconButton(settings.darkTheme ? "sun" : "moon", I18n.t("toolbar.theme"), this::toggleTheme);
        toolbar.add(themeButton);
        toolbar.add(iconButton("settings", I18n.t("toolbar.settings"), this::openSettings));
    }

    private JComponent buildEditorPage() {
        editor.setFont(new Font(FlatInterFont.FAMILY, Font.PLAIN, MarkdownEditor.BASE_SIZE));
        editor.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, I18n.t("editor.placeholder"));
        editor.setCaretListener(this::syncFormatBar);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            void changed() {
                if (!modified) { modified = true; updateTitle(); }
                updateEditorStats();
                textSync.restart();
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        editor.setTransferHandler(new FileDropHandler(editor.getTransferHandler()));
        prompter.setTransferHandler(new FileDropHandler(null));

        // Header: title + file name on the left, stats on the right.
        JLabel title = new JLabel(I18n.t("editor.title"));
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
        body.add(buildFormatBar(), BorderLayout.NORTH);
        body.add(scroll);

        JLabel hint = new JLabel(I18n.t("editor.hint"),
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

    /** Formatting buttons above the editor. They insert Markdown, and light up for the styles under the caret. */
    private JComponent buildFormatBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(0, 0, 8, 0));
        for (int level = 1; level <= 3; level++) {
            int l = level;
            formatButton(bar, "h" + l, "H" + l, null, "font: bold", () -> editor.toggleBlock(Markdown.Block.HEADING, l));
        }
        bar.addSeparator();
        formatButton(bar, "bold", "B", null, "font: bold", () -> editor.toggleInline("**", "**"));
        formatButton(bar, "italic", "I", null, "font: italic", () -> editor.toggleInline("*", "*"));
        formatButton(bar, "strike", "<html><s>S</s></html>", null, null, () -> editor.toggleInline("~~", "~~"));
        bar.addSeparator();
        formatButton(bar, "bullet", null, "list", null, () -> editor.toggleBlock(Markdown.Block.BULLET, 0));
        formatButton(bar, "numbered", null, "list-numbered", null, () -> editor.toggleBlock(Markdown.Block.NUMBERED, 0));
        formatButton(bar, "quote", null, "quote", null, () -> editor.toggleBlock(Markdown.Block.QUOTE, 0));
        bar.addSeparator();
        formatButton(bar, "cue", "[ ]", null, "font: bold", () -> editor.toggleInline("[", "]"));
        return bar;
    }

    private void formatButton(JToolBar bar, String name, String text, String icon, String style, Runnable r) {
        JToggleButton b = new JToggleButton(text, icon == null ? null : Icons.get(icon, 18));
        b.setToolTipText(I18n.t("format." + name));
        b.setFocusable(false);
        b.putClientProperty(FlatClientProperties.STYLE, "margin: 3,7,3,7" + (style == null ? "" : "; " + style));
        // The editor decides what is selected; the button only reflects it.
        b.addActionListener(e -> { r.run(); syncFormatBar(); });
        b.setMaximumSize(b.getPreferredSize());   // HTML labels would otherwise stretch across the bar
        bar.add(b);
        formatButtons.put(name, b);
    }

    private void syncFormatBar() {
        Markdown.Line line = editor.lineAtCaret();
        int flags = editor.inlineStylesAtCaret();
        for (int level = 1; level <= 3; level++) {
            formatButtons.get("h" + level).setSelected(line.block() == Markdown.Block.HEADING && line.level() == level);
        }
        formatButtons.get("bold").setSelected((flags & Markdown.BOLD) != 0);
        formatButtons.get("italic").setSelected((flags & Markdown.ITALIC) != 0);
        formatButtons.get("strike").setSelected((flags & Markdown.STRIKE) != 0);
        formatButtons.get("bullet").setSelected(line.block() == Markdown.Block.BULLET);
        formatButtons.get("numbered").setSelected(line.block() == Markdown.Block.NUMBERED);
        formatButtons.get("quote").setSelected(line.block() == Markdown.Block.QUOTE);
        formatButtons.get("cue").setSelected((flags & Markdown.CUE) != 0);
    }

    private void buildStatusBar() {
        modeStatus = statusLabel("edit");
        elapsedStatus = statusLabel("clock");
        elapsedStatus.setToolTipText(I18n.t("status.elapsed.tip"));
        remainingStatus = statusLabel("timer");
        remainingStatus.setToolTipText(I18n.t("status.remaining.tip"));
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

        JMenu file = menu("menu.file");
        file.add(item(I18n.t("menu.file.new"), "new", KeyStroke.getKeyStroke(KeyEvent.VK_N, menu), this::newFile));
        file.add(item(I18n.t("menu.file.open"), "open", KeyStroke.getKeyStroke(KeyEvent.VK_O, menu), this::openFile));
        file.add(item(I18n.t("menu.file.save"), "save", KeyStroke.getKeyStroke(KeyEvent.VK_S, menu), this::save));
        file.add(item(I18n.t("menu.file.saveAs"), null, KeyStroke.getKeyStroke(KeyEvent.VK_S, menu | InputEvent.SHIFT_DOWN_MASK), this::saveAs));
        file.addSeparator();
        file.add(item(I18n.t("menu.file.paste"), "clipboard", null, this::pasteAndPrompt));
        file.addSeparator();
        file.add(item(I18n.t("menu.file.exit"), "exit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, menu), this::exit));
        bar.add(file);

        JMenu view = menu("menu.view");
        view.add(item(I18n.t("menu.view.toggleMode"), "prompter", KeyStroke.getKeyStroke(KeyEvent.VK_E, menu), this::toggleMode));
        view.add(item(I18n.t("menu.view.fullscreen"), "fullscreen", KeyStroke.getKeyStroke("F11"), this::toggleFullscreen));
        view.add(item(I18n.t("menu.view.mirror"), "mirror", null, () -> toggle(() -> settings.mirrorHorizontal = !settings.mirrorHorizontal)));
        view.add(item(I18n.t("menu.view.guide"), "guide", null, () -> toggle(() -> settings.showGuide = !settings.showGuide)));
        view.addSeparator();
        view.add(item(I18n.t("menu.view.theme"), "moon", null, this::toggleTheme));
        JMenu languages = new JMenu(I18n.t("menu.view.language"));
        languages.setIcon(Icons.get("text", 16));
        ButtonGroup languageGroup = new ButtonGroup();
        for (I18n.Language lang : I18n.Language.values()) {
            JRadioButtonMenuItem li = new JRadioButtonMenuItem(lang.toString(), lang == I18n.language());
            li.addActionListener(e -> changeLanguage(lang));
            languageGroup.add(li);
            languages.add(li);
            languageItems.put(lang, li);
        }
        view.add(languages);
        view.add(item(I18n.t("menu.view.settings"), "settings", KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, menu), this::openSettings));
        bar.add(view);

        JMenu play = menu("menu.playback");
        play.add(item(I18n.t("menu.playback.playPause"), "play", KeyStroke.getKeyStroke("F5"), this::playPause));
        play.add(item(I18n.t("menu.playback.restart"), "restart", null, () -> { showPrompter(); prompter.reset(); }));
        play.addSeparator();
        paceSpeedItem = new JRadioButtonMenuItem(I18n.t("menu.playback.paceSpeed"), Icons.get("speed", 16));
        paceSpeedItem.addActionListener(e -> setPace(Settings.Pace.SPEED));
        paceTimeItem = new JRadioButtonMenuItem(I18n.t("menu.playback.paceTime"), Icons.get("timer", 16));
        paceTimeItem.addActionListener(e -> setPace(Settings.Pace.TIME));
        ButtonGroup paceItems = new ButtonGroup();
        paceItems.add(paceSpeedItem);
        paceItems.add(paceTimeItem);
        play.add(paceSpeedItem);
        play.add(paceTimeItem);
        play.addSeparator();
        play.add(item(I18n.t("menu.playback.faster"), null, null, () -> prompter.changeSpeed(1)));
        play.add(item(I18n.t("menu.playback.slower"), null, null, () -> prompter.changeSpeed(-1)));
        bar.add(play);

        JMenu help = new JMenu(I18n.t("menu.help"));
        help.add(item(I18n.t("menu.help.shortcuts"), "keyboard", KeyStroke.getKeyStroke("F1"), this::showHelp));
        help.add(item(I18n.t("menu.help.about"), "info", null, this::showAbout));
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
        bind("T", () -> setPace(prompter.isTimed() ? Settings.Pace.SPEED : Settings.Pace.TIME));
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
        durationSpinner.commit();   // a time typed but not yet applied must count for this run
        if (!prompterShown) {
            showPrompter();
            prompter.play();
        } else {
            prompter.togglePlay();
            focusPrompter();
        }
    }

    /**
     * Toolbar buttons don't take focus, so after typing in the duration field the keyboard would stay
     * there and Space would type instead of pausing. Hand the keyboard back to the prompter.
     */
    private void focusPrompter() {
        if (prompterShown) prompter.requestFocusInWindow();
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

    private void setPace(Settings.Pace pace) {
        if (pace == Settings.Pace.TIME && settings.targetSeconds <= 0) {
            settings.targetSeconds = estimatedReadingSeconds();
        }
        settings.pace = pace;
        prompter.settingsChanged();
        focusPrompter();
    }

    /** Reading time at {@link #WORDS_PER_MINUTE}, rounded to 15 s; the starting point for time mode. */
    private int estimatedReadingSeconds() {
        double seconds = wordCount() * 60.0 / WORDS_PER_MINUTE;
        return Settings.clamp((int) Math.round(seconds / 15) * 15, Settings.MIN_TARGET, Settings.MAX_TARGET);
    }

    /**
     * Switches the UI language by rebuilding the window in place. The script, file, unsaved state and
     * view carry over; an untouched sample script is replaced by the sample in the new language.
     */
    private void changeLanguage(I18n.Language lang) {
        if (lang == I18n.language()) return;
        boolean untouchedSample = !modified && currentFile == null && editor.getText().equals(I18n.t("sample"));
        boolean settingsOpen = settingsDialog != null && settingsDialog.isVisible();
        if (fullscreen) toggleFullscreen();
        prompter.pause();
        if (settingsDialog != null) settingsDialog.dispose();

        settings.language = lang.code;
        I18n.setLanguage(lang);

        JPrompter next = new JPrompter(settings);
        if (!untouchedSample) {
            next.editor.setText(editor.getText());
            next.editor.setCaretPosition(0);
            next.editor.discardUndo();
            next.currentFile = currentFile;
            next.modified = modified;
        }
        next.setBounds(getBounds());
        next.setExtendedState(getExtendedState());
        if (prompterShown) next.showPrompter();
        next.updateTitle();
        dispose();
        next.setVisible(true);
        if (settingsOpen) next.openSettings();
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
        settingsDialog = new SettingsDialog(this, settings, prompter::settingsChanged, this::openSettings, this::changeLanguage);
        settingsDialog.setVisible(true);
    }

    private void showHelp() {
        String[] shortcuts = {"play", "speed", "pace", "nudge", "paragraph", "startEnd", "size", "mirror",
                "guide", "fullscreen", "mode", "scroll", "format", "headings", "lists"};
        JPanel grid = new JPanel(new GridBagLayout());
        for (int i = 0; i < shortcuts.length; i++) {
            JLabel key = new JLabel(I18n.t("shortcut." + shortcuts[i] + ".keys"));
            key.putClientProperty(FlatClientProperties.STYLE,
                    "font: bold; border: 3,9,3,9,$Component.borderColor,1,8; background: $Panel.background");
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = i; c.anchor = GridBagConstraints.EAST; c.insets = new Insets(3, 0, 3, 14);
            grid.add(key, c);
            c = new GridBagConstraints();
            c.gridx = 1; c.gridy = i; c.anchor = GridBagConstraints.WEST;
            grid.add(new JLabel(I18n.t("shortcut." + shortcuts[i])), c);
        }
        JOptionPane.showMessageDialog(this, grid, I18n.t("shortcuts.title"), JOptionPane.PLAIN_MESSAGE, null);
    }

    private void showAbout() {
        JLabel logo = new JLabel(Icons.logo(96));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel name = new JLabel("JPrompter");
        name.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1");
        name.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel version = new JLabel(I18n.t("about.version", VERSION));
        version.setForeground(UIManager.getColor("Label.disabledForeground"));
        version.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel desc = new JLabel(I18n.t("about.description"));
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
                error(I18n.t("error.browser", ex.getMessage()));
            }
        });
        JLabel license = new JLabel(I18n.t("about.license"));
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
        JOptionPane.showMessageDialog(this, p, I18n.t("about.title"), JOptionPane.PLAIN_MESSAGE, null);
    }

    // ---------------------------------------------------------------- files

    private void newFile() {
        if (!confirmDiscard()) return;
        showEditor();
        editor.setText("");
        currentFile = null;
        editor.discardUndo();
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
            editor.discardUndo();
            currentFile = f;
            modified = false;
            rememberDir(f);
            prompter.setText(text);
            prompter.reset();
            updateTitle();
        } catch (IOException e) {
            error(I18n.t("error.open", f.getName(), e.getMessage()));
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
        if (!f.getName().contains(".")) f = new File(f.getParentFile(), f.getName() + ".md");
        if (f.exists() && !f.equals(currentFile)
                && JOptionPane.showConfirmDialog(this, I18n.t("dialog.overwrite.message", f.getName()),
                I18n.t("dialog.overwrite.title"),
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
            error(I18n.t("error.save", f.getName(), e.getMessage()));
            return false;
        }
    }

    private void pasteAndPrompt() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) { error(I18n.t("error.clipboardEmpty")); return; }
            if (!confirmDiscard()) return;
            editor.setText((String) cb.getData(DataFlavor.stringFlavor));
            currentFile = null;
            modified = true;
            prompter.reset();
            updateTitle();
            showPrompter();
        } catch (Exception e) {
            error(I18n.t("error.clipboard", e.getMessage()));
        }
    }

    private JFileChooser chooser() {
        JFileChooser fc = new JFileChooser(settings.lastDirectory);
        fc.setFileFilter(new FileNameExtensionFilter(I18n.t("file.filter"), "md", "markdown", "txt", "text"));
        return fc;
    }

    private void rememberDir(File f) {
        if (f.getParentFile() != null) settings.lastDirectory = f.getParentFile().getAbsolutePath();
    }

    private boolean confirmDiscard() {
        if (!modified) return true;
        int r = JOptionPane.showConfirmDialog(this, I18n.t("dialog.unsaved.message"), I18n.t("dialog.unsaved.title"),
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
                    error(I18n.t("error.drop", e.getMessage()));
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
            playButton.setText(I18n.t(playing ? "play.pause" : "play.play"));
            playButton.setIcon(Icons.colored(playing ? "pause" : "play", 16, Color.WHITE));
            editButton.setSelected(!prompterShown);
            promptButton.setSelected(prompterShown);
            mirrorButton.setSelected(settings.mirrorHorizontal);
            boolean timed = prompter.isTimed();
            paceSpeedButton.setSelected(!timed);
            paceTimeButton.setSelected(timed);
            paceSpeedItem.setSelected(!timed);
            paceTimeItem.setSelected(timed);
            paceCards.show(paceControls, settings.pace.name());
            durationSpinner.setSeconds(prompter.targetSeconds());
            autoSpeedValue.setText(String.valueOf(prompter.effectiveSpeed()));
            speedSlider.setValue(settings.speed);
            sizeSlider.setValue(settings.fontSize);
            speedValue.setText(String.valueOf(settings.speed));
            sizeValue.setText(String.valueOf(settings.fontSize));

            modeStatus.setText(I18n.t(prompterShown ? (playing ? "status.prompting" : "status.paused") : "status.editing"));
            modeStatus.setIcon(Icons.muted(prompterShown ? (playing ? "play" : "pause") : "edit", 14));
            elapsedStatus.setText(PrompterPanel.fmt(prompter.elapsedSeconds())
                    + (timed ? " / " + PrompterPanel.fmt(prompter.targetSeconds()) : ""));
            remainingStatus.setText("-" + PrompterPanel.fmt(prompter.remainingSeconds()));
            progressBar.setValue((int) Math.round(prompter.progress() * 1000));
            percentStatus.setText(Math.round(prompter.progress() * 100) + "%");
        } finally {
            updatingControls = false;
        }
    }

    private int wordCount() {
        String t = Markdown.plain(editor.getText()).strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private void updateEditorStats() {
        int words = wordCount();
        wordsLabel.setText(I18n.t("editor.words", words));
        double minutes = (double) words / WORDS_PER_MINUTE;
        readTimeLabel.setText(minutes < 1 ? I18n.t("editor.readTime.short") : I18n.t("editor.readTime", Math.round(minutes)));
        readTimeLabel.setToolTipText(I18n.t("editor.readTime.tip", WORDS_PER_MINUTE));
    }

    private void updateTitle() {
        String name = currentFile == null ? I18n.t("window.untitled") : currentFile.getName();
        setTitle((modified ? "• " : "") + name + " — JPrompter");
        fileLabel.setText(currentFile == null ? I18n.t("editor.untitled") : currentFile.getAbsolutePath());
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
        b.addActionListener(e -> { r.run(); focusPrompter(); });
        return b;
    }

    private JToggleButton paceToggle(String text, String tip, Settings.Pace pace) {
        JToggleButton b = new JToggleButton(text);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.putClientProperty(FlatClientProperties.STYLE, "margin: 3,8,3,8");
        b.addActionListener(e -> setPace(pace));
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

    /** A top-level menu whose mnemonic is the first letter of its translated name. */
    private static JMenu menu(String key) {
        JMenu m = new JMenu(I18n.t(key));
        m.setMnemonic(m.getText().charAt(0));
        return m;
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
        I18n.setLanguage(I18n.Language.fromCode(settings.language));
        SwingUtilities.invokeLater(() -> {
            setupLookAndFeel(settings.darkTheme);
            JPrompter app = new JPrompter(settings);
            app.setVisible(true);
            if (args.length > 0) app.load(new File(args[0]));
        });
    }
}
