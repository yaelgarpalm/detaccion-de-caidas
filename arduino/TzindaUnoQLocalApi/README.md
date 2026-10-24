# Tzinda UNO Q Local SQLite API

Aplicación local de Arduino App Lab para guardar lecturas recibidas por HTTP en SQLite y entregar la última lectura a TzindaApp. No genera valores de pulso ni SpO₂. El archivo de base se crea en `data/tzinda_local.sqlite3` al iniciar la app.

## Instalar en UNO Q

1. Abre Arduino App Lab y elige importar una app desde un archivo ZIP.
2. Selecciona `TzindaUNOQ-LocalSQLite-App.zip` y abre la app importada.
3. Pulsa **Run**. Si tienes otra app usando el puerto 8081, detén esa app primero.
4. En TzindaApp, abre **Ajustes → Base local de pruebas**, escribe `http://IP_DEL_UNO_Q:8081` y guarda. Usa la IP que muestra `arduino-cli board list`.
5. Activa el monitoreo en TzindaApp. La app móvil consultará `GET /api/v1/sensor` cada cinco segundos.

Esta app Python corre en el Linux del UNO Q; no es un sketch `.ino` para Arduino IDE. Arduino App Lab administra apps para la parte Linux y, cuando se incluye, el microcontrolador. Este paquete solo necesita Linux para SQLite y HTTP.

## API

- `GET /health` — disponibilidad del servicio.
- `GET /api/v1/stats` — cantidad de lecturas locales.
- `GET /api/v1/sensor` — devuelve la última lectura o HTTP 503 si aún no hay ninguna.
- `POST /api/v1/sensor` — guarda una lectura JSON real en SQLite.
- `GET /` — página de estado.

El POST admite JSON anidado y preserva el payload original. Reconoce `heart_rate_bpm`, `heartRate`, `heart_rate`, `bpm`, `pulse`, `spo2_percent`, `spo2`, `oxygen_saturation`, `latitude`, `longitude` y ejes `accel_x`/`ax`, `accel_y`/`ay`, `accel_z`/`az`. Las demás claves también quedan guardadas dentro del JSON original.

El firmware que está actualmente en el UNO Q no se puede extraer desde este paquete. Para que SQLite reciba datos del Arduino, el programa que lee el sensor debe enviar cada medición como JSON a `POST http://IP_DEL_UNO_Q:8081/api/v1/sensor`. Si el sensor no está conectado, la base se inicia vacía y la API informa que todavía no hay lecturas.

La base conserva hasta 30 días y 100,000 filas. El servicio solo debe usarse en una red local de pruebas de confianza.
