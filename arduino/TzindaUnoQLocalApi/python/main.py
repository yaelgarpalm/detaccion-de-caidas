from __future__ import annotations

import json
import math
import os
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from arduino.app_utils import App


APP_ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = APP_ROOT / "data"
DB_PATH = DATA_DIR / "tzinda_local.sqlite3"
HOST = "0.0.0.0"
PORT = int(os.environ.get("PORT", "8081"))
MAX_BODY_BYTES = 32 * 1024
RETENTION_MS = 30 * 24 * 60 * 60 * 1000
MAX_ROWS = 100_000
PRUNE_EVERY = 500
db_lock = threading.RLock()
rows_since_prune = 0


def connect_db() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH, timeout=5)
    connection.row_factory = sqlite3.Row
    return connection


def initialize_db() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with db_lock, connect_db() as connection:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute(
            """CREATE TABLE IF NOT EXISTS readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                received_at_ms INTEGER NOT NULL,
                captured_at_ms INTEGER NOT NULL,
                source TEXT NOT NULL,
                event_type TEXT,
                heart_rate_bpm REAL,
                spo2_percent REAL,
                latitude REAL,
                longitude REAL,
                accel_x REAL,
                accel_y REAL,
                accel_z REAL,
                raw_json TEXT NOT NULL
            )"""
        )
        connection.execute(
            "CREATE INDEX IF NOT EXISTS readings_captured_idx ON readings(captured_at_ms)"
        )
        connection.execute(
            "CREATE INDEX IF NOT EXISTS readings_source_idx ON readings(source)"
        )


def find_value(data: Any, names: tuple[str, ...]) -> Any:
    if isinstance(data, dict):
        for name in names:
            if name in data:
                return data[name]
        for value in data.values():
            found = find_value(value, names)
            if found is not None:
                return found
    elif isinstance(data, list):
        for value in data:
            found = find_value(value, names)
            if found is not None:
                return found
    return None


def as_number(value: Any) -> float | None:
    if isinstance(value, bool) or value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def captured_timestamp(payload: dict[str, Any], now_ms: int) -> int:
    value = payload.get("captured_at_ms", payload.get("timestamp", payload.get("captured_at")))
    number = as_number(value)
    if number is not None:
        # Accept seconds or milliseconds, then store milliseconds.
        return int(number if number > 10_000_000_000 else number * 1000)
    return now_ms


