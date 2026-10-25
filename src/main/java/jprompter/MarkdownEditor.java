package jprompter;

import com.formdev.flatlaf.fonts.inter.FlatInterFont;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * A live-preview Markdown editor. The document stays plain Markdown (what gets saved), but it is shown
 * the way it reads: headings large, **bold** bold, list markers as bullets. The Markdown markers are
 * hidden, except on the lines being edited, where they show dimmed so they can be changed.
 */
final class MarkdownEditor extends JTextPane {

    static final int BASE_SIZE = 18;
    private static final float[] HEADING_SCALE = {1, 1.75f, 1.45f, 1.2f, 1.1f, 1, 1};
    private static final Color CUE = new Color(0xE0, 0x8A, 0x2E);
    private static final Color HIDDEN = new Color(0, 0, 0, 0);
    /** Character attribute: draw this list marker as a bullet. */
    private static final Object BULLET = "md-bullet";

    private final UndoManager undo = new UndoManager();
    private CompoundEdit group;
    private Runnable caretListener = () -> {};

    /** Lines whose markers are showing: the ones the caret or selection is on. */
    private Position activeStart, activeEnd;
    /** Text range changed since the last restyle. */
    private int dirtyStart = Integer.MAX_VALUE, dirtyEnd = -1;
    private boolean restyleQueued;

