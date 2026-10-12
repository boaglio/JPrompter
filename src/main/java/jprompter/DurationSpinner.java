package jprompter;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultFormatterFactory;
import java.text.ParseException;
import java.util.function.IntConsumer;

/**
 * A spinner for durations, shown as m:ss (or h:mm:ss). Typing accepts "3:00", "1:30:00",
 * or a plain number of minutes such as "5". The arrows step by 15 seconds.
 * Typed values apply shortly after typing stops, or at once via {@link #commit()};
 * they do not wait for the field to lose focus.
 */
public class DurationSpinner extends JSpinner {

    private final JFormattedTextField field;
    private final Timer typingDelay = new Timer(500, e -> commit());

    public DurationSpinner(int seconds, IntConsumer onChange) {
        super(new SpinnerNumberModel(clamp(seconds), Settings.MIN_TARGET, Settings.MAX_TARGET, 15));
        field = ((DefaultEditor) getEditor()).getTextField();
        field.setFormatterFactory(new DefaultFormatterFactory(new Formatter()));
        field.setEditable(true);
        field.setColumns(5);
        field.setHorizontalAlignment(SwingConstants.CENTER);
        field.setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);
        typingDelay.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { typingDelay.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { typingDelay.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        addChangeListener(e -> onChange.accept(getSeconds()));
    }

    public JFormattedTextField getTextField() { return field; }

    /** Applies the typed text now if it is a valid duration; incomplete or invalid text is left alone. */
    public void commit() {
        typingDelay.stop();
        try {
            if (parse(field.getText()) != getSeconds()) field.commitEdit();
        } catch (ParseException ignored) {
            // Still typing (e.g. "4:") or out of range: keep the current value.
        }
    }

    public int getSeconds() { return (Integer) getValue(); }

    /** Updates the value without treating it as a user edit when it is unchanged. */
    public void setSeconds(int seconds) {
        int v = clamp(seconds);
        if (v != getSeconds()) setValue(v);
    }

    private static int clamp(int seconds) {
        return Settings.clamp(seconds, Settings.MIN_TARGET, Settings.MAX_TARGET);
    }

    static String format(int seconds) {
        return seconds >= 3600
                ? String.format("%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    static int parse(String text) throws ParseException {
        String t = text.strip();
        if (!t.matches("\\d{1,3}(:\\d{1,2}){0,2}")) throw new ParseException("Use m:ss, h:mm:ss or minutes", 0);
        String[] parts = t.split(":");
        int seconds = switch (parts.length) {
            case 1 -> Integer.parseInt(parts[0]) * 60;
            case 2 -> Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            default -> Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
        };
        if (seconds < Settings.MIN_TARGET || seconds > Settings.MAX_TARGET) {
            throw new ParseException("Out of range", 0);
        }
        return seconds;
    }

    private static final class Formatter extends JFormattedTextField.AbstractFormatter {
        @Override public Object stringToValue(String text) throws ParseException { return parse(text); }

        @Override public String valueToString(Object value) {
            return value instanceof Integer i ? format(i) : "";
        }
    }
}
