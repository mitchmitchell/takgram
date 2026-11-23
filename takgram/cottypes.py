"""Plain-language descriptions of CoT type codes."""

from __future__ import annotations

AFFILIATION = {
    "f": "Friendly", "a": "Assumed friendly", "h": "Hostile", "s": "Suspect",
    "j": "Joker", "k": "Faker", "n": "Neutral", "u": "Unknown", "p": "Pending", "o": "Unspecified",
}
# Frame family used for drawing: friend, hostile, neutral, unknown.
FRAME = {"f": "f", "a": "f", "h": "h", "s": "h", "j": "h", "k": "h", "n": "n", "u": "u", "p": "u", "o": "u"}

DIMENSION = {
    "P": "space", "A": "air", "G": "ground", "S": "sea surface", "U": "subsurface", "F": "special operations",
}

# Longest match wins. Keys are the part of the type after the dimension letter.
FUNCTION = {
    "G": {
        "U": "unit", "U-C": "combat unit", "U-C-I": "infantry", "U-C-A": "armor", "U-C-F": "artillery",
        "U-C-R": "reconnaissance", "U-C-E": "engineer", "U-C-D": "air defense", "U-C-V": "aviation",
        "U-U": "combat support", "U-U-S": "signal", "U-U-M": "military intelligence",
        "U-U-L": "law enforcement", "U-S": "service support", "U-S-M": "medical", "U-S-S": "supply",
        "U-S-T": "transport", "U-H": "headquarters",
        "E": "equipment", "E-V": "vehicle", "E-V-A": "armored vehicle", "E-V-C": "civilian vehicle",
        "E-W": "weapon", "E-S": "sensor",
        "I": "installation", "I-U-T": "telecommunications site", "I-X-H": "hospital",
    },
    "A": {
        "M": "military aircraft", "M-F": "fixed-wing aircraft", "M-H": "helicopter", "M-F-Q": "drone",
        "M-H-Q": "rotary drone", "C": "civil aircraft", "C-F": "civil fixed-wing", "C-H": "civil helicopter",
        "W": "weapon",
    },
    "S": {"C": "combatant vessel", "N": "noncombatant vessel", "X": "non-military vessel",
          "X-M": "merchant vessel", "X-F": "fishing vessel", "X-R": "leisure craft"},
    "U": {"S": "submarine", "W": "underwater weapon"},
}

BITS = {
    "b-m-p-s-m": "Spot marker",
    "b-m-p-w": "Waypoint",
    "b-m-p-w-GOTO": "Go-to waypoint",
    "b-m-p-c": "Control point",
    "b-m-p-s-p-i": "Sensor point of interest",
    "b-m-p-s-p-loc": "Point of interest",
    "b-m-p-s-p-op": "Observation post",
    "b-m-r": "Route",
    "b-t-f": "Chat message",
    "b-r-f-h-c": "CASEVAC request",
    "b-a-o-tbl": "Emergency: 911 alert",
    "b-a-o-pan": "Emergency: ring the bell",
    "b-a-o-opn": "Emergency: troops in contact",
    "b-a-g": "Emergency: geofence breached",
    "b-a-o-can": "Emergency cancelled",
    "b-i-x-i": "Image",
    "u-d-f": "Freehand shape",
    "u-d-r": "Rectangle",
    "u-d-c-c": "Circle",
    "u-d-c-e": "Ellipse",
    "u-d-p": "Polygon",
    "u-rb-a": "Range and bearing line",
    "t-x-d-d": "Delete",
}


def affiliation(cot_type: str) -> str:
    parts = cot_type.split("-")
    return parts[1] if len(parts) > 1 and parts[0] == "a" else ""


def frame(cot_type: str) -> str:
    return FRAME.get(affiliation(cot_type), "u")


def describe(cot_type: str) -> str:
    """'a-h-G-U-C-A' -> 'Hostile ground armor'. Unknown codes fall back gracefully."""
    parts = cot_type.split("-")
    if parts[0] != "a":
        best = ""
        for k in BITS:
            if (cot_type == k or cot_type.startswith(k + "-")) and len(k) > len(best):
                best = k
        return BITS[best] if best else f"Object ({cot_type})"
    aff = AFFILIATION.get(parts[1] if len(parts) > 1 else "", "Unknown")
    dim_code = parts[2] if len(parts) > 2 else ""
    dim = DIMENSION.get(dim_code, "")
    rest = parts[3:]
    func = ""
    table = FUNCTION.get(dim_code, {})
    for n in range(len(rest), 0, -1):
        key = "-".join(rest[:n])
        if key in table:
            func = table[key]
            break
    words = [aff]
    if dim:
        words.append(dim)
    words.append(func or ("object" if not dim else "track"))
    return " ".join(words)