    MarkdownEditor() {
        setEditorKit(new Kit());
        setMargin(new Insets(18, 22, 18, 22));
        StyledDocument doc = getStyledDocument();
        doc.addUndoableEditListener(e -> {
            // Styling is derived from the text, so only text changes are worth undoing.
            if (e.getEdit() instanceof DocumentEvent de && de.getType() == DocumentEvent.EventType.CHANGE) return;
            if (group != null) group.addEdit(e.getEdit()); else undo.addEdit(e.getEdit());
        });
        doc.addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { dirty(e.getOffset(), e.getOffset() + e.getLength()); }
            @Override public void removeUpdate(DocumentEvent e) { dirty(e.getOffset(), e.getOffset()); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        addCaretListener(e -> { queueRestyle(); caretListener.run(); });
        installKeys();
    }

    /** Called when the caret moves, so the formatting bar can show the styles under it. */
    void setCaretListener(Runnable r) { this.caretListener = r; }

    @Override
    public void setText(String t) {
        super.setText(t);
        restyleAll();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Theme colors are baked into the character attributes; pick up the new ones.
        if (getDocument() != null) SwingUtilities.invokeLater(this::restyleAll);
    }

    void discardUndo() { undo.discardAllEdits(); }

    // ---------------------------------------------------------------- styling

    private void dirty(int from, int to) {
        dirtyStart = Math.min(dirtyStart, from);
        dirtyEnd = Math.max(dirtyEnd, to);
        queueRestyle();
    }

    /** Restyling can't happen inside a document event, so it runs right after it. */
    private void queueRestyle() {
        if (restyleQueued) return;
        restyleQueued = true;
        SwingUtilities.invokeLater(this::restyle);
    }

    private void restyle() {
        restyleQueued = false;
        Element root = getDocument().getDefaultRootElement();
        int oldFirst = activeStart == null ? -1 : root.getElementIndex(activeStart.getOffset());
        int oldLast = activeEnd == null ? -1 : root.getElementIndex(activeEnd.getOffset());
        int first = root.getElementIndex(getSelectionStart());
        int last = root.getElementIndex(getSelectionEnd());
        try {
            activeStart = getDocument().createPosition(root.getElement(first).getStartOffset());
            activeEnd = getDocument().createPosition(root.getElement(last).getStartOffset());
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        if (oldFirst >= 0) restyleLines(oldFirst, oldLast, first, last);
        if (dirtyEnd >= 0) {
            int len = getDocument().getLength();
            restyleLines(root.getElementIndex(Math.min(dirtyStart, len)), root.getElementIndex(Math.min(dirtyEnd, len)), first, last);
            dirtyStart = Integer.MAX_VALUE;
            dirtyEnd = -1;
        }
        restyleLines(first, last, first, last);
    }

    private void restyleAll() {
        dirtyStart = Integer.MAX_VALUE;
        dirtyEnd = -1;
        activeStart = activeEnd = null;
        Element root = getDocument().getDefaultRootElement();
        int first = root.getElementIndex(getSelectionStart()), last = root.getElementIndex(getSelectionEnd());
        restyleLines(0, root.getElementCount() - 1, first, last);
        queueRestyle();   // records the active lines
    }

    private void restyleLines(int from, int to, int activeFirst, int activeLast) {
        Element root = getDocument().getDefaultRootElement();
        for (int i = Math.max(0, from); i <= to && i < root.getElementCount(); i++) {
            restyleLine(root.getElement(i), i >= activeFirst && i <= activeLast);
        }
    }

    private void restyleLine(Element para, boolean active) {
        StyledDocument doc = getStyledDocument();
        int start = para.getStartOffset();
        int end = Math.min(para.getEndOffset(), doc.getLength() + 1);
        String text;
        try {
            text = doc.getText(start, Math.max(0, end - start - 1));
        } catch (BadLocationException e) {
            return;
        }
        Markdown.Line line = Markdown.parse(text);

        SimpleAttributeSet p = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(p, 0.15f);
        if (line.block() == Markdown.Block.HEADING) {
            StyleConstants.setSpaceAbove(p, line.level() <= 2 ? 12 : 6);
            StyleConstants.setSpaceBelow(p, 4);
        }
        if (line.block() == Markdown.Block.QUOTE) StyleConstants.setLeftIndent(p, active ? 0 : 18);
        doc.setParagraphAttributes(start, end - start, p, true);

        // The newline takes the line's base style, so the caret at the end of a heading is heading sized.
        doc.setCharacterAttributes(start, end - start, attributes(line, 0, false, active), true);
        int i = 0;
        while (i < text.length()) {
            int flags = line.style()[i];
            boolean marker = line.isMarker(i);
            boolean prefix = i < line.prefixEnd();
            int j = i + 1;
            while (j < text.length() && line.style()[j] == flags && line.isMarker(j) == marker
                    && (j < line.prefixEnd()) == prefix) j++;
            if (prefix && line.block() == Markdown.Block.BULLET && !active) {
                // Show the "-" as a bullet; the spaces around it stay as they are.
                for (int k = i; k < j; k++) {
                    if (!Character.isWhitespace(text.charAt(k))) {
                        SimpleAttributeSet a = attributes(line, flags, false, active);
                        a.addAttribute(BULLET, Boolean.TRUE);
                        doc.setCharacterAttributes(start + k, 1, a, true);
                    }
                }
            } else {
                // Numbered list markers stay visible: the number is part of what is read.
                boolean shown = !marker || (prefix && line.block() == Markdown.Block.NUMBERED);
                doc.setCharacterAttributes(start + i, j - i, attributes(line, flags, !shown, active), true);
            }
            i = j;
        }
    }

    private SimpleAttributeSet attributes(Markdown.Line line, int flags, boolean marker, boolean active) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        boolean heading = line.block() == Markdown.Block.HEADING;
        StyleConstants.setFontFamily(a, (flags & Markdown.CODE) != 0 ? Font.MONOSPACED : FlatInterFont.FAMILY);
        StyleConstants.setFontSize(a, Math.round(BASE_SIZE * (heading ? HEADING_SCALE[line.level()] : 1)));
        StyleConstants.setBold(a, heading || (flags & Markdown.BOLD) != 0);
        StyleConstants.setItalic(a, (flags & (Markdown.ITALIC | Markdown.CUE)) != 0 || line.block() == Markdown.Block.QUOTE);
        StyleConstants.setStrikeThrough(a, (flags & Markdown.STRIKE) != 0);
        StyleConstants.setUnderline(a, (flags & Markdown.LINK) != 0);

        // Styled text is black unless told otherwise; follow the theme instead.
        StyleConstants.setForeground(a, getForeground());
        Color muted = UIManager.getColor("Label.disabledForeground");
        if ((flags & Markdown.CUE) != 0) StyleConstants.setForeground(a, CUE);
        else if ((flags & Markdown.LINK) != 0) StyleConstants.setForeground(a, Color.decode(JPrompter.ACCENT));
        else if (line.block() == Markdown.Block.QUOTE || line.block() == Markdown.Block.RULE) StyleConstants.setForeground(a, muted);
        if ((flags & Markdown.CODE) != 0) {
            Color fg = getForeground();
            StyleConstants.setBackground(a, new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 28));
        }
        if (marker) {
            if (active || line.block() == Markdown.Block.RULE) {
                StyleConstants.setForeground(a, muted);
                StyleConstants.setStrikeThrough(a, false);
                StyleConstants.setUnderline(a, false);
            } else {
                // Hidden: the characters are still there (and saved), just drawn tiny and transparent.
                StyleConstants.setFontSize(a, 1);
                StyleConstants.setForeground(a, HIDDEN);
                StyleConstants.setBackground(a, HIDDEN);
            }
        }
        return a;
    }

