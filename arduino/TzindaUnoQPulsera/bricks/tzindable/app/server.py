"""Bridge HTTP readings into a BlueZ GATT peripheral on the UNO Q host."""

import asyncio
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from dbus_next import BusType, Variant
from dbus_next.aio import MessageBus
from dbus_next.constants import PropertyAccess
from dbus_next.service import ServiceInterface, dbus_property, method


SERVICE_UUID = "6b9f0001-6e2a-4f72-a11d-545a696e6461"
PULSE_UUID = "6b9f0002-6e2a-4f72-a11d-545a696e6461"
SPO2_UUID = "6b9f0003-6e2a-4f72-a11d-545a696e6461"
FALLS_UUID = "6b9f0004-6e2a-4f72-a11d-545a696e6461"
DEVICE_NAME = "Tzinda"
APP_PATH = "/com/tzinda/pulsera"
SERVICE_PATH = f"{APP_PATH}/service0"
CHARACTERISTIC_PATHS = {
    "bpm": f"{SERVICE_PATH}/char0",
    "spo2": f"{SERVICE_PATH}/char1",
    "caidas": f"{SERVICE_PATH}/char2",
}
BLUEZ = "org.bluez"
OBJECT_MANAGER = "org.freedesktop.DBus.ObjectManager"
GATT_MANAGER = "org.bluez.GattManager1"
ADVERTISEMENT_MANAGER = "org.bluez.LEAdvertisingManager1"
GATT_SERVICE = "org.bluez.GattService1"
GATT_CHARACTERISTIC = "org.bluez.GattCharacteristic1"
ADVERTISEMENT = "org.bluez.LEAdvertisement1"
PROPERTIES = "org.freedesktop.DBus.Properties"


def bounded_uint(value: Any, bits: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError, OverflowError):
        return 0
    return max(0, min(parsed, (1 << bits) - 1))


class Snapshot:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.values = {"bpm": 0, "spo2": 0, "caidas": 0}
        self.updated_at = 0.0
        self.loop: asyncio.AbstractEventLoop | None = None
        self.characteristics: dict[str, "SensorCharacteristic"] = {}

    def update(self, sample: dict[str, Any]) -> bool:
        with self.lock:
            self.values = {
                "bpm": bounded_uint(sample.get("bpm", sample.get("heart_rate_bpm", 0)), 16),
                "spo2": bounded_uint(sample.get("spo2", sample.get("spo2_percent", 0)), 8),
                "caidas": bounded_uint(sample.get("caidas", sample.get("falls", 0)), 32),
            }
            self.updated_at = time.time()
            values = dict(self.values)
            loop = self.loop
        if loop is None or loop.is_closed():
            return False
        try:
            loop.call_soon_threadsafe(self._apply, values)
            return True
        except RuntimeError:
            # The BlueZ connection may drop between checking the loop and
            # scheduling the update. Keep the latest reading for a restart.
            with self.lock:
                if self.loop is loop:
                    self.loop = None
            return False

    def set_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        with self.lock:
            self.loop = loop

    def clear_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        with self.lock:
            if self.loop is loop:
                self.loop = None

    def bluetooth_ready(self) -> bool:
        with self.lock:
            return self.loop is not None and not self.loop.is_closed()

    def current(self) -> dict[str, int]:
        with self.lock:
            return dict(self.values)

    def _apply(self, values: dict[str, int]) -> None:
        for key, value in values.items():
            characteristic = self.characteristics.get(key)
            if characteristic is not None:
                characteristic.set_value(value)


class GattServiceInterface(ServiceInterface):
    def __init__(self) -> None:
        super().__init__(GATT_SERVICE)

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":
        return SERVICE_UUID

    @dbus_property(access=PropertyAccess.READ)
    def Primary(self) -> "b":
        return True

    @dbus_property(access=PropertyAccess.READ)
    def Characteristics(self) -> "ao":
        return list(CHARACTERISTIC_PATHS.values())


