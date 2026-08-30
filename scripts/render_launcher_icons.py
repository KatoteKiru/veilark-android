"""Render Veilark legacy launcher icons from the canonical foreground artwork.

The geometry and alpha mask are preserved. Only the palette is mapped to the
understated launcher palette used by the adaptive icon resources.
"""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
FOREGROUND_SOURCE = ROOT / "design" / "veilark_logo_foreground_source.png"
FOREGROUND = RES / "drawable-xxxhdpi" / "veilark_logo_foreground_v2.png"

CANVAS = (0x11, 0x15, 0x1A, 0xFF)
INNER = (0x1D, 0x24, 0x2C, 0xFF)
UPPER = (0xD4, 0xDB, 0xE2)
LOWER = (0x7C, 0x8E, 0xA1)
CENTER = (0xB6, 0xC2, 0xCD)

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def recolor_foreground(image: Image.Image) -> Image.Image:
    source = image.convert("RGBA")
    output = Image.new("RGBA", source.size)
    source_pixels = source.load()
    output_pixels = output.load()
    for y in range(source.height):
        for x in range(source.width):
            red, green, blue, alpha = source_pixels[x, y]
            if alpha == 0:
                continue
            if green > 150 and blue > 150 and red < 100:
                target = CENTER
            elif blue > red * 1.35 and blue > green * 1.08:
                target = LOWER
            else:
                target = UPPER
            # Retain the original artwork's subtle lighting without retaining hue.
            luminance = (red * 299 + green * 587 + blue * 114) / 255_000
            adjustment = 0.88 + 0.18 * luminance
            output_pixels[x, y] = (
                min(255, round(target[0] * adjustment)),
                min(255, round(target[1] * adjustment)),
                min(255, round(target[2] * adjustment)),
                alpha,
            )
    return output


def render_legacy(foreground: Image.Image, size: int, round_icon: bool) -> Image.Image:
    scale = 4
    extent = size * scale
    canvas = Image.new("RGBA", (extent, extent), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    if round_icon:
        draw.ellipse((0, 0, extent - 1, extent - 1), fill=CANVAS)
    else:
        radius = round(extent * 0.22)
        draw.rounded_rectangle((0, 0, extent - 1, extent - 1), radius=radius, fill=CANVAS)
    inset = round(extent * 0.074)
    draw.ellipse((inset, inset, extent - inset, extent - inset), fill=INNER)

    art_extent = round(extent * (76 / 108))
    art = foreground.resize((art_extent, art_extent), Image.Resampling.LANCZOS)
    origin = (extent - art_extent) // 2
    canvas.alpha_composite(art, (origin, origin))
    return canvas.resize((size, size), Image.Resampling.LANCZOS)


def main() -> None:
    foreground = recolor_foreground(Image.open(FOREGROUND_SOURCE))
    foreground.save(FOREGROUND, format="PNG", optimize=True)
    for directory_name, size in DENSITIES.items():
        directory = RES / directory_name
        directory.mkdir(parents=True, exist_ok=True)
        render_legacy(foreground, size, round_icon=False).save(
            directory / "ic_launcher.webp",
            format="WEBP",
            lossless=True,
            method=6,
        )
        render_legacy(foreground, size, round_icon=True).save(
            directory / "ic_launcher_round.webp",
            format="WEBP",
            lossless=True,
            method=6,
        )


if __name__ == "__main__":
    main()
