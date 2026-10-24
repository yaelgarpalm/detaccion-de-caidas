# Backend Supabase de TzindaApp

## Estado y arquitectura

El proyecto Android sigue en Kotlin con Views. `AppStore` conserva los datos y el flujo de emergencia locales; `LocalTelemetryDatabase` guarda cola durable de contactos, eventos, intentos técnicos y lecturas válidas; `SupabaseBackend` realiza Auth y REST; `SupabaseSyncJobService` reintenta en segundo plano con red disponible. La interfaz no espera la red para detectar o iniciar una alerta.

El cliente guarda solo los tokens de sesión, cifrados con AES-GCM y una clave de Android Keystore, en `noBackupFilesDir`. Las contraseñas solo viven en memoria mientras se autentica. La clave publishable de Android no es un secreto; `service_role` solo se utiliza en la Edge Function de eliminación y nunca forma parte de Gradle, el APK ni el repositorio.

## Datos remotos y privacidad

La migración inicial crea `profiles`, `devices`, `trusted_contacts`, `caregiver_links`, `caregiver_invites`, `fall_events`, `emergency_alerts`, `notification_attempts` y `measurements`. `realtime_location_and_short_invites` añade `caregiver_locations`; `independent_devices_and_account_links` agrega `camera` y `fall_sensor` al catálogo y permite una vinculación de cuenta completa. Todas las tablas mantienen RLS. El propietario se asigna desde `auth.uid()` por defecto y las políticas verifican la identidad en inserciones y actualizaciones. El cuidador consulta los dispositivos, eventos y mediciones de la cuenta protegida tras canjear un código de un solo uso de 8 caracteres (`ABCD-EFGH`, válido 10 minutos). Los vínculos antiguos limitados a un dispositivo siguen siendo válidos.

Se sincronizan nombre, rol, preferencias de aviso, contactos de confianza, eventos de caídas, estado técnico de solicitudes SMS/llamada, lecturas reales que entregue el UNO Q y la última ubicación consentida. Teléfono protegido, canal MPU, Arduino UNO Q y cada cámara tienen identificadores independientes. La cámara IA actual se ejecuta en la cámara de este teléfono; los registros de cámaras externas son inventario pendiente de conexión y no implican transmisión de video. La ubicación se publica únicamente mientras el monitoreo está activo, la persona protegida mantiene el permiso y hay una sesión de Supabase; al detener el monitoreo o retirar el permiso, la app elimina la coordenada remota. El cuidador recibe cambios de `caregiver_locations` por Supabase Realtime; el permiso de lectura requiere un vínculo activo y RLS. No se sincronizan domicilio, fecha de nacimiento, antecedentes médicos, alergias, video ni muestras crudas de movimiento. La tabla `notification_attempts` no contiene teléfono ni nombre del destinatario. Los registros remotos se conservan hasta que el usuario elimine su cuenta; el borrado cascada elimina los datos relacionados.

La ubicación del teléfono protegido se comparte en vivo solo con autorización explícita y mientras el servicio de monitoreo siga activo. Android y el proveedor de ubicación determinan la frecuencia real de las coordenadas, por lo que la interfaz del cuidador muestra también cuándo se recibió la última posición. La conexión BLE no se simula: el esquema aún no registra un estado BLE real. No hay push ni reenvío automático de emergencias.

La revisión de Supabase conserva tres avisos sobre funciones `SECURITY DEFINER`: son las operaciones de crear y canjear códigos de vínculo y revocar una relación. Requieren esos privilegios porque la tabla de invitaciones no tiene acceso directo desde el cliente; las funciones validan la sesión/propiedad, tienen `search_path` vacío y ejecutan solo esa operación. `is_linked_caregiver` usa permisos del usuario y RLS. El asesor de rendimiento marca los índices como no usados porque el esquema acaba de desplegarse y las tablas están vacías.

## Configuración local

