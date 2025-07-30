package dev.amsam0.voicechatdiscord;

public record Component(Color color, String text) {
    enum Color {
        WHITE,
        RED,
        YELLOW,
        GREEN,
    }

    public static Component white(String text) {
        return new Component(Color.WHITE, text);
    }

    public static Component red(String text) {
        return new Component(Color.RED, text);
    }

    public static Component yellow(String text) {
        return new Component(Color.YELLOW, text);
    }

    public static Component green(String text) {
        return new Component(Color.GREEN, text);
    }
}
