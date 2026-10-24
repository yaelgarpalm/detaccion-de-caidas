"""HTTP client for the local BLE Brick container."""

from __future__ import annotations

import json
import time
from typing import Any
from urllib.error import URLError
from urllib.request import Request, urlopen


_URL = "http://tzinda-ble:8765/api/v1/reading"
_TIMEOUT_SECONDS = 1.5
_last_error_log = 0.0


def publish(sample: dict[str, Any]) -> bool:
    """Forward one sensor sample to the Brick over the app's private network."""
    global _last_error_log
    body = json.dumps(sample, separators=(",", ":"), allow_nan=False).encode("utf-8")
    request = Request(
        _URL,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=_TIMEOUT_SECONDS) as response:
            return 200 <= response.status < 300
    except (OSError, URLError, ValueError) as error:
        now = time.monotonic()
        if now - _last_error_log >= 30:
            print(f"[BLE] No se pudieron enviar lecturas al Brick: {error}")
            _last_error_log = now
        return False