class SensorCharacteristic(ServiceInterface):
    def __init__(self, key: str, uuid: str, size: int, snapshot: Snapshot) -> None:
        super().__init__(GATT_CHARACTERISTIC)
        self.key = key
        self.uuid = uuid
        self.size = size
        self.snapshot = snapshot
        # dbus-next represents the D-Bus byte-array signature `ay` as bytes,
        # not as a list[int]. BlueZ omits this property (and can reject the
        # GATT application) when it receives a list here.
        self.value = bytes(size)
        self.notifying = False

    def set_value(self, value: int) -> None:
        new_value = value.to_bytes(self.size, byteorder="little", signed=False)
        if new_value == self.value:
            return
        self.value = new_value
        if self.notifying:
            self.emit_properties_changed({"Value": self.value})

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":
        return self.uuid

    @dbus_property(access=PropertyAccess.READ)
    def Service(self) -> "o":
        return SERVICE_PATH

    @dbus_property(access=PropertyAccess.READ)
    def Flags(self) -> "as":
        return ["read", "notify"]

    @dbus_property(access=PropertyAccess.READ)
    def Value(self) -> "ay":
        return self.value

    @dbus_property(access=PropertyAccess.READ)
    def Notifying(self) -> "b":
        return self.notifying

    @method()
    def ReadValue(self, options: "a{sv}") -> "ay":
        return self.value

    @method()
    def StartNotify(self):
        self.notifying = True
        self.emit_properties_changed({"Notifying": True, "Value": self.value})

    @method()
    def StopNotify(self):
        self.notifying = False
        self.emit_properties_changed({"Notifying": False})


class AdvertisementInterface(ServiceInterface):
    def __init__(self) -> None:
        super().__init__(ADVERTISEMENT)

    @dbus_property(access=PropertyAccess.READ)
    def Type(self) -> "s":
        return "peripheral"

    @dbus_property(access=PropertyAccess.READ)
    def ServiceUUIDs(self) -> "as":
        return [SERVICE_UUID]

    @dbus_property(access=PropertyAccess.READ)
    def LocalName(self) -> "s":
        return DEVICE_NAME

    @method()
    def Release(self):
        print("[BLE] BlueZ liberó el anuncio de Tzinda")


class ApplicationObjectManager(ServiceInterface):
    def __init__(self) -> None:
        super().__init__(OBJECT_MANAGER)
        self.service = GattServiceInterface()
        self.characteristics = {
            "bpm": SensorCharacteristic("bpm", PULSE_UUID, 2, SNAPSHOT),
            "spo2": SensorCharacteristic("spo2", SPO2_UUID, 1, SNAPSHOT),
            "caidas": SensorCharacteristic("caidas", FALLS_UUID, 4, SNAPSHOT),
        }
        SNAPSHOT.characteristics = self.characteristics
        self.advertisement = AdvertisementInterface()

    @method()
    def GetManagedObjects(self) -> "a{oa{sa{sv}}}":
        characteristic_interfaces = {
            CHARACTERISTIC_PATHS[key]: {
                "UUID": Variant("s", item.uuid),
                "Service": Variant("o", SERVICE_PATH),
                "Flags": Variant("as", ["read", "notify"]),
                "Value": Variant("ay", item.value),
                "Notifying": Variant("b", item.notifying),
            }
            for key, item in self.characteristics.items()
        }
        return {
            SERVICE_PATH: {
                GATT_SERVICE: {
                    "UUID": Variant("s", SERVICE_UUID),
                    "Primary": Variant("b", True),
                    "Characteristics": Variant("ao", list(CHARACTERISTIC_PATHS.values())),
                }
            },
            **{
                path: {GATT_CHARACTERISTIC: props}
                for path, props in characteristic_interfaces.items()
            },
        }


SNAPSHOT = Snapshot()


