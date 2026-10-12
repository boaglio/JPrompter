package jprompter;

import javax.swing.JComponent;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * UI text lookup. Messages live in {@code jprompter/messages*.properties} (UTF-8);
 * keys with arguments use {@link MessageFormat} placeholders such as {@code {0}}.
 */
public final class I18n {

    public enum Language {
        ENGLISH("en", Locale.ENGLISH, "English"),
        PORTUGUESE_BR("pt_BR", Locale.of("pt", "BR"), "Português (Brasil)");

        public final String code;
        public final Locale locale;
        private final String nativeName;

        Language(String code, Locale locale, String nativeName) {
            this.code = code;
            this.locale = locale;
            this.nativeName = nativeName;
        }

        /** Shown in its own language, so people can always find theirs. */
        @Override public String toString() { return nativeName; }

        public static Language fromCode(String code) {
            for (Language l : values()) if (l.code.equals(code)) return l;
            return Locale.getDefault().getLanguage().equals("pt") ? PORTUGUESE_BR : ENGLISH;
        }
    }

    private static final String BUNDLE = "jprompter.messages";

    private static Language language = Language.ENGLISH;
    private static ResourceBundle bundle = load(language);

    private I18n() {}

    public static Language language() { return language; }

    /** Switches the language for our messages and for Swing's built-in dialogs (Yes/No, file chooser). */
    public static void setLanguage(Language lang) {
        language = lang;
        bundle = load(lang);
        Locale.setDefault(lang.locale);
        JComponent.setDefaultLocale(lang.locale);
    }

    public static String t(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    public static String t(String key, Object... args) {
        return new MessageFormat(t(key), language.locale).format(args);
    }

    private static ResourceBundle load(Language lang) {
        // No fallback to the system locale: choosing English must give English on a Portuguese system.
        return ResourceBundle.getBundle(BUNDLE, lang.locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }
}
