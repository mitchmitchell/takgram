"""TAK data packages (mission packages): zip files TAK clients import.

Two kinds are built here: a connection package that adds the bridge's
built-in endpoint as a server on the device, and an object package that
carries one CoT event so a user can import it by hand.
"""

from __future__ import annotations

import io
import uuid
import zipfile
from xml.sax.saxutils import escape, quoteattr

from .cot import CotEvent, canonical


def _manifest(name: str, contents: str, delete_after: bool) -> str:
    return (
        '<MissionPackageManifest version="2"><Configuration>'
        f'<Parameter name="uid" value="{uuid.uuid4()}"/>'
        f"<Parameter name=\"name\" value={quoteattr(name)}/>"
        f'<Parameter name="onReceiveDelete" value="{"true" if delete_after else "false"}"/>'
        f"</Configuration><Contents>{contents}</Contents></MissionPackageManifest>"
    )


def _zip(files: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for path, data in files.items():
            z.writestr(path, data)
    return buf.getvalue()


def connection_package(host: str, port: int, name: str = "TAKgram") -> bytes:
    """Data package that adds '<host>:<port>:tcp' as a streaming CoT server."""
    pref = (
        "<?xml version='1.0' encoding='ASCII' standalone='yes'?>\n<preferences>"
        '<preference version="1" name="cot_streams">'
        '<entry key="count" class="class java.lang.Integer">1</entry>'
        f'<entry key="description0" class="class java.lang.String">{escape(name)}</entry>'
        '<entry key="enabled0" class="class java.lang.Boolean">true</entry>'
        f'<entry key="connectString0" class="class java.lang.String">{escape(host)}:{port}:tcp</entry>'
        "</preference></preferences>"
    )
    manifest = _manifest(f"{name} connection", '<Content ignore="false" zipEntry="takgram.pref"/>', True)
    return _zip({"takgram.pref": pref.encode(), "MANIFEST/manifest.xml": manifest.encode()})


def object_package(ev: CotEvent) -> bytes:
    """Data package holding one CoT event, for manual import into a TAK client."""
    safe = "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in ev.uid)[:80]
    entry = f"{safe}/{safe}.cot"
    contents = (f"<Content ignore=\"false\" zipEntry={quoteattr(entry)}>"
                f"<Parameter name=\"uid\" value={quoteattr(ev.uid)}/></Content>")
    manifest = _manifest(ev.name, contents, False)
    xml = b"<?xml version='1.0' encoding='UTF-8' standalone='yes'?>" + canonical(ev.xml)
    return _zip({entry: xml, "MANIFEST/manifest.xml": manifest.encode()})
