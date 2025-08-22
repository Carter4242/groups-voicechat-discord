package dev.amsam0.voicechatdiscord;

public class Component {
    public enum Color {
        WHITE,
        RED,
        YELLOW,
        GREEN,
        GOLD,
        BLUE,
        GRAY,
        AQUA,
    }

    private final Color color;
    private final String text;
    private final String clickUrl;
    private final String hoverText;
    private final Component next;

    public Component(Color color, String text) {
        this(color, text, null, null, null);
    }

    public Component(Color color, String text, String clickUrl, String hoverText, Component next) {
        this.color = color;
        this.text = text;
        this.clickUrl = clickUrl;
        this.hoverText = hoverText;
        this.next = next;
    }

    public Color color() { return color; }
    public String text() { return text; }
    public String clickUrl() { return clickUrl; }
    public String hoverText() { return hoverText; }
    public Component next() { return next; }

    public Component withClickUrl(String url) {
        return new Component(color, text, url, hoverText, next);
    }

    public Component withHoverText(String hover) {
        return new Component(color, text, clickUrl, hover, next);
    }

    public Component append(Component other) {
        if (next == null) {
            return new Component(color, text, clickUrl, hoverText, other);
        } else {
            return new Component(color, text, clickUrl, hoverText, next.append(other));
        }
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

    public static Component gold(String text) {
        return new Component(Color.GOLD, text);
    }

    public static Component blue(String text) {
        return new Component(Color.BLUE, text);
    }

    public static Component gray(String text) {
        return new Component(Color.GRAY, text);
    }

    public static Component aqua(String text) {
        return new Component(Color.AQUA, text);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Component current = this;
        while (current != null) {
            sb.append(current.text());
            current = current.next();
        }
        return sb.toString();
    }
}
