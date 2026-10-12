package jprompter;

import java.awt.Color;
import java.awt.Font;
import java.util.prefs.Preferences;

/** All user-adjustable prompter settings, persisted with java.util.prefs. */
public class Settings {

    public enum Align {
        LEFT, CENTER, RIGHT;

        @Override public String toString() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    public String fontFamily = "Inter"; // bundled via flatlaf-fonts-inter
    public int fontSize = 56;
    public boolean bold = true;
    public float lineSpacing = 1.3f;
    public int marginPercent = 10;
    public Align align = Align.LEFT;

    public Color textColor = Color.WHITE;
    public Color backgroundColor = Color.BLACK;
    public Color cueColor = new Color(0xFFD54F);
    public Color guideColor = new Color(0xE53935);

    public boolean mirrorHorizontal = false;
    public boolean mirrorVertical = false;

    public boolean showGuide = true;
    public float guidePosition = 0.33f;
    public boolean fadeEdges = true;
    public boolean showHud = true;

    public int countdownSeconds = 3;
    public int speed = 20;

    public static final int MIN_SPEED = 1, MAX_SPEED = 100;
    public static final int MIN_FONT = 16, MAX_FONT = 200;

    public boolean darkTheme = true;
    public String lastDirectory;

    private static final Preferences PREFS = Preferences.userNodeForPackage(Settings.class);

    public Font font() {
        return new Font(fontFamily, bold ? Font.BOLD : Font.PLAIN, fontSize);
    }

    public static Settings load() {
        Settings s = new Settings();
        try {
            s.fontFamily = PREFS.get("fontFamily", s.fontFamily);
            s.fontSize = clamp(PREFS.getInt("fontSize", s.fontSize), MIN_FONT, MAX_FONT);
            s.bold = PREFS.getBoolean("bold", s.bold);
            s.lineSpacing = PREFS.getFloat("lineSpacing", s.lineSpacing);
            s.marginPercent = clamp(PREFS.getInt("marginPercent", s.marginPercent), 0, 40);
            s.align = Align.valueOf(PREFS.get("align", s.align.name()));
            s.textColor = new Color(PREFS.getInt("textColor", s.textColor.getRGB()));
            s.backgroundColor = new Color(PREFS.getInt("backgroundColor", s.backgroundColor.getRGB()));
            s.cueColor = new Color(PREFS.getInt("cueColor", s.cueColor.getRGB()));
            s.guideColor = new Color(PREFS.getInt("guideColor", s.guideColor.getRGB()));
            s.mirrorHorizontal = PREFS.getBoolean("mirrorHorizontal", s.mirrorHorizontal);
            s.mirrorVertical = PREFS.getBoolean("mirrorVertical", s.mirrorVertical);
            s.showGuide = PREFS.getBoolean("showGuide", s.showGuide);
            s.guidePosition = PREFS.getFloat("guidePosition", s.guidePosition);
            s.fadeEdges = PREFS.getBoolean("fadeEdges", s.fadeEdges);
            s.showHud = PREFS.getBoolean("showHud", s.showHud);
            s.countdownSeconds = clamp(PREFS.getInt("countdownSeconds", s.countdownSeconds), 0, 10);
            s.speed = clamp(PREFS.getInt("speed", s.speed), MIN_SPEED, MAX_SPEED);
            s.darkTheme = PREFS.getBoolean("darkTheme", s.darkTheme);
            s.lastDirectory = PREFS.get("lastDirectory", null);
        } catch (RuntimeException e) {
            // Corrupt or unreadable preferences: fall back to defaults.
            return new Settings();
        }
        return s;
    }

    public void save() {
        try {
            PREFS.put("fontFamily", fontFamily);
            PREFS.putInt("fontSize", fontSize);
            PREFS.putBoolean("bold", bold);
            PREFS.putFloat("lineSpacing", lineSpacing);
            PREFS.putInt("marginPercent", marginPercent);
            PREFS.put("align", align.name());
            PREFS.putInt("textColor", textColor.getRGB());
            PREFS.putInt("backgroundColor", backgroundColor.getRGB());
            PREFS.putInt("cueColor", cueColor.getRGB());
            PREFS.putInt("guideColor", guideColor.getRGB());
            PREFS.putBoolean("mirrorHorizontal", mirrorHorizontal);
            PREFS.putBoolean("mirrorVertical", mirrorVertical);
            PREFS.putBoolean("showGuide", showGuide);
            PREFS.putFloat("guidePosition", guidePosition);
            PREFS.putBoolean("fadeEdges", fadeEdges);
            PREFS.putBoolean("showHud", showHud);
            PREFS.putInt("countdownSeconds", countdownSeconds);
            PREFS.putInt("speed", speed);
            PREFS.putBoolean("darkTheme", darkTheme);
            if (lastDirectory != null) PREFS.put("lastDirectory", lastDirectory);
            PREFS.flush();
        } catch (Exception e) {
            System.err.println("Could not save settings: " + e.getMessage());
        }
    }

    /** Restores the prompter's look and behavior to defaults, keeping the app theme and last folder. */
    public void resetPrompterDefaults() {
        Settings d = new Settings();
        fontFamily = d.fontFamily; fontSize = d.fontSize; bold = d.bold; lineSpacing = d.lineSpacing;
        marginPercent = d.marginPercent; align = d.align;
        textColor = d.textColor; backgroundColor = d.backgroundColor; cueColor = d.cueColor; guideColor = d.guideColor;
        mirrorHorizontal = d.mirrorHorizontal; mirrorVertical = d.mirrorVertical;
        showGuide = d.showGuide; guidePosition = d.guidePosition; fadeEdges = d.fadeEdges; showHud = d.showHud;
        countdownSeconds = d.countdownSeconds; speed = d.speed;
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