1. Copia `.env.example` a `.env.local` en la raíz del repositorio.
2. Configura `TZINDA_SUPABASE_URL`, `TZINDA_SUPABASE_PUBLISHABLE_KEY` y `TZINDA_SUPABASE_REDIRECT_URL`. `.env.local` está ignorado por Git.
3. El registro por correo usa contraseña, sin OTP ni confirmación por correo:

   - En el repo, `supabase/config.toml` deja `auth.email.enable_confirmations = false` para el entorno local.
   - En el proyecto alojado, desactiva **Authentication → Providers → Email → Confirm email** y guarda el cambio. El panel del proyecto no se puede modificar desde las herramientas Supabase disponibles en esta sesión, así que este ajuste remoto requiere hacerlo en el Dashboard.
   - Al quedar desactivada la confirmación, `/auth/v1/signup` devuelve una sesión y la app entra directamente. No hacen falta Twilio ni la función `auth-email-hook` para crear cuenta o iniciar sesión. Si el registro muestra que Supabase aún exige confirmar el correo, revisa que el cambio remoto se haya guardado.

   Desactivar la confirmación permite registrar un correo aunque no se haya comprobado su propiedad. Escribe el correo correctamente; el restablecimiento de contraseña seguirá dependiendo del servicio de correo configurado. La función `auth-email-hook` queda para recuperación y otras notificaciones que envíe Auth; si se usa, debe tener los secretos `SEND_EMAIL_HOOK_SECRET` y `TWILIO_*` configurados. [Configuración de Auth por correo](https://supabase.com/docs/guides/auth/passwords).

Las credenciales de Twilio y el secreto del hook quedaron visibles en las capturas compartidas. Revoca la API key expuesta y genera un secreto nuevo para el hook; no reutilices esos valores.

   `scripts/Set-SupabaseSmtpAndOtp.ps1` es una alternativa separada para SMTP de Twilio SendGrid; no usa la API nativa `comms.twilio.com/v1/Emails` de este hook. El enlace `com.guardian.tzindaapp.v2://auth-callback` sigue permitido para restablecer contraseñas.
4. Aplica en orden las migraciones de `supabase/migrations/` al proyecto vacío asignado a TzindaApp. No ejecutes la migración inicial sobre otra base que ya tenga tablas con estos nombres. Si el proyecto ya tiene las migraciones anteriores aplicadas, ejecuta solo la migración nueva `20261021210000_independent_devices_and_account_links.sql` en el SQL Editor antes de usar el nuevo código de invitación de cuenta.
5. Despliega `supabase/functions/delete-account` con verificación JWT habilitada. La función usa las variables administradas `SUPABASE_URL`, `SUPABASE_ANON_KEY` y `SUPABASE_SERVICE_ROLE_KEY` del runtime; no las copies al repo.
6. Abre el proyecto en Android Studio y compila con `./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`).

También se puede configurar Gradle con las variables de entorno de los mismos nombres. En ausencia de configuración, la app ofrece el modo local.

## Sincronización y conflictos

- Cada evento tiene un UUID local estable; los inserts usan `on_conflict=id` y no duplican filas.
- La base SQLite conserva registros antes de programar sincronización. JobScheduler exige conectividad y aplica espera exponencial; cada fila se reintenta un máximo de ocho veces.
- Sin sesión o ante error de red, los eventos quedan en el teléfono. Un error de Auth no borra la cola.
- Las ediciones locales de perfil más recientes prevalecen; si no hay nombre local, se adopta el perfil remoto. Los contactos pendientes se suben antes de consultar la lista remota. Los eventos remotos no vuelven a ejecutar SMS ni llamadas.
- Las solicitudes de SMS/llamada se guardan como estados técnicos y no se confunden con entrega o respuesta confirmada.

## Pruebas

`supabase/tests/rls_isolation.sql` contiene aserciones SQL transaccionales de lectura e inserción cruzada en perfil, dispositivo, contactos, eventos y mediciones. En local, ejecuta `supabase start`, `supabase db reset` y luego `psql "postgresql://postgres:postgres@127.0.0.1:54322/postgres" -f supabase/tests/rls_isolation.sql`. Android: `gradlew.bat testDebugUnitTest` y `gradlew.bat assembleDebug`.

Un teléfono físico es necesario para verificar la cámara, Bluetooth BLE, cuenta regresiva bajo bloqueo, permisos de SMS/llamadas y ubicación. La cámara usa un servicio GATT local para intercambiar el código de vínculo y avisos breves; el video permanece en el teléfono. El Arduino UNO Q conserva su endpoint HTTP local para lecturas de pulso, SpO₂ y el contador de caídas; no necesita Supabase para que el teléfono protegido ejecute la cuenta regresiva y las alertas. Un servicio UNO Q con lecturas reales y dos teléfonos cercanos son necesarios para validar ambos flujos en hardware.