    /** Text views that can draw a list marker as a bullet; the same view switches back when the line is edited. */
    private static final class Kit extends StyledEditorKit {
        @Override public ViewFactory getViewFactory() {
            ViewFactory base = super.getViewFactory();
            return e -> AbstractDocument.ContentElementName.equals(e.getName()) ? new MarkerView(e) : base.create(e);
        }
    }

    private static final class MarkerView extends LabelView {
        private static final float WIDTH = 16;

        MarkerView(Element e) { super(e); }

        private boolean bullet() { return getAttributes().getAttribute(BULLET) == Boolean.TRUE; }

        @Override public float getPreferredSpan(int axis) {
            return axis == X_AXIS && bullet() ? WIDTH : super.getPreferredSpan(axis);
        }

        @Override public void paint(Graphics g0, Shape a) {
            if (!bullet()) { super.paint(g0, a); return; }
            Rectangle r = a.getBounds();
            FontMetrics fm = getContainer().getFontMetrics(getFont());
            float d = fm.getAscent() * 0.3f;
            float cy = r.y + fm.getAscent() - fm.getAscent() * 0.36f;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getForeground());
            g.fill(new java.awt.geom.Ellipse2D.Float(r.x + (WIDTH - d) / 2, cy - d / 2, d, d));
            g.dispose();
        }

        @Override public Shape modelToView(int pos, Shape a, Position.Bias b) throws BadLocationException {
            if (!bullet()) return super.modelToView(pos, a, b);
            Rectangle r = a.getBounds();
            return new Rectangle(pos > getStartOffset() ? r.x + r.width : r.x, r.y, 0, r.height);
        }

