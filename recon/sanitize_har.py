#!/usr/bin/env python3
"""Strip auth material from a HAR before it gets read.

Removes Authorization / Cookie / Set-Cookie / WWW-Authenticate headers and the
cookies[] arrays. NTLM Type-3 messages in Authorization headers embed the
NTLMv2 response, which is crackable offline -- it must not linger in a file.

Usage: python3 sanitize_har.py raw.har clean.har
"""
import json, sys

STRIP = {"authorization", "proxy-authorization", "cookie", "set-cookie",
         "www-authenticate", "proxy-authenticate", "x-csrf-token"}


def clean_headers(headers):
    out = []
    for h in headers or []:
        if h.get("name", "").lower() in STRIP:
            out.append({"name": h.get("name"), "value": "<redacted>"})
        else:
            out.append(h)
    return out


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        har = json.load(f)

    entries = har.get("log", {}).get("entries", [])
    for e in entries:
        for side in ("request", "response"):
            part = e.get(side, {})
            part["headers"] = clean_headers(part.get("headers"))
            if part.get("cookies"):
                part["cookies"] = []

    with open(dst, "w", encoding="utf-8") as f:
        json.dump(har, f, indent=1)

    print(f"{len(entries)} entries sanitized -> {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
