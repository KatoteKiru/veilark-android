package app.veilark.macos;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Vector-backed launcher and menu-bar rendering. No bitmap scaling between icon sizes. */
public final class BrandIcon {
    private static String source;
    public static List<String> paths() {
        try {
            if (source == null) {
                try (var input = BrandIcon.class.getResourceAsStream("/brand/veilark-mark.svg")) {
                    if (input == null) throw new IOException("Missing bundled brand mark");
                    source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            var result = new ArrayList<String>();
            var matcher = Pattern.compile("\\bd=\"([^\"]+)\"").matcher(source);
            while (matcher.find()) result.add(matcher.group(1));
            if (result.size() != 2) throw new IOException("Invalid bundled brand mark");
            return List.copyOf(result);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static Path2D geometry(String data) {
        var tokens = new ArrayList<String>();
        var matcher = Pattern.compile("[MLHVZ]|-?\\d+(?:\\.\\d+)?").matcher(data);
        while (matcher.find()) tokens.add(matcher.group());
        var path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        double x = 0, y = 0;
        for (int i = 0; i < tokens.size();) {
            switch (tokens.get(i++)) {
                case "M": x = Double.parseDouble(tokens.get(i++)); y = Double.parseDouble(tokens.get(i++)); path.moveTo(x, y); break;
                case "L": x = Double.parseDouble(tokens.get(i++)); y = Double.parseDouble(tokens.get(i++)); path.lineTo(x, y); break;
                case "H": x = Double.parseDouble(tokens.get(i++)); path.lineTo(x, y); break;
                case "V": y = Double.parseDouble(tokens.get(i++)); path.lineTo(x, y); break;
                case "Z": path.closePath(); break;
                default: throw new IllegalArgumentException("Unsupported brand path command");
            }
        }
        return path;
    }
    public static BufferedImage render(int size, boolean dark, boolean tray) {
        if (size < 16 || size > 1024) throw new IllegalArgumentException("Invalid icon size");
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!tray) {
                graphics.setColor(dark ? new Color(0x242427) : new Color(0xF2F2F4));
                graphics.fillRoundRect(0, 0, size, size, size / 4, size / 4);
            }
            graphics.setColor(dark ? new Color(0xF5F5F7) : new Color(0x242427));
            var transform = new AffineTransform();
            transform.translate(size / 2.0, size / 2.0);
            double extent = tray ? 370.0 : 480.0;
            transform.scale(size / extent, size / extent);
            transform.translate(-216.0, -216.5);
            for (var path : paths()) graphics.fill(transform.createTransformedShape(geometry(path)));
        } finally { graphics.dispose(); }
        return image;
    }
    /** Source launcher used by Gradle on any build host. */
    public static void main(String[] args) throws IOException {
        source = Files.readString(Path.of(args[0]));
        var output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        String[] types = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};
        var entries = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(entries)) {
            for (int i = 0; i < sizes.length; i++) {
                var png = new ByteArrayOutputStream();
                ImageIO.write(render(sizes[i], true, false), "png", png);
                data.writeBytes(types[i]); data.writeInt(png.size() + 8); data.write(png.toByteArray());
            }
        }
        try (var data = new DataOutputStream(Files.newOutputStream(output))) {
            data.writeBytes("icns"); data.writeInt(entries.size() + 8); data.write(entries.toByteArray());
        }
        if (args.length > 2) ImageIO.write(render(512, true, false), "png", Path.of(args[2]).toFile());
    }
}