        @Override public int viewToModel(float x, float y, Shape a, Position.Bias[] biasReturn) {
            if (!bullet()) return super.viewToModel(x, y, a, biasReturn);
            Rectangle r = a.getBounds();
            biasReturn[0] = Position.Bias.Forward;
            return x < r.x + r.width / 2f ? getStartOffset() : getEndOffset();
        }
    }

    // ---------------------------------------------------------------- formatting

    /** Styles at the caret, as {@link Markdown} flags, for the formatting bar. */
    int inlineStylesAtCaret() {
        Element para = getDocument().getDefaultRootElement().getElement(
                getDocument().getDefaultRootElement().getElementIndex(getCaretPosition()));
        Markdown.Line line = Markdown.parse(lineText(para));
        int i = getCaretPosition() - para.getStartOffset();
        int flags = 0;
        if (i < line.text().length()) flags |= line.style()[i];
        if (i > 0) flags |= line.style()[i - 1] & ~Markdown.MARKER;
        return flags & ~Markdown.MARKER;
    }

    Markdown.Line lineAtCaret() {
        Element root = getDocument().getDefaultRootElement();
        return Markdown.parse(lineText(root.getElement(root.getElementIndex(getCaretPosition()))));
    }

    /**
     * Wraps the selection (or the word at the caret) in {@code mark}, or unwraps it if it is already
     * wrapped. Works for "**", "*", "~~" and the cue brackets.
     */
    void toggleInline(String open, String close) {
        int s = getSelectionStart(), e = getSelectionEnd();
        if (s == e) {
            try {
                if (s > 0 && isWordChar(s - 1) || s < getDocument().getLength() && isWordChar(s)) {
                    s = Utilities.getWordStart(this, s > 0 && isWordChar(s - 1) ? s - 1 : s);
                    e = Utilities.getWordEnd(this, s);
                }
            } catch (BadLocationException ex) {
                return;
            }
        }
        int start = s, end = e;
        grouped(() -> {
            try {
                Document doc = getDocument();
                String text = doc.getText(0, doc.getLength());
                int o = open.length(), c = close.length();
                if (wrapped(text, start - o, open, true) && wrapped(text, end, close, false)) {
                    doc.remove(end, c);
                    doc.remove(start - o, o);
                    select(start - o, end - o);
                } else if (end - start >= o + c && wrapped(text, start, open, false) && wrapped(text, end - c, close, true)) {
                    doc.remove(end - c, c);
                    doc.remove(start, o);
                    select(start, end - o - c);
                } else {
                    doc.insertString(end, close, null);
                    doc.insertString(start, open, null);
                    select(start + o, end + o);
                }
            } catch (BadLocationException ex) {
                UIManager.getLookAndFeel().provideErrorFeedback(this);
            }
        });
        requestFocusInWindow();
    }

    /**
     * Whether {@code mark} sits at {@code at}. For "*" and "**", the whole run of asterisks counts,
     * so "***both***" reads as bold and italic but "**bold**" is not italic.
     */
    private static boolean wrapped(String text, int at, String mark, boolean runEndsHere) {
        if (at < 0 || at + mark.length() > text.length() || !text.startsWith(mark, at)) return false;
        if (!mark.equals("*") && !mark.equals("**")) return true;
        int a = runEndsHere ? at + mark.length() - 1 : at;   // the asterisk next to the text
        int from = a, to = a;
        while (from > 0 && text.charAt(from - 1) == '*') from--;
        while (to + 1 < text.length() && text.charAt(to + 1) == '*') to++;
        int run = to - from + 1;
        return mark.equals("*") ? run % 2 == 1 : run >= 2;
    }

    /** Turns the selected lines into the given block type, or back into plain text if they already are. */
    void toggleBlock(Markdown.Block block, int level) {
        Element root = getDocument().getDefaultRootElement();
        int first = root.getElementIndex(getSelectionStart());
        int last = root.getElementIndex(Math.max(getSelectionStart(), getSelectionEnd() - (getSelectionEnd() > getSelectionStart() ? 1 : 0)));
        boolean all = true;
        for (int i = first; i <= last; i++) {
            Markdown.Line line = Markdown.parse(lineText(root.getElement(i)));
            all &= line.block() == block && line.level() == level;
        }
        boolean remove = all;
        grouped(() -> {
            try {
                int number = 1;
                for (int i = first; i <= last; i++) {
                    Element para = root.getElement(i);
                    Markdown.Line line = Markdown.parse(lineText(para));
                    int prefixEnd = line.block() == Markdown.Block.RULE ? 0 : line.prefixEnd();
                    String prefix = remove ? "" : switch (block) {
                        case HEADING -> "#".repeat(level) + " ";
                        case BULLET -> "- ";
                        case NUMBERED -> (number++) + ". ";
                        case QUOTE -> "> ";
                        default -> "";
                    };
                    getDocument().remove(para.getStartOffset(), prefixEnd);
                    getDocument().insertString(para.getStartOffset(), prefix, null);
                }
            } catch (BadLocationException ex) {
                UIManager.getLookAndFeel().provideErrorFeedback(this);
            }
        });
        requestFocusInWindow();
    }

    /** Runs edits as one undo step. */
    private void grouped(Runnable edits) {
        group = new CompoundEdit();
        try {
            edits.run();
        } finally {
            CompoundEdit g = group;
            group = null;
            g.end();
            undo.addEdit(g);
        }
    }

    private String lineText(Element para) {
        try {
            int end = Math.min(para.getEndOffset() - 1, getDocument().getLength());
            return getDocument().getText(para.getStartOffset(), end - para.getStartOffset());
        } catch (BadLocationException e) {
            return "";
        }
    }

    private boolean isWordChar(int offset) {
        try {
            return Character.isLetterOrDigit(getDocument().getText(offset, 1).charAt(0));
        } catch (BadLocationException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- keys

    private void installKeys() {
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        int shift = InputEvent.SHIFT_DOWN_MASK;
        key(KeyEvent.VK_Z, menu, "undo", () -> { if (undo.canUndo()) undo.undo(); });
        key(KeyEvent.VK_Y, menu, "redo", () -> { if (undo.canRedo()) undo.redo(); });
        key(KeyEvent.VK_Z, menu | shift, "redo", null);
        key(KeyEvent.VK_B, menu, "md-bold", () -> toggleInline("**", "**"));
        key(KeyEvent.VK_I, menu, "md-italic", () -> toggleInline("*", "*"));
        key(KeyEvent.VK_X, menu | shift, "md-strike", () -> toggleInline("~~", "~~"));
        key(KeyEvent.VK_K, menu, "md-cue", () -> toggleInline("[", "]"));
        key(KeyEvent.VK_0, menu, "md-text", () -> toggleBlock(Markdown.Block.TEXT, 0));
        for (int level = 1; level <= 3; level++) {
            int l = level;
            key(KeyEvent.VK_0 + level, menu, "md-h" + level, () -> toggleBlock(Markdown.Block.HEADING, l));
        }
        key(KeyEvent.VK_8, menu | shift, "md-bullet", () -> toggleBlock(Markdown.Block.BULLET, 0));
        key(KeyEvent.VK_7, menu | shift, "md-numbered", () -> toggleBlock(Markdown.Block.NUMBERED, 0));
        key(KeyEvent.VK_9, menu | shift, "md-quote", () -> toggleBlock(Markdown.Block.QUOTE, 0));

        // Enter continues a list or quote; Enter on an empty item ends it.
        Action newline = getActionMap().get(DefaultEditorKit.insertBreakAction);
        getActionMap().put(DefaultEditorKit.insertBreakAction, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!continueBlock()) newline.actionPerformed(e);
            }
        });
    }

    private boolean continueBlock() {
        if (getSelectionStart() != getSelectionEnd()) return false;
        Element root = getDocument().getDefaultRootElement();
        Element para = root.getElement(root.getElementIndex(getCaretPosition()));
        Markdown.Line line = Markdown.parse(lineText(para));
        String next = line.nextPrefix();
        if (next == null || getCaretPosition() < para.getStartOffset() + line.prefixEnd()) return false;
        if (line.text().substring(line.prefixEnd()).isBlank()) {
            grouped(() -> {
                try {
                    getDocument().remove(para.getStartOffset(), line.text().length());
                } catch (BadLocationException ex) {
                    UIManager.getLookAndFeel().provideErrorFeedback(this);
                }
            });
        } else {
            grouped(() -> replaceSelection("\n" + next));
        }
        return true;
    }

    private void key(int code, int modifiers, String name, Runnable r) {
        getInputMap().put(KeyStroke.getKeyStroke(code, modifiers), name);
        if (r != null) {
            getActionMap().put(name, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { r.run(); }
            });
        }
    }
}
