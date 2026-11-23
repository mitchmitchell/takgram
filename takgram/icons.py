"""Icon resolution for CoT objects.

Order: custom iconset file, team-colored circle for TAK users, colored spot
marker, built-in glyph for non-atom kinds, MIL-STD-2525 style frame.

The 2525 frames here are drawn directly (frame shape and color by
affiliation, short function label inside). Full symbol rendering from
pre-built milsymbol sprites is planned; drop PNGs into the icons folder under
`2525/<cot-type>.png` and they are used first.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from .cot import CotEvent
from .cottypes import frame

SIZE = 96
FILL = {"f": (128, 224, 255), "h": (255, 128, 128), "n": (170, 255, 170), "u": (255, 255, 128)}
TEAM = {
    "white": (255, 255, 255), "yellow": (255, 255, 0), "orange": (255, 119, 0), "magenta": (255, 0, 255),
    "red": (255, 0, 0), "maroon": (127, 0, 0), "purple": (127, 0, 127), "dark blue": (0, 0, 127),
    "blue": (0, 0, 255), "cyan": (0, 255, 255), "teal": (0, 127, 127), "green": (0, 255, 0),
    "dark green": (0, 127, 0), "brown": (119, 119, 0),
}
ROLE = {"team lead": "TL", "hq": "HQ", "sniper": "S", "medic": "+", "forward observer": "FO",
        "rto": "R", "k9": "K9"}
LABEL = {
    "U-C-I": "INF", "U-C-A": "ARM", "U-C-F": "ART", "U-C-R": "RCN", "U-C-E": "ENG", "U-S-M": "MED",
    "U-U-S": "SIG", "U-H": "HQ", "E-V": "VEH", "E-S": "SNS", "I": "INST", "M-H": "HELO", "M-F": "FW",
    "M-F-Q": "UAS",
}
EMOJI = {"f": "🟦", "h": "🟥", "n": "🟩", "u": "🟨"}


def _font(px: int) -> ImageFont.ImageFont:
    for name in ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "arialbd.ttf"):
        try:
            return ImageFont.truetype(name, px)
        except OSError:
            continue
    return ImageFont.load_default()


def _text(d: ImageDraw.ImageDraw, text: str, px: int, fill=(0, 0, 0)) -> None:
    if not text:
        return
    f = _font(px)
    box = d.textbbox((0, 0), text, font=f)
    w, h = box[2] - box[0], box[3] - box[1]
    d.text(((SIZE - w) / 2 - box[0], (SIZE - h) / 2 - box[1]), text, font=f, fill=fill)


def _canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def _contrast(rgb: tuple[int, int, int]) -> tuple[int, int, int]:
    return (0, 0, 0) if (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000 > 140 else (255, 255, 255)


def team_icon(team: str, role: str) -> Image.Image:
    img, d = _canvas()
    rgb = TEAM.get(team.lower(), (0, 255, 255))
    d.ellipse((14, 14, SIZE - 14, SIZE - 14), fill=rgb, outline=(0, 0, 0), width=5)
    _text(d, ROLE.get(role.lower(), ""), 30, _contrast(rgb))
    return img


def spot_icon(argb: int | None) -> Image.Image:
    img, d = _canvas()
    rgb = ((argb >> 16) & 255, (argb >> 8) & 255, argb & 255) if argb is not None else (255, 255, 255)
    d.ellipse((22, 22, SIZE - 22, SIZE - 22), fill=rgb, outline=(0, 0, 0), width=5)
    return img


def frame_icon(cot_type: str) -> Image.Image:
    img, d = _canvas()
    fam = frame(cot_type)
    fill, line, w, m = FILL[fam], (0, 0, 0), 5, 8
    if fam == "f":
        d.rectangle((m, 22, SIZE - m, SIZE - 22), fill=fill, outline=line, width=w)
    elif fam == "h":
        c = SIZE / 2
        d.polygon([(c, m - 4), (SIZE - m + 4, c), (c, SIZE - m + 4), (m - 4, c)], fill=fill, outline=line, width=w)
    elif fam == "n":
        d.rectangle((16, 16, SIZE - 16, SIZE - 16), fill=fill, outline=line, width=w)
    else:
        r = 22
        for cx, cy in ((48, 26), (70, 48), (48, 70), (26, 48)):
            d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=line)
        for cx, cy in ((48, 26), (70, 48), (48, 70), (26, 48)):
            d.ellipse((cx - r + w, cy - r + w, cx + r - w, cy + r - w), fill=fill)
        d.rectangle((30, 30, 66, 66), fill=fill)
    rest = "-".join(cot_type.split("-")[3:])
    label = ""
    for n in range(len(rest), 0, -1):
        key = rest[:n].rstrip("-")
        if key in LABEL:
            label = LABEL[key]
            break
    _text(d, label, 20 if len(label) > 3 else 24)
    return img


def glyph_icon(kind: str) -> Image.Image:
    img, d = _canvas()
    if kind == "alert":
        d.polygon([(48, 8), (90, 84), (6, 84)], fill=(230, 30, 30), outline=(0, 0, 0), width=5)
        f = _font(48)
        d.text((48, 54), "!", font=f, fill=(255, 255, 255), anchor="mm")
    elif kind == "casevac":
        d.ellipse((10, 10, 86, 86), fill=(255, 255, 255), outline=(0, 0, 0), width=5)
        d.rectangle((40, 22, 56, 74), fill=(220, 30, 30))
        d.rectangle((22, 40, 74, 56), fill=(220, 30, 30))
    elif kind == "route":
        d.line([(14, 76), (40, 40), (60, 58), (82, 18)], fill=(0, 0, 0), width=14, joint="curve")
        d.line([(14, 76), (40, 40), (60, 58), (82, 18)], fill=(255, 170, 0), width=7, joint="curve")
    elif kind == "shape":
        d.polygon([(20, 26), (76, 18), (84, 66), (36, 80)], fill=(255, 255, 255, 110), outline=(0, 0, 0), width=6)
    else:
        d.polygon([(48, 88), (22, 40), (48, 8), (74, 40)], fill=(255, 200, 0), outline=(0, 0, 0), width=5)
    return img


class IconResolver:
    def __init__(self, icons_dir: str | None = None) -> None:
        self.dir = Path(icons_dir) if icons_dir else None

    def _file(self, rel: str) -> Image.Image | None:
        if not self.dir or not rel:
            return None
        base = self.dir.resolve()
        p = (base / rel).resolve()
        if base not in p.parents or not p.is_file():
            return None
        try:
            return Image.open(p).convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS)
        except OSError:
            return None

    def icon(self, ev: CotEvent) -> Image.Image:
        if ev.iconsetpath and not ev.iconsetpath.startswith("COT_MAPPING_"):
            img = self._file(ev.iconsetpath)
            if img:
                return img
        if ev.kind == "unit":
            return team_icon(ev.team, ev.role)
        if ev.type.startswith("b-m-p-s-m") or ev.iconsetpath.startswith("COT_MAPPING_SPOTMAP"):
            return spot_icon(ev.color_argb)
        if ev.kind in ("alert", "casevac", "route", "shape"):
            return glyph_icon(ev.kind)
        if ev.type.startswith("a-"):
            return self._file(f"2525/{ev.type}.png") or frame_icon(ev.type)
        return glyph_icon("point")


def emoji(ev: CotEvent) -> str:
    if ev.kind == "alert":
        return "🚨"
    if ev.kind == "casevac":
        return "🚑"
    if ev.kind == "route":
        return "〰️"
    if ev.kind == "shape":
        return "⬠"
    if ev.kind == "unit":
        return "🔵"
    if ev.type.startswith("a-"):
        return EMOJI[frame(ev.type)]
    return "📍"
