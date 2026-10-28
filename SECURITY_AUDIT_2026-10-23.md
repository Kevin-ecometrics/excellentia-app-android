# Auditoría de seguridad: excellentia scanner (Android)

Fecha: 2026-10-23
Proyecto: `com.excellentia.scanner` (v1.6.1, minSdk 30, targetSdk 36)
Estado: **documentado, implementación PENDIENTE (no se aplicó ningún cambio)**

## Entorno revisado

| Componente | Versión |
|---|---|
| Android Studio | 2026.2.1 (build 262.9437.185) |
| JBR (JDK incluido) | OpenJDK 25.0.3 (2026-04-21) |
| Gradle | 9.4.1 |
| AGP | 9.2.1 |
| Platform-tools | 37.0.0 |

Carpetas de config de versiones viejas de Studio (2025.3.4, 2026.1.3, 2026.1.4) siguen en
`%LOCALAPPDATA%\Google` y `%APPDATA%\Google`. No es vulnerabilidad; se pueden borrar.

## Dependencias (consulta a OSV.dev, 2026-10-23)

Sin vulnerabilidades registradas: okhttp 4.12.0, logging-interceptor 4.12.0, retrofit 2.11.0,
gson 2.11.0, work-runtime-ktx 2.9.1, security-crypto 1.1.0-alpha06, coroutines 1.7.3,
material 1.10.0, appcompat 1.6.1, core-ktx 1.10.1, activity-ktx 1.8.0, constraintlayout 2.1.4,
junit 4.13.2, okio 3.6.0, kotlin-stdlib 2.2.10.

Limitación: solo dependencias directas más okio y kotlin-stdlib; no se escaneó el árbol transitivo completo.

## Hallazgos y cambios pendientes

### 1. Tráfico HTTP sin cifrar permitido (prioridad alta)
- Archivo: `app/src/main/res/xml/network_security_config.xml`
- Actual: `<base-config cleartextTrafficPermitted="true" />`
- Riesgo: lectura o alteración de credenciales y pedidos en una red Wi-Fi hostil.
- Contexto: backend por defecto `https://app.excellentiafoods.com`
  (`SecurePreferences.kt:202`), pero `LoginActivity` permite que el usuario escriba otra URL
  (se guarda con `saveBackendUrl`).
- Opciones:
  - **A (recomendada):** `cleartextTrafficPermitted="false"` global. Se rompe solo el uso de URLs `http://` manuales.
  - **B:** cleartext cerrado global, con `<domain-config>` solo para una IP o dominio interno concreto de pruebas (por definir).
- **Decisión pendiente:** A o B.

### 2. Backup habilitado con reglas vacías
- Archivos: `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`
- Cambio: `android:allowBackup="false"`; añadir `<exclude>` de `sharedpref` y `database`
  en ambos XML por si se reactiva.
- Efecto: restaurar en otro equipo ya no trae prefs ilegibles (el Keystore no viaja con el backup).
  `SecurePreferences` ya maneja el caso con "recreando".

### 3. Release sin minify (riesgo bajo, cambio con riesgo de regresión)
- Archivo: `app/build.gradle.kts`
- Cambio: `isMinifyEnabled = true`, `isShrinkResources = true`.
- Requiere reglas en `proguard-rules.pro` para los modelos que Gson serializa y Retrofit.
- Probar build release completo (login, pedidos, sincronización, WorkManager) antes de distribuir.
- Hacer por separado, al final.

### 4. Opcional / a futuro
- `androidx.security:security-crypto:1.1.0-alpha06` está deprecada (sin CVE). Planear reemplazo
  (Keystore directo o Tink).

## Revisado y correcto
- Solo `LoginActivity` está `exported="true"` (launcher).
- Logging de OkHttp en nivel BODY solo con `BuildConfig.DEBUG` (`RetrofitClient.kt:34`).
- Sin API keys ni secretos hardcodeados en `src/main/java`.
- Tokens en `EncryptedSharedPreferences` (AES256).

## Orden sugerido al implementar
1 → 2 → compilar debug y probar login → 3 por separado.

## Antes de implementar
- Proyecto sin git: hacer copia de respaldo de los archivos a modificar.
- Sin commits ni builds release sin solicitud explícita.