class GattRuntime:
    def __init__(self) -> None:
        self.bus: MessageBus | None = None
        self.object_manager = ApplicationObjectManager()
        self.loop: asyncio.AbstractEventLoop | None = None

    async def run(self) -> None:
        loop = asyncio.get_running_loop()
        self.loop = loop
        try:
            self.bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
            SNAPSHOT.set_loop(loop)
            SNAPSHOT._apply(SNAPSHOT.current())

            self.bus.export(APP_PATH, self.object_manager)
            self.bus.export(SERVICE_PATH, self.object_manager.service)
            for key, characteristic in self.object_manager.characteristics.items():
                self.bus.export(CHARACTERISTIC_PATHS[key], characteristic)
            advertisement_path = f"{APP_PATH}/advertisement0"
            self.bus.export(advertisement_path, self.object_manager.advertisement)

            root = self.bus.get_proxy_object(BLUEZ, "/", await self.bus.introspect(BLUEZ, "/"))
            managed = await root.get_interface(OBJECT_MANAGER).call_get_managed_objects()
            adapter_path = next(
                (path for path, interfaces in managed.items() if GATT_MANAGER in interfaces),
                None,
            )
            if adapter_path is None:
                raise RuntimeError("BlueZ no expone GattManager1 en el UNO Q.")

            adapter = self.bus.get_proxy_object(
                BLUEZ,
                adapter_path,
                await self.bus.introspect(BLUEZ, adapter_path),
            )
            try:
                properties = adapter.get_interface(PROPERTIES)
                await properties.call_set("org.bluez.Adapter1", "Powered", Variant("b", True))
            except Exception as error:
                print(f"[BLE] No se pudo encender Bluetooth automáticamente: {error}")

            gatt_manager = adapter.get_interface(GATT_MANAGER)
            await gatt_manager.call_register_application(APP_PATH, {})
            try:
                advertising_manager = adapter.get_interface(ADVERTISEMENT_MANAGER)
            except Exception as error:
                raise RuntimeError("El adaptador BlueZ no admite anuncios BLE.") from error
            await advertising_manager.call_register_advertisement(advertisement_path, {})
            print(f"[BLE] Pulsera anunciada como {DEVICE_NAME}; servicio {SERVICE_UUID}")
            await self.bus.wait_for_disconnect()
            raise RuntimeError("La conexión con BlueZ se cerró.")
        finally:
            SNAPSHOT.clear_loop(loop)
            if self.bus is not None:
                self.bus.disconnect()
                self.bus = None
            self.loop = None


class ReadingHandler(BaseHTTPRequestHandler):
    server_version = "TzindaBleBrick/1.0"

    def log_message(self, format_string: str, *args: Any) -> None:
        print("[BLE HTTP] " + format_string % args)

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            # A container may retry or leave while a response is being sent.
            return

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(200, {"ok": True, "bluetooth": SNAPSHOT.bluetooth_ready()})
            return
        self.send_json(404, {"ok": False, "error": "not_found"})

    def do_POST(self) -> None:
        if self.path != "/api/v1/reading":
            self.send_json(404, {"ok": False, "error": "not_found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 16_384:
                raise ValueError("Tamaño de lectura no válido.")
            sample = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(sample, dict):
                raise ValueError("La lectura debe ser un objeto JSON.")
            if not SNAPSHOT.update(sample):
                self.send_json(503, {"ok": False, "error": "bluetooth_unavailable", "message": "Bluetooth está reiniciando; la lectura se conserva y se volverá a enviar."})
                return
            self.send_json(202, {"ok": True})
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            self.send_json(400, {"ok": False, "error": str(error)})
        except RuntimeError as error:
            print(f"[BLE HTTP] El servicio Bluetooth está reiniciando: {error}")
            self.send_json(503, {"ok": False, "error": "bluetooth_restarting"})


def run_gatt() -> None:
    while True:
        try:
            asyncio.run(GATT.run())
        except Exception as error:
            print(f"[BLE] No se pudo mantener el servicio GATT: {error}")
        print("[BLE] Reintentando Bluetooth en 3 segundos; la lectura sigue guardada en el Brick.")
        time.sleep(3)


GATT = GattRuntime()


def main() -> None:
    threading.Thread(target=run_gatt, name="tzinda-ble-gatt", daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", 8765), ReadingHandler)
    print("[BLE] Brick listo; esperando lecturas de TzindaApp")
    server.serve_forever()


if __name__ == "__main__":
    main()
