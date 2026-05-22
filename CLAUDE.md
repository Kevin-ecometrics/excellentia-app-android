# CLAUDE.md — Android App (test/)

## Build & Run Commands

```powershell
.\gradlew assembleDebug      # APK debug
.\gradlew installDebug       # instalar en dispositivo
.\gradlew clean assembleDebug
```

## Architecture Overview

Single-module Android app (`:app`) targeting Zebra TC22 (barcode scanner) + Zebra ZQ630 Plus (Bluetooth printer). Kotlin, View-based UI, `minSdk 30`, `compileSdk 36`.

### Tech Stack
- **HTTP:** Retrofit 2.11 + OkHttp 4.12 + Gson
- **Auth:** JWT Bearer via AuthInterceptor + OkHttp Authenticator (auto-refresh en 401) + EncryptedSharedPreferences
- **DB local:** Raw SQLite via SQLiteOpenHelper (no Room/KSP/KAPT)
- **Offline:** WorkManager (SyncWorker 15 min)
- **Print:** Bluetooth Classic SPP → Zebra ZQ630 Plus via CPCL

### Activity Flow

1. **LoginActivity** (LAUNCHER) — POST `/api/auth/login`, guarda JWT + refreshToken + URL en SecurePreferences, navega a MainActivity.
2. **MainActivity** — verifica token en `onCreate`/`onResume`. DataWedge profile configurado automáticamente. Escaneo físico o botón manual → ProductDetailActivity. Badge "Ver pedido (N)" → CurrentOrderActivity. Pide `BLUETOOTH_CONNECT` en startup (Android 12+).
3. **ProductDetailActivity** — muestra producto con precio/lb. Múltiples unidades con peso individual cada una (+/- 0.1). Tap en cantidad → diálogo numérico. "Agregar al pedido" → SQLite local.
4. **CurrentOrderActivity** — lista todos los ítems pendientes. Cada ítem con botón **editar** (cantidad total lb + precio/lb editable, resumen dinámico) y **borrar** (con confirmación). Loading overlay mientras se envía el batch. "Ver ticket" → TicketDetailActivity. "Finalizar pedido" → SignatureActivity (luego checkPrinterThenFinalize).
5. **SignatureActivity** — pantalla completa de firma del cliente. Canvas táctil (`SignatureView`). Botones "Limpiar" y "Confirmar firma". Exporta firma como PNG base64. Al confirmar → checkPrinterThenFinalize() en CurrentOrderActivity.
6. **CustomerPickerActivity** — carga clientes de QB (`GET /api/customers`). Búsqueda en tiempo real. Cada card muestra nombre + dirección completa (gris). Modal de confirmación con nombre y dirección. Retorna `customer_id`, `customer_name`, `customer_address` en el intent result.
7. **HistoryActivity** — pedidos locales pendientes + remotos del API. Filtros ALL/PENDING/SENT. Cada batch mostrado con card azul (Pedido #, fecha, cliente, total, estado) — click → TicketDetailActivity.
8. **TicketDetailActivity** — ticket estilo recibo con header de la tienda, fecha, batch#, factura#, chip del cliente, ítems individuales (nombre + barcode·precio/lb + qty + total), grand total, estado. Botón **"Reimprimir ticket"** visible si hay impresora configurada en Settings.
9. **SettingsActivity** — backend URL, offline mode, **impresora Bluetooth** (lista dispositivos emparejados, botón probar), cerrar sesión.

### Data Layer

| File | Purpose |
|---|---|
| `data/network/ApiService.kt` | Retrofit interface — incluye `getCustomers()` |
| `data/network/AuthInterceptor.kt` | JWT Bearer en todos los requests |
| `data/network/RetrofitClient.kt` | Singleton Retrofit + TokenAuthenticator (refresh 401 + SESSION_EXPIRED broadcast) |
| `data/local/SecurePreferences.kt` | JWT, refreshToken, backend URL, offline mode, `printer_bt_address`, `printer_bt_name`, `active_customer_id/name/address`, datos de empresa, user info, last scan |
| `data/local/AppDatabase.kt` | SQLiteOpenHelper v6 — tablas `cached_products`, `pending_orders`, `cached_customers` |
| `data/local/dao/OrderDao.kt` | `insert`, `getAllPending`, `getById`, `update(id, price, qty)`, `deleteById`, `deleteAll`, `count` |
| `data/local/entities/PendingOrderEntity.kt` | `id, barcode, productName, price, quantity(Double), deviceId, createdAt, retryCount` |
| `data/repository/ProductRepository.kt` | API first, SQLite fallback |
| `data/repository/OrderRepository.kt` | `savePendingOrder`, `updatePendingOrder(id, price, qty)`, `deletePendingOrder`, `sendBatch(items, customerId, customerName, signature)` |
| `data/sync/SyncWorker.kt` | WorkManager — reenvía pending cada 15 min |
| `data/sync/OrderStatusWorker.kt` | WorkManager — consulta SENT cada 2 min, notifica cambios |
| `data/local/NotificationHelper.kt` | Canal + notificación de pedido sincronizado |
| `data/print/PrintService.kt` | Bluetooth SPP → ZQ630 Plus. CPCL. `printTicket()` + `printTest()` |
| `data/print/BluetoothPermission.kt` | `hasBtConnectPermission()` helper para Android 12+ |
| `data/Models.kt` | DTOs: `QbCustomer(id, displayName, active, addressLine1?, city?, stateCode?, postalCode?, fullAddress)`, `QbCustomersResponse`, `BatchRequest(items, customerId, customerName, signature)`, `OrderDto(…customerName)` |
| `SignatureView.kt` | Custom View táctil — captura trazos en Canvas (quadratic bezier), `getBase64()` exporta PNG base64, `clear()`, `isEmpty` |
| `SignatureActivity.kt` | Pantalla completa de firma — muestra nombre del cliente, `SignatureView`, botones Limpiar/Confirmar, retorna base64 via `RESULT_OK` |

### Layouts

| Layout | Uso |
|---|---|
| `activity_main.xml` | Pantalla principal — scanner card + badge "Ver pedido" |
| `activity_current_order.xml` | Pedido en curso — lista ítems + totales + botones + loading overlay |
| `activity_ticket_detail.xml` | Ticket de venta — scroll card estilo recibo + botón Reimprimir |
| `activity_history.xml` | Historial — chips filtro + lista batches |
| `activity_customer_picker.xml` | Selector de cliente QB — búsqueda + lista |
| `activity_settings.xml` | Ajustes — conexión, scanner, offline, impresora BT |
| `activity_signature.xml` | Firma del cliente — toolbar + nombre cliente + `SignatureView` (fill) + botones Limpiar/Confirmar |
| `item_pending_order.xml` | Ítem del pedido actual — nombre, barcode·precio, qty=total, btn editar, btn borrar |
| `item_batch_header.xml` | Card de batch en historial — azul, pedido#, fecha, cliente, total, estado |
| `item_order_product.xml` | Ítem individual dentro del ticket (TicketDetailActivity e HistoryActivity) |
| `item_ticket_row.xml` | Fila simple de ticket (legado) |
| `item_scan_entry.xml` | Card de pedido local pendiente en historial |

### Flujo de Pedido (completo)

```
1. Escanear → ProductDetailActivity
   - Múltiples unidades, cada una con peso propio
   - "Agregar al pedido" → guarda PendingOrderEntity (quantity = suma pesos)

2. Badge "Ver pedido (N)" → CurrentOrderActivity
   - Cada ítem como card individual con editar/borrar
   - Editar: dialog con "Cantidad total (lb)" + "Precio/lb" (ambos editables), resumen "X.XX lb = $Y.YY" dinámico
   - Borrar: confirmación AlertDialog
   - "Ver ticket" → TicketDetailActivity (preview)
   - "Finalizar pedido" → SignatureActivity (o CustomerPickerActivity si no hay cliente)

3. CustomerPickerActivity
   - GET /api/customers (JWT) → lista QB (con dirección desde BillAddr)
   - Cards muestran nombre + dirección completa en gris
   - Modal "¿Asignar pedido a [cliente]?\n[dirección]" antes de confirmar
   - Retorna customer_id + customer_name + customer_address al activity llamador
   - Tras confirmar → SignatureActivity automáticamente

4. SignatureActivity
   - Canvas táctil para firma del cliente
   - "Limpiar" borra el trazo, "Confirmar" valida que no esté vacío
   - Retorna PNG base64 → CurrentOrderActivity.checkPrinterThenFinalize()

5. CurrentOrderActivity.finalizeOrder()
   - Loading overlay: "Enviando pedido…" / "Imprimiendo ticket…"
   - POST /api/orders/batch con customer_id + customer_name + signature (base64)
   - Si printer configurada → PrintService.printTicket() via BT
   - clearPending() → finish() → MainActivity

5. HistoryActivity
   - Cards azules por batch (click → TicketDetailActivity)
   - TicketDetailActivity → ítems individuales + botón Reimprimir
```

### Impresión Bluetooth (ZQ630 Plus)

| Aspecto | Detalle |
|---|---|
| Protocolo | Bluetooth Classic SPP |
| UUID | `00001101-0000-1000-8000-00805F9B34FB` |
| Lenguaje | **CPCL** — ZPL es ignorado por el firmware de esta unidad |
| Fuentes | Font 4 (17×27px) y Font 7 (28×44px) — **font 3 NO disponible** en este firmware |
| Alineación | Solo `CENTER` y `LEFT` — `RIGHT` mal implementado → no usar |
| Ancho | `PAGE-WIDTH 576` (~3" a 203 DPI) |
| Height | Calculado dinámicamente (Y final del contenido + 200 dots de margen de corte) |
| Drain | `Thread.sleep(2000)` antes de cerrar socket — sin esto se cortan datos |
| Sin FORM | `FORM` causaba doble avance de papel — solo `PRINT` |
| Sin cancelDiscovery | Requería `BLUETOOTH_SCAN` innecesariamente |

**Estructura del ticket CPCL:**
```
EXCELLENTIA          (F7, CENTER)
Ticket de Venta      (F4, CENTER)
dd/MM/yyyy HH:mm     (F4, CENTER)
Pedido #XXXXXXXX     (F4, CENTER, si aplica)
Factura #XXXXX       (F4, CENTER, si aplica)
Cliente: Nombre      (F4, CENTER, si aplica)
1 Infinite Loop      (F4, CENTER, dirección línea 1 si > 32 chars)
Cupertino, CA 95014  (F4, CENTER, dirección línea 2)

Nombre producto      (F4, LEFT, x=8)
barcode  $precio/lb  (F4, LEFT, x=8)
qty lb  =  $total    (F4, LEFT, x=8)
[espacio entre ítems]

TOTAL                (F4, CENTER)
$XX.XX               (F7, CENTER)
X.XX lb en total     (F4, CENTER)
Excellentia          (F4, CENTER)

------------------------------ ← solo si damageQty > 0
Negative Sale          (F4, CENTER)
X unit(s) damaged/expired
------------------------------

I hereby acknowledge that all  ← leyenda legal (siempre)
above referenced goods have    ← word-wrapped 30 chars
been received...               ← (wrapText helper)

------------------------------
Customer Signature             ← solo si hay firma
[imagen PNG firma]
```

**Setup impresora:**
1. Emparejar ZQ630 en Ajustes → Bluetooth del TC22
2. Settings app → "Seleccionar impresora" → elegir ZQ630
3. "Imprimir página de prueba" para verificar
4. Guardar

**Permisos BT:**
- `BLUETOOTH` + `BLUETOOTH_ADMIN` (API ≤ 30, install-time)
- `BLUETOOTH_CONNECT` (API 31+, runtime — pedido en `MainActivity.onCreate`)
- `BLUETOOTH_SCAN` **NO declarado** — `cancelDiscovery()` fue eliminado

### Key Design Notes

- **PendingOrderEntity.quantity** = peso TOTAL en lb (suma de todas las unidades). Los pesos individuales por unidad NO se almacenan — al editar solo se puede editar el total.
- **customer_id + customer_name** en orders — guardados en MySQL, enviados a QB al crear invoice.
- **BatchRequest** incluye `customerId`, `customerName`, y `signature` (base64 PNG) opcionales.
- **SignatureView** — custom View en `com.example.test`. No requiere permisos. Canvas blanco con Paint stroke 7f, bezier suavizado. `getBase64()` crea Bitmap ARGB_8888 y lo comprime a PNG.
- **Flujo firma** — `pendingSignature: String?` en CurrentOrderActivity. Se limpia a `null` tras cada `sendBatch()`. `launchSignatureAfterCustomer: Boolean` flag para lanzar firma automáticamente tras elegir cliente desde "Finalizar pedido".
- **Payment Method** — `pendingPaymentMethod: String?` en CurrentOrderActivity. `askPaymentMethod()` muestra diálogo Cash/Check/Omitir tras `askDamagedItems()`. Se muestra en el ticket ("Payment: Cash") y en `CustomerMemo` de QB junto con el negative sale.
- **Negative Sale** — `pendingDamageQty: Int` en CurrentOrderActivity. Tras confirmar firma, `askDamagedItems()` muestra diálogo para capturar unidades dañadas/caducas. Se pasa a `PrintService.printTicket(damageQty)` (aparece en el ticket), y también a `OrderRepository.sendBatch(damageQty)` → `BatchRequest.damageQty` → backend → `createBatchInvoice(damageQty)` → línea `DescriptionOnly` en QB invoice. Se limpia a 0 tras `sendBatch()`.
- **Leyenda legal** — `wrapText()` en PrintService divide el texto de términos en líneas de ≤ 30 chars. Siempre impresa antes de la firma ("Customer Signature").
- **Company settings refresh** — `MainActivity.refreshCompanySettings()` hace fetch de `GET /api/settings` en cada `onResume()` en background (silencioso). Actualiza `SecurePreferences` sin re-login. El ticket siempre refleja los datos más recientes de la webapp.
- **SecurePreferences** guarda: JWT, refreshToken, backend URL, offline mode, `printer_bt_address`, `printer_bt_name`, `active_customer_id`, `active_customer_name`, `active_customer_address`, user info, last scan, company settings.
- **Offline mode** default true. LoginActivity lo pone false tras login exitoso.
- **TokenAuthenticator** usa OkHttpClient independiente para el refresh (evita loops).
- **registerReceiver con `RECEIVER_NOT_EXPORTED`** — obligatorio API 33+.
- **DataWedge** profile `TestScannerProfile` creado programáticamente al primer lanzamiento.
- **Backend URL default** `http://10.0.2.2:3000` (emulador).
- **No Room/KAPT/KSP** — AGP 9.2.1 embeds Kotlin plugin, raw SQLite.
- **Timezone** — `SimpleDateFormat` usa UTC al parsear timestamps ISO 8601 del backend.
- **Loading overlay** en CurrentOrderActivity — se muestra durante todo el envío del batch + impresión, con texto descriptivo de la etapa actual.
- **Botón Reimprimir** en TicketDetailActivity — solo visible si `SecurePreferences.getPrinterAddress() != null`.
- **Edit dialog (`dialog_edit_order.xml`)** — dos inputs: "Cantidad total (lb)" (`etQty`) y "Precio / lb" (`etPricePerLb`). Resumen dinámico `tvTotal` ("X.XX lb = $Y.YY"). Sin input de precio total — se eliminó; el precio/lb es editable directamente. Advertencia `tvMinWarning` compara `qty × rate` contra `minPrice`.

### Pending Improvements

- [ ] **Device registration** — auto-call `POST /api/devices/register` on login
- [ ] **Retry button** — in HistoryActivity, resend failed/pending orders manually
- [ ] **Almacenar pesos individuales** — guardar el desglose de unidades en `PendingOrderEntity` para poder editarlos individualmente después