def store_reading(document: dict[str, Any]) -> dict[str, Any]:
    global rows_since_prune
    payload = document.get("reading", document.get("data", document))
    if not isinstance(payload, dict) or not payload:
        raise ValueError("El JSON debe contener un objeto de lectura no vacío.")

    now_ms = int(time.time() * 1000)
    raw_json = json.dumps(document, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    values = {
        "received_at_ms": now_ms,
        "captured_at_ms": captured_timestamp(payload, now_ms),
        "source": str(payload.get("source", "arduino"))[:32] or "arduino",
        "event_type": str(payload.get("event_type", payload.get("type", "")))[:64] or None,
        "heart_rate_bpm": as_number(find_value(payload, ("heart_rate_bpm", "heartRate", "heart_rate", "bpm", "pulse"))),
        "spo2_percent": as_number(find_value(payload, ("spo2_percent", "spo2", "oxygen_saturation", "oxygenSaturation"))),
        "latitude": as_number(find_value(payload, ("latitude", "lat"))),
        "longitude": as_number(find_value(payload, ("longitude", "lon", "lng"))),
        "accel_x": as_number(find_value(payload, ("accel_x", "ax"))),
        "accel_y": as_number(find_value(payload, ("accel_y", "ay"))),
        "accel_z": as_number(find_value(payload, ("accel_z", "az"))),
        "raw_json": raw_json,
    }

    with db_lock, connect_db() as connection:
        cursor = connection.execute(
            """INSERT INTO readings (
                received_at_ms, captured_at_ms, source, event_type,
                heart_rate_bpm, spo2_percent, latitude, longitude,
                accel_x, accel_y, accel_z, raw_json
            ) VALUES (
                :received_at_ms, :captured_at_ms, :source, :event_type,
                :heart_rate_bpm, :spo2_percent, :latitude, :longitude,
                :accel_x, :accel_y, :accel_z, :raw_json
            )""",
            values,
        )
        reading_id = int(cursor.lastrowid)
        rows_since_prune += 1
        if rows_since_prune >= PRUNE_EVERY:
            rows_since_prune = 0
            connection.execute("DELETE FROM readings WHERE captured_at_ms < ?", (now_ms - RETENTION_MS,))
            connection.execute(
                "DELETE FROM readings WHERE id IN "
                "(SELECT id FROM readings ORDER BY captured_at_ms DESC LIMIT -1 OFFSET ?)",
                (MAX_ROWS,),
            )
    return {"id": reading_id, "received_at_ms": now_ms}


def latest_reading() -> dict[str, Any] | None:
    with db_lock, connect_db() as connection:
        row = connection.execute(
            "SELECT id, received_at_ms, captured_at_ms, raw_json FROM readings ORDER BY id DESC LIMIT 1"
        ).fetchone()
    if row is None:
        return None
    document = json.loads(row["raw_json"])
    return {
        "id": int(row["id"]),
        "received_at_ms": int(row["received_at_ms"]),
        "captured_at_ms": int(row["captured_at_ms"]),
        "reading": document.get("reading", document.get("data", document)),
    }


def database_stats() -> dict[str, Any]:
    with db_lock, connect_db() as connection:
        count = int(connection.execute("SELECT COUNT(*) FROM readings").fetchone()[0])
        latest = connection.execute("SELECT MAX(received_at_ms) FROM readings").fetchone()[0]
    return {"readings": count, "latest_received_at_ms": latest}


class ApiHandler(BaseHTTPRequestHandler):
    server_version = "TzindaLocalAPI/1.0"

    def log_message(self, format_string: str, *args: Any) -> None:
        print("[HTTP] " + format_string % args)

    def send_json(self, status: int, document: dict[str, Any]) -> None:
        body = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(200, {"ok": True, "service": "tzinda-local-sqlite", "time_ms": int(time.time() * 1000)})
            return
        if self.path == "/api/v1/stats":
            self.send_json(200, {"ok": True, **database_stats()})
            return
        if self.path == "/api/v1/sensor":
            reading = latest_reading()
            if reading is None:
                self.send_json(
                    503,
                    {
                        "error": "sensor_unavailable",
                        "message": "No hay lecturas en la base SQLite local. Envía una lectura a POST /api/v1/sensor.",
                    },
                )
            else:
                self.send_json(200, {"ok": True, **reading})
            return
        if self.path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(
                b"<!doctype html><html lang='es'><meta charset='utf-8'><title>Tzinda UNO Q</title>"
                b"<body><h1>Tzinda UNO Q - SQLite local</h1><p>API en linea. Consulta /health, "
                b"/api/v1/stats y /api/v1/sensor. Guarda lecturas con POST /api/v1/sensor.</p></body></html>"
            )
            return
        self.send_json(404, {"error": "not_found", "message": "Ruta no encontrada"})

    def do_POST(self) -> None:
        if self.path != "/api/v1/sensor":
            self.send_json(404, {"error": "not_found", "message": "Ruta no encontrada"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > MAX_BODY_BYTES:
                raise ValueError(f"El cuerpo debe tener entre 1 y {MAX_BODY_BYTES} bytes.")
            document = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(document, dict):
                raise ValueError("El cuerpo JSON debe ser un objeto.")
            saved = store_reading(document)
            self.send_json(201, {"ok": True, **saved})
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            self.send_json(400, {"error": "invalid_payload", "message": str(error)})
        except sqlite3.Error as error:
            print(f"[SQLite] {error}")
            self.send_json(500, {"error": "storage_error", "message": "No se pudo guardar la lectura."})


def start_http_server() -> None:
    initialize_db()
    server = ThreadingHTTPServer((HOST, PORT), ApiHandler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, name="tzinda-http", daemon=True).start()
    print(f"Tzinda Local SQLite API escuchando en http://0.0.0.0:{PORT}")
    print(f"Base local: {DB_PATH}")


def keep_app_alive() -> None:
    time.sleep(1)


start_http_server()
App.run(user_loop=keep_app_alive)
