"""Tiny synchronous Bot API helper for the phase 0 spike scripts (standard library only)."""

import json
import urllib.error
import urllib.request
import uuid


def call(token: str, method: str, files: dict | None = None, **params):
    """Returns (ok, result_or_error_dict). Never raises on API errors."""
    url = f"https://api.telegram.org/bot{token}/{method}"
    if files:
        boundary = uuid.uuid4().hex
        body = b""
        for k, v in params.items():
            v = v if isinstance(v, str) else json.dumps(v)
            body += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
        for k, (name, data) in files.items():
            body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"; filename="{name}"\r\n'
                     f"Content-Type: application/octet-stream\r\n\r\n").encode() + data + b"\r\n"
        body += f"--{boundary}--\r\n".encode()
        req = urllib.request.Request(url, body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    else:
        req = urllib.request.Request(url, json.dumps(params).encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=70) as r:
            out = json.load(r)
    except urllib.error.HTTPError as e:
        out = json.load(e)
    return out.get("ok", False), out.get("result") if out.get("ok") else out
