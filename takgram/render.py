"""Card rendering: map thumbnail with the object's icon, and the caption text."""

from __future__ import annotations

import hashlib
import html
import io
import math
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw

from . import __version__
from .cot import CotEvent
from .cottypes import describe
from .icons import IconResolver, _font, emoji

W, H, TILE = 640, 400, 256
CAPTION_LIMIT = 1024
ATTRIBUTION = "© OpenStreetMap contributors"


def mgrs_of(lat: float, lon: float) -> str:
    """MGRS with 10 m precision, spaced for reading. Empty outside the MGRS latitude band."""
    if not -80 <= lat <= 84:
        return ""
    try:
        import mgrs

        s = mgrs.MGRS().toMGRS(lat, lon, MGRSPrecision=4)
    except Exception:
        return ""
    s = s.decode() if isinstance(s, bytes) else s
    return f"{s[:-10]} {s[-10:-8]} {s[-8:-4]} {s[-4:]}" if len(s) >= 13 else s


def _world_px(lat: float, lon: float, z: int) -> tuple[float, float]:
    n = TILE * 2**z
    x = (lon + 180.0) / 360.0 * n
    lat = max(min(lat, 85.0511), -85.0511)
    y = (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n
    return x, y


class TileSource:
    """Fetches and caches OpenStreetMap-style raster tiles. Returns None when offline."""

    def __init__(self, url: str, cache_dir: str | None, user_agent: str | None = None) -> None:
        self.url = url
        self.cache = Path(cache_dir) if cache_dir else None
        self.ua = user_agent or f"takgram/{__version__}"

    def get(self, z: int, x: int, y: int) -> Image.Image | None:
        if not self.url:
            return None
        n = 2**z
        if not 0 <= y < n:
            return None
        x %= n
        path = self.cache / str(z) / str(x) / f"{y}.png" if self.cache else None
        try:
            if path and path.is_file():
                return Image.open(path).convert("RGB")
            req = urllib.request.Request(self.url.format(z=z, x=x, y=y), headers={"User-Agent": self.ua})
            with urllib.request.urlopen(req, timeout=6) as r:
                data = r.read(2_000_000)
            img = Image.open(io.BytesIO(data)).convert("RGB")
            if path:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            return img
        except Exception:
            return None


def _fit_zoom(points: list[tuple[float, float]], default: int) -> int:
    if len(points) < 2:
        return default
    for z in range(17, 1, -1):
        xs, ys = zip(*(_world_px(a, b, z) for a, b in points))
        if max(xs) - min(xs) < W * 0.8 and max(ys) - min(ys) < H * 0.7:
            return z
    return 2


def render_card(ev: CotEvent, icons: IconResolver, tiles: TileSource | None, zoom: int = 14) -> bytes:
    """PNG bytes: map centred on the object, outline for shapes, icon and name on top."""
    pts = ev.points if ev.kind in ("shape", "route") else []
    if pts:
        clat = (min(p[0] for p in pts) + max(p[0] for p in pts)) / 2
        clon = (min(p[1] for p in pts) + max(p[1] for p in pts)) / 2
        z = _fit_zoom(pts, zoom)
    else:
        clat, clon, z = ev.lat, ev.lon, zoom
    cx, cy = _world_px(clat, clon, z)
    x0, y0 = cx - W / 2, cy - H / 2

    img = Image.new("RGB", (W, H), (226, 226, 220))
    d = ImageDraw.Draw(img, "RGBA")
    have_map = False
    for tx in range(int(x0 // TILE), int((x0 + W) // TILE) + 1):
        for ty in range(int(y0 // TILE), int((y0 + H) // TILE) + 1):
            t = tiles.get(z, tx, ty) if tiles else None
            px, py = int(tx * TILE - x0), int(ty * TILE - y0)
            if t:
                img.paste(t, (px, py))
                have_map = True
            else:
                d.rectangle((px, py, px + TILE, py + TILE), outline=(200, 200, 194))

    if pts:
        line = [(_world_px(a, b, z)[0] - x0, _world_px(a, b, z)[1] - y0) for a, b in pts]
        if ev.kind == "shape" and len(line) > 2:
            d.polygon(line, fill=(255, 60, 60, 50))
            line.append(line[0])
        d.line(line, fill=(0, 0, 0, 255), width=7, joint="curve")
        d.line(line, fill=(255, 170, 0, 255), width=3, joint="curve")

    ix, iy = _world_px(ev.lat, ev.lon, z)
    ix, iy = int(ix - x0), int(iy - y0)
    icon = icons.icon(ev).resize((72, 72), Image.LANCZOS)
    img.paste(icon, (ix - 36, iy - 36), icon)

    f = _font(20)
    label = ev.name[:28]
    box = d.textbbox((0, 0), label, font=f)
    tw = box[2] - box[0]
    lx, ly = max(4, min(W - tw - 12, ix - tw // 2 - 4)), min(H - 52, iy + 40)
    d.rounded_rectangle((lx, ly, lx + tw + 12, ly + 28), radius=6, fill=(255, 255, 255, 225))
    d.text((lx + 6 - box[0], ly + 4 - box[1]), label, font=f, fill=(0, 0, 0))

    small = _font(12)
    note = ATTRIBUTION if have_map else f"map tiles unavailable · {ev.lat:.5f}, {ev.lon:.5f}"
    nb = d.textbbox((0, 0), note, font=small)
    d.rectangle((W - nb[2] - 10, H - 18, W, H), fill=(255, 255, 255, 200))
    d.text((W - nb[2] - 5, H - 16), note, font=small, fill=(60, 60, 60))

    out = io.BytesIO()
    img.save(out, "PNG", optimize=True)
    return out.getvalue()


def _clock(t) -> str:
    return t.strftime("%Y-%m-%d %H:%MZ")


def caption(ev: CotEvent, source: str = "", status: str = "") -> str:
    """Telegram HTML caption. Everything a reader needs to find the object without TAK."""
    e = html.escape
    head = f"{emoji(ev)} <b>{e(ev.name)}</b> · {e(describe(ev.type))}"
    if status:
        head = f"{e(status)} · {head}"
    lines = [head, f"📍 <code>{ev.lat:.5f}, {ev.lon:.5f}</code>"]
    g = mgrs_of(ev.lat, ev.lon)
    if g:
        lines.append(f"🗺 MGRS <code>{g}</code>")
    bits = []
    if ev.hae is not None:
        bits.append(f"{ev.hae:.0f} m HAE")
    if ev.ce is not None:
        bits.append(f"±{ev.ce:.0f} m")
    if bits:
        lines.append("⛰ " + " · ".join(bits))
    if ev.course is not None and ev.speed is not None and ev.speed > 0.3:
        lines.append(f"➡ {ev.course:03.0f}° at {ev.speed * 3.6:.0f} km/h")
    if len(ev.points) > 1 and ev.kind in ("shape", "route"):
        lines.append(f"📐 {len(ev.points)} points")
    lines.append(f"🕒 {_clock(ev.time)} · stale {_clock(ev.stale)}")
    tail = [f"<code>#takobj uid={e(ev.uid)} type={e(ev.type)} lat={ev.lat:.6f} lon={ev.lon:.6f} "
            f"t={ev.time.strftime('%Y%m%dT%H%M%SZ')}</code>"]
    if source:
        tail.insert(0, f"<i>via {e(source)}</i>")
    used = sum(len(x) + 1 for x in lines + tail)
    if ev.remarks:
        room = CAPTION_LIMIT - used - 8
        if room > 20:
            r = ev.remarks if len(e(ev.remarks)) <= room else ev.remarks[: max(0, room - 40)] + "…"
            lines.append(f"📝 {e(r)}")
    return "\n".join(lines + tail)


def callback_key(uid: str) -> str:
    return hashlib.sha1(uid.encode()).hexdigest()[:20]


def keyboard(ev: CotEvent) -> dict:
    ll = f"{ev.lat:.6f},{ev.lon:.6f}"
    k = callback_key(ev.uid)
    return {"inline_keyboard": [
        [{"text": "Google Maps", "url": f"https://www.google.com/maps/search/?api=1&query={ll}"},
         {"text": "OpenStreetMap",
          "url": f"https://www.openstreetmap.org/?mlat={ev.lat:.6f}&mlon={ev.lon:.6f}#map=16/{ev.lat:.6f}/{ev.lon:.6f}"}],
        [{"text": "📍 Pin", "callback_data": f"pin:{k}"}, {"text": "Open in TAK", "callback_data": f"tak:{k}"}],
    ]}
