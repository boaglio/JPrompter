package jprompter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Markdown subset JPrompter understands, parsed one line at a time so the editor and the prompter
 * read a script the same way: headings, bullet and numbered lists, quotes, horizontal rules,
 * **bold**, *italic*, ~~strikethrough~~, `code`, [links](url) and [cues].
 */
final class Markdown {

    enum Block { TEXT, HEADING, BULLET, NUMBERED, QUOTE, RULE }

    /** Per-character style flags. MARKER is Markdown syntax that is hidden when the text is read. */
    static final int BOLD = 1, ITALIC = 2, STRIKE = 4, CODE = 8, CUE = 16, LINK = 32, MARKER = 64;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+");
    private static final Pattern BULLET = Pattern.compile("^[ \\t]*[-*+][ \\t]+");
    private static final Pattern NUMBERED = Pattern.compile("^([ \\t]*)(\\d{1,9})([.)])[ \\t]+");
    private static final Pattern QUOTE = Pattern.compile("^>[ \\t]?");
    private static final Pattern RULE = Pattern.compile("^[ \\t]*([-*_])([ \\t]*\\1){2,}[ \\t]*$");
    private static final Pattern LINK_SPAN = Pattern.compile("\\[([^\\]\\n]+)]\\(([^)\\s]*)\\)");
    private static final Pattern CUE_SPAN = Pattern.compile("\\[[^\\]\\n]*]");

    private Markdown() {}

    /**
     * One parsed line. Characters before {@code prefixEnd} are the block marker ("## ", "- ", "> ");
     * {@code style[i]} holds the flags of character i.
     */
    record Line(String text, Block block, int level, int prefixEnd, int[] style) {

        boolean isMarker(int i) { return i < prefixEnd || (style[i] & MARKER) != 0; }

        /** The line as it is read aloud: markers removed, list bullets shown as "•". */
        String plain() {
            StringBuilder sb = new StringBuilder(text.length()).append(displayPrefix());
            for (int i = 0; i < text.length(); i++) if (!isMarker(i)) sb.append(text.charAt(i));
            return sb.toString();
        }

        /** What the block marker becomes when the text is read: a bullet, the item number, or nothing. */
        String displayPrefix() {
            return switch (block) {
                case BULLET -> "•  ";
                case NUMBERED -> text.substring(0, prefixEnd).strip() + " ";
                default -> "";
            };
        }

        /** The number of a numbered list item, or 0. */
        int number() {
            Matcher m = NUMBERED.matcher(text);
            return block == Block.NUMBERED && m.find() ? Integer.parseInt(m.group(2)) : 0;
        }

        /** The prefix that continues this list or quote on the next line, or null. */
        String nextPrefix() {
            return switch (block) {
                case BULLET, QUOTE -> text.substring(0, prefixEnd);
                case NUMBERED -> {
                    Matcher m = NUMBERED.matcher(text);
                    m.find();
                    yield m.group(1) + (Integer.parseInt(m.group(2)) + 1) + m.group(3) + " ";
                }
                default -> null;
            };
        }
    }

    static Line parse(String text) {
        int[] style = new int[text.length()];
        if (RULE.matcher(text).matches()) {
            java.util.Arrays.fill(style, MARKER);
            return new Line(text, Block.RULE, 0, text.length(), style);
        }
        Block block = Block.TEXT;
        int level = 0, prefixEnd = 0;
        Matcher m;
        if ((m = HEADING.matcher(text)).find()) {
            block = Block.HEADING;
            level = m.group(1).length();
        } else if ((m = BULLET.matcher(text)).find()) {
            block = Block.BULLET;
        } else if ((m = NUMBERED.matcher(text)).find()) {
            block = Block.NUMBERED;
        } else if ((m = QUOTE.matcher(text)).find()) {
            block = Block.QUOTE;
        } else {
            m = null;
        }
        if (m != null) prefixEnd = m.end();
        inline(text, prefixEnd, text.length(), 0, style);
        return new Line(text, block, level, prefixEnd, style);
    }

