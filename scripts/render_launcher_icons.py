"""Rasterize compatibility launcher icons from the shared monochrome SVG mark."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
FOREGROUND_SOURCE = ROOT / "design" / "veilark-mark.svg"
FOREGROUND = RES / "drawable-xxxhdpi" / "veilark_logo_foreground_v2.png"
CANVAS = (0x15, 0x16, 0x1B, 0xFF)
DENSITIES = {
    "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
}

def svg_contours(data: str) -> list[list[tuple[float, float]]]:
    tokens = re.findall(r"[MLHVZ]|-?\d+(?:\.\d+)?", data)
    contours = []
    current = []
    x = y = 0.0
    command = ""
    i = 0
    while i < len(tokens):
        if tokens[i] in "MLHVZ":
            command = tokens[i]
            i += 1
        if command == "Z":
            contours.append(current)
            current = []
            command = ""
            continue
        if command in ("M", "L"):
            x, y = float(tokens[i]), float(tokens[i + 1])
            i += 2
            if command == "M":
                command = "L"
        elif command == "H":
            x = float(tokens[i])
            i += 1
        elif command == "V":
            y = float(tokens[i])
            i += 1
        else:
            raise ValueError("Unsupported SVG path command")
        current.append((x, y))
    return contours

def render_foreground() -> Image.Image:
    extent = 864
    combined = Image.new("1", (extent, extent))
    for element in ET.parse(FOREGROUND_SOURCE).getroot():
        path_mask = Image.new("1", combined.size)
        for contour in svg_contours(element.attrib["d"]):
            contour_mask = Image.new("1", combined.size)
            ImageDraw.Draw(contour_mask).polygon(
                [(x * 2, y * 2) for x, y in contour], fill=1,
            )
            path_mask = ImageChops.logical_xor(path_mask, contour_mask)
        combined = ImageChops.logical_or(combined, path_mask)
    image = Image.new("RGBA", combined.size, (255, 255, 255, 0))
    image.putalpha(combined.convert("L"))
    return image

def render_legacy(foreground: Image.Image, size: int, round_icon: bool) -> Image.Image:
    extent = size * 4
    canvas = Image.new("RGBA", (extent, extent), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    if round_icon:
        draw.ellipse((0, 0, extent - 1, extent - 1), fill=CANVAS)
    else:
        draw.rounded_rectangle(
            (0, 0, extent - 1, extent - 1),
            radius=round(extent * 0.22), fill=CANVAS,
        )
    art_extent = round(extent * (76 / 108))
    art = foreground.resize((art_extent, art_extent), Image.Resampling.LANCZOS)
    origin = (extent - art_extent) // 2
    canvas.alpha_composite(art, (origin, origin))
    return canvas.resize((size, size), Image.Resampling.LANCZOS)

def main() -> None:
    foreground = render_foreground()
    foreground.save(FOREGROUND, format="PNG", optimize=True)
    for directory_name, size in DENSITIES.items():
        directory = RES / directory_name
        directory.mkdir(parents=True, exist_ok=True)
        for round_icon in (False, True):
            name = "ic_launcher_round.webp" if round_icon else "ic_launcher.webp"
            render_legacy(foreground, size, round_icon).save(
                directory / name, format="WEBP", lossless=True, method=6,
            )

if __name__ == "__main__":
    main()
