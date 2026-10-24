# Tzinda UNO Q · Pulsera

Paquete de Arduino App Lab con el sketch real de pulso, SpO₂ y caídas, más el puente local hacia TzindaApp.

## Qué contiene y cómo fluyen los datos

- `sketch/sketch.ino` y `sketch/caida.h`: leen MAX30102 y MPU6050 y publican `get_data` por Arduino Bridge.
- `python/main.py`: consulta `get_data` cada segundo, conserva un búfer SQLite local en `data/tzinda_local.sqlite3`, publica la API HTTP local y reenvía lecturas al Brick BLE.
- `bricks/tzindable`: usa la imagen pública `python:3.13-slim`, monta el socket D-Bus del sistema y registra el servicio GATT de `Tzinda`.
- `bricks/tzindable/app/vendor/dbus_next`: dependencia BLE incluida en el paquete para evitar instalar módulos al arrancar.
- TzindaApp se conecta primero por Bluetooth BLE y puede hacerlo desde Dispositivos sin activar las alertas de caída. Guarda las lecturas en SQLite del teléfono y las sincroniza con Supabase cuando hay Internet. El cuidador recibe pulso, SpO₂ y caídas por Supabase Realtime cuando tiene abierta la app.

El búfer SQLite del UNO Q es local para pruebas y para tolerar lecturas mientras el teléfono no consulta el dispositivo. Supabase es el almacenamiento remoto compartido; este paquete no configura PostgreSQL local ni una base externa.

## Cableado del sketch

| Sensor | Pin UNO Q |
| --- | --- |
| MAX30102 VIN / GND | 3V3 / GND |
| MAX30102 SDA / SCL | D7 / D6 |
| MPU6050 VCC / GND | 3V3 / GND |
| MPU6050 SDA / SCL | D3 / D2 |
| MPU6050 AD0 | GND (dirección 0x68) |

Usa tierras comunes. Si el módulo no incluye resistencias pull-up, el sketch recomienda 4.7 kΩ de SDA y SCL a 3V3 para cada bus I²C.

## Cargar y vincular

1. Importa `TzindaUNOQ-Pulsera-App.zip` en Arduino App Lab.
2. Selecciona el perfil `default` para la placa Arduino UNO Q y ejecuta la app. App Lab compila `sketch/sketch.ino` y ejecuta la aplicación Python en Linux.
3. En el primer inicio, App Lab debe descargar la imagen pública `python:3.13-slim`. El Brick usa `APP_HOME` para montar el código desde la carpeta de la app importada (no desde `.cache`) y el servicio se llama `tzinda-ble` para que Docker DNS lo resuelva. Después monta `/var/run/dbus/system_bus_socket` del UNO Q y anuncia BLE. En una terminal del UNO Q confirma que `bluetoothctl show` encuentre un controlador; si Bluetooth está apagado, ejecuta `sudo systemctl enable --now bluetooth`.
4. Confirma en Monitor que aparezcan `MAX30102 listo` y `MPU6050 listo`. En la consola del Brick debe aparecer `[BLE] Pulsera anunciada como Tzinda`; la consola Python sigue mostrando BPM, SpO₂ y caídas. Si BPM y SpO₂ permanecen en cero, revisa el montaje y la conexión I²C de los sensores.
5. En TzindaApp, abre **Dispositivos → Pulsera de sensores → Conectar o volver a conectar**. Permite **Dispositivos cercanos** cuando Android lo solicite y acerca el teléfono al UNO Q. El dispositivo BLE se anuncia como `Tzinda`; no hace falta activar el monitoreo para emparejar la conexión.
6. En **Ajustes → Pulsera y datos del teléfono** elige Bluetooth o Wi-Fi. Bluetooth es el método principal y funciona sin Internet; si eliges Wi-Fi, guarda `http://IP_DEL_UNO_Q:8081` con la dirección que muestra `arduino-cli board list`. Desde Dispositivos puedes desconectar o volver a conectar.

Si la consola del Brick muestra `SignatureBodyMismatchError` o `ObjectManager.InterfacesAdded`, importa y ejecuta el ZIP actualizado: las características BLE deben publicar sus bytes como `bytes` para que BlueZ las acepte. Cuando queda listo, el Brick muestra `[BLE] Pulsera anunciada como Tzinda` y Android puede encontrarla en **Dispositivos**.

La conexión BLE entre el UNO Q y el teléfono no necesita Internet. El Brick recibe cada lectura del contenedor Python por la red interna de Docker y la anuncia por BLE. Si BlueZ pierde la conexión, el Brick vuelve a registrar el servicio y conserva la última lectura para anunciarla al reiniciarse. Las lecturas permanecen en SQLite del teléfono y se sincronizan con Supabase cuando vuelve la conexión; los SMS y llamadas automáticos requieren el servicio móvil del teléfono.

Si el punto 3 no muestra ambos sensores listos, revisa alimentación, GND, pines y pull-ups antes de esperar lecturas. Sin una sesión de Supabase, el flujo local sigue guardando en el teléfono; para compartir datos con el cuidador ambos teléfonos deben usar la misma cuenta y mantener activa la vinculación.

## Lecturas y límites

`GET /health`, `GET /api/v1/stats` y `GET /api/v1/sensor` están disponibles en el puerto 8081 como alternativa para pruebas en red local. BLE usa el servicio `6b9f0001-6e2a-4f72-a11d-545a696e6461`; sus características notifican BPM (entero uint16 little-endian), SpO₂ (uint8) y caídas (uint32 little-endian).

La fórmula de SpO₂ del sketch es empírica y el mismo código la marca como no médica; no es un oxímetro clínico ni debe usarse para diagnóstico. La detección de caída también es experimental y requiere validar el montaje físico.