    /** The whole script as it is read aloud, for word counts. */
    static String plain(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) sb.append(parse(line).plain()).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- inline spans

    private static void inline(String s, int from, int to, int outer, int[] style) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < to && isEscapable(s.charAt(i + 1))) {
                style[i] = outer | MARKER;
                style[i + 1] = outer;
                i += 2;
                continue;
            }
            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i + 1 && j < to) {
                    style[i] = style[j] = outer | MARKER;
                    fill(style, i + 1, j, outer | CODE);
                    i = j + 1;
                    continue;
                }
            } else if (c == '[') {
                Matcher m = LINK_SPAN.matcher(s).region(i, to);
                if (m.lookingAt()) {
                    style[i] = outer | MARKER;
                    inline(s, i + 1, m.end(1), outer | LINK, style);
                    fill(style, m.end(1), m.end(), outer | MARKER);
                    i = m.end();
                    continue;
                }
                m = CUE_SPAN.matcher(s).region(i, to);
                if (m.lookingAt()) {
                    // Cue brackets stay visible: they are part of what the presenter sees.
                    style[i] = style[m.end() - 1] = outer | CUE;
                    inline(s, i + 1, m.end() - 1, outer | CUE, style);
                    i = m.end();
                    continue;
                }
            } else if (c == '*' || c == '_' || c == '~') {
                int run = run(s, i, to, c);
                int n = c == '~' ? 2 : Math.min(run, 2);
                int j = (c != '~' || run == 2) && canOpen(s, i + run - 1, to, c) ? findClose(s, i + n, to, c, n) : -1;
                if (j >= 0) {
                    int flag = c == '~' ? STRIKE : n == 2 ? BOLD : ITALIC;
                    fill(style, i, i + n, outer | MARKER);
                    fill(style, j, j + n, outer | MARKER);
                    inline(s, i + n, j, outer | flag, style);
                    i = j + n;
                } else {
                    fill(style, i, i + run, outer);
                    i += run;
                }
                continue;
            }
            style[i++] = outer;
        }
    }

    /** Where the closing delimiter of length n starts, or -1. Longer closing runs close with their last n characters. */
    private static int findClose(String s, int from, int to, char c, int n) {
        int k = from + 1;   // the content can't be empty
        while (k < to) {
            if (s.charAt(k) == '`') {   // emphasis never closes inside a code span
                int j = s.indexOf('`', k + 1);
                if (j > 0 && j < to) { k = j + 1; continue; }
            }
            if (s.charAt(k) != c) { k++; continue; }
            int r = run(s, k, to, c);
            boolean fits = n == 1 ? r % 2 == 1 : r >= n && (c != '~' || r == 2);
            if (fits && canClose(s, k, k + r, to, c)) return k + r - n;
            k += r;
        }
        return -1;
    }

    private static boolean canOpen(String s, int last, int to, char c) {
        if (last + 1 >= to || Character.isWhitespace(s.charAt(last + 1))) return false;
        int first = last;
        while (first > 0 && s.charAt(first - 1) == c) first--;
        // Underscores inside words (snake_case) are not emphasis.
        return c != '_' || first == 0 || !Character.isLetterOrDigit(s.charAt(first - 1));
    }

    private static boolean canClose(String s, int start, int end, int to, char c) {
        if (Character.isWhitespace(s.charAt(start - 1))) return false;
        return c != '_' || end >= to || !Character.isLetterOrDigit(s.charAt(end));
    }

    private static int run(String s, int i, int to, char c) {
        int j = i;
        while (j < to && s.charAt(j) == c) j++;
        return j - i;
    }

    private static boolean isEscapable(char c) {
        return "\\`*_~[]()#>-+.!".indexOf(c) >= 0;
    }

    private static void fill(int[] style, int from, int to, int value) {
        for (int i = from; i < to; i++) style[i] = value;
    }
}
