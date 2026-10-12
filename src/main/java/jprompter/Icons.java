package jprompter;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.extras.FlatSVGUtils;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Image;
import java.util.List;
import java.util.function.Supplier;

/** Loads the SVG icons from resources and tints them to match the current theme. */
public final class Icons {

    private static final String DIR = "jprompter/icons/";

    private Icons() {}

    /** A line icon drawn in the theme's normal text color. */
    public static FlatSVGIcon get(String name, int size) {
        return tinted(name, size, () -> UIManager.getColor("Label.foreground"));
    }

    /** A line icon drawn in the theme's secondary (muted) text color. */
    public static FlatSVGIcon muted(String name, int size) {
        return tinted(name, size, () -> UIManager.getColor("Label.disabledForeground"));
    }

    /** A line icon in a fixed color, e.g. white on the accent-colored play button. */
    public static FlatSVGIcon colored(String name, int size, Color color) {
        return tinted(name, size, () -> color);
    }

    /** The full-color app logo. */
    public static FlatSVGIcon logo(int size) {
        return new FlatSVGIcon(DIR + "logo.svg", size, size);
    }

    public static List<Image> windowIcons() {
        return FlatSVGUtils.createWindowIconImages("/" + DIR + "logo.svg");
    }

    private static FlatSVGIcon tinted(String name, int size, Supplier<Color> color) {
        FlatSVGIcon icon = new FlatSVGIcon(DIR + name + ".svg", size, size);
        // The mapper runs at paint time, so icons follow live theme switches.
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> {
            Color target = color.get();
            return target == null ? c : new Color(target.getRed(), target.getGreen(), target.getBlue(), c.getAlpha());
        }));
        return icon;
    }
}
