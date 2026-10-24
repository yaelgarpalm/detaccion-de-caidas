# TzindaApp 2.1.7

Aplicación Android nativa para apoyar el monitoreo de una persona y avisar a sus contactos ante una posible caída. La detección es experimental: puede omitir caídas o generar falsas alarmas y no sustituye atención médica.

## Roles y dispositivos

- **Persona protegida:** lleva el teléfono; comparte su ubicación y los datos que recibe de la pulsera.
- **Cuidador/a:** consulta ubicación, eventos, pulso y SpO₂ desde otra red siempre que ambos teléfonos tengan Internet.
- **Cámara:** se agrega por separado con QR o código. Envía avisos de detección; el video no se transmite a Supabase.
- **Pulsera Arduino UNO Q:** se conecta al teléfono de la persona por Bluetooth LE. Wi-Fi local está disponible como alternativa para probar el servicio HTTP del UNO Q.

La vinculación de persona, cuidador y cámara usa Supabase por Internet; no requiere que los teléfonos estén en la misma red Wi-Fi. Los códigos se generan desde **Dispositivos**. Las lecturas, eventos y ubicación se guardan localmente y se sincronizan con Supabase cuando hay conexión. La ubicación requiere permiso de Android y que el monitoreo esté activo.

La pulsera envía pulso, SpO₂ y detecciones de golpe/caída. El teléfono protegido inicia una cuenta regresiva de 20 segundos; si no se cancela, solicita SMS y llamada a los contactos configurados. Cuando varias fuentes reportan el mismo incidente, la app deduplica el aviso para evitar llamadas repetidas.

## Supabase

El backend incluye autenticación, perfiles, vínculos, dispositivos, mediciones, eventos y Realtime con políticas RLS. La configuración y despliegue están descritos en [docs/supabase-backend.md](docs/supabase-backend.md); las migraciones están en [supabase/migrations](supabase/migrations).

Gradle lee `TZINDA_SUPABASE_URL`, `TZINDA_SUPABASE_PUBLISHABLE_KEY` y `TZINDA_SUPABASE_REDIRECT_URL` del entorno o de un archivo local `.env.local`. Copia [.env.example](.env.example) como plantilla. No guardes contraseñas, claves privadas ni la clave `service_role` en el APK o el repositorio. La clave `service_role` solo se configura en el entorno servidor de Supabase.

## Arduino UNO Q

Los proyectos listos para Arduino App Lab están en [arduino](arduino): la pulsera con BLE y la API local de SQLite/HTTP. También están los paquetes ZIP en [outputs](outputs). Consulta sus README para cargar cada proyecto. La dirección HTTP se puede cambiar desde los ajustes de TzindaApp; ambos métodos de conexión con la pulsera se pueden desconectar y reconectar desde la app.

## Compilar

Abre esta carpeta en Android Studio o ejecuta en Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Identificador: `com.guardian.tzindaapp.v2`; versión 2.1.7, código 11; Android mínimo 7.0 (API 24). La compilación debug usa firma de depuración y sirve para pruebas, no para publicación en Google Play.

La compilación local se completó correctamente el 23 de octubre de 2026. El flujo entre dos teléfonos y una pulsera debe verificarse en los dispositivos reales; esta compilación no confirma una prueba integral de hardware.
