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
4. **CurrentOrderActivity** — lista todos los ítems pendientes. Cada ítem con botón **editar** (cantidad total lb + precio/lb, resumen dinámico) y **borrar** (con confirmación). Loading overlay mientras se envía el batch. "Ver ticket" → TicketDetailActivity. "Finalizar pedido" → CustomerPickerActivity.
5. **CustomerPickerActivity** — carga clientes de QB (`GET /api/customers`). Búsqueda en tiempo real. Modal de confirmación al seleccionar.
6. **HistoryActivity** — pedidos locales pendientes + remotos del API. Filtros ALL/PENDING/SENT. Cada batch mostrado con card azul (Pedido #, fecha, cliente, total, estado) — click → TicketDetailActivity.
7. **TicketDetailActivity** — ticket estilo recibo con header de la tienda, fecha, batch#, factura#, chip del cliente, ítems individuales (nombre + barcode·precio/lb + qty + total), grand total, estado. Botón **"Reimprimir ticket"** visible si hay impresora configurada en Settings.
8. **SettingsActivity** — backend URL, offline mode, **impresora Bluetooth** (lista dispositivos emparejados, botón probar), cerrar sesión.

### Data Layer

| File | Purpose |
|---|---|
| `data/network/ApiService.kt` | Retrofit interface — incluye `getCustomers()` |
| `data/network/AuthInterceptor.kt` | JWT Bearer en todos los requests |
| `data/network/RetrofitClient.kt` | Singleton Retrofit + TokenAuthenticator (refresh 401 + SESSION_EXPIRED broadcast) |
| `data/local/SecurePreferences.kt` | JWT, refreshToken, backend URL, offline mode, `printer_bt_address`, `printer_bt_name` |
| `data/local/AppDatabase.kt` | SQLiteOpenHelper — tablas `cached_products` + `pending_orders` |
| `data/local/dao/OrderDao.kt` | `insert`, `getAllPending`, `getById`, `update(id, price, qty)`, `deleteById`, `deleteAll`, `count` |
| `data/local/entities/PendingOrderEntity.kt` | `id, barcode, productName, price, quantity(Double), deviceId, createdAt, retryCount` |
| `data/repository/ProductRepository.kt` | API first, SQLite fallback |
| `data/repository/OrderRepository.kt` | `savePendingOrder`, `updatePendingOrder(id, price, qty)`, `deletePendingOrder`, `sendBatch(items, customerId, customerName)` |
| `data/sync/SyncWorker.kt` | WorkManager — reenvía pending cada 15 min |
| `data/sync/OrderStatusWorker.kt` | WorkManager — consulta SENT cada 2 min, notifica cambios |
| `data/local/NotificationHelper.kt` | Canal + notificación de pedido sincronizado |
| `data/print/PrintService.kt` | Bluetooth SPP → ZQ630 Plus. CPCL. `printTicket()` + `printTest()` |
| `data/print/BluetoothPermission.kt` | `hasBtConnectPermission()` helper para Android 12+ |
| `data/Models.kt` | DTOs: `QbCustomer`, `QbCustomersResponse`, `BatchRequest(items, customerId, customerName)`, `OrderDto(…customerName)` |

### Layouts

| Layout | Uso |
|---|---|
| `activity_main.xml` | Pantalla principal — scanner card + badge "Ver pedido" |
| `activity_current_order.xml` | Pedido en curso — lista ítems + totales + botones + loading overlay |
| `activity_ticket_detail.xml` | Ticket de venta — scroll card estilo recibo + botón Reimprimir |
| `activity_history.xml` | Historial — chips filtro + lista batches |
| `activity_customer_picker.xml` | Selector de cliente QB — búsqueda + lista |
| `activity_settings.xml` | Ajustes — conexión, scanner, offline, impresora BT |
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
   - Editar: dialog con "Cantidad total (lb)" + "Precio/lb" + resumen dinámico
   - Borrar: confirmación AlertDialog
   - "Ver ticket" → TicketDetailActivity (preview)
   - "Finalizar pedido" → CustomerPickerActivity

3. CustomerPickerActivity
   - GET /api/customers (JWT) → lista QB
   - Modal "¿Asignar pedido a [cliente]?" antes de confirmar

4. Confirmado → CurrentOrderActivity.finalizeOrder()
   - Loading overlay: "Enviando pedido…" / "Imprimiendo ticket…"
   - POST /api/orders/batch con customer_id + customer_name
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

Nombre producto      (F4, LEFT, x=8)
barcode  $precio/lb  (F4, LEFT, x=8)
qty lb  =  $total    (F4, LEFT, x=8)
[espacio entre ítems]

TOTAL                (F4, CENTER)
$XX.XX               (F7, CENTER)
X.XX lb en total     (F4, CENTER)
Excellentia          (F4, CENTER)
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
- **BatchRequest** incluye `customerId` y `customerName` opcionales.
- **SecurePreferences** guarda: JWT, refreshToken, backend URL, offline mode, `printer_bt_address`, `printer_bt_name`.
- **Offline mode** default true. LoginActivity lo pone false tras login exitoso.
- **TokenAuthenticator** usa OkHttpClient independiente para el refresh (evita loops).
- **registerReceiver con `RECEIVER_NOT_EXPORTED`** — obligatorio API 33+.
- **DataWedge** profile `TestScannerProfile` creado programáticamente al primer lanzamiento.
- **Backend URL default** `http://10.0.2.2:3000` (emulador).
- **No Room/KAPT/KSP** — AGP 9.2.1 embeds Kotlin plugin, raw SQLite.
- **Timezone** — `SimpleDateFormat` usa UTC al parsear timestamps ISO 8601 del backend.
- **Loading overlay** en CurrentOrderActivity — se muestra durante todo el envío del batch + impresión, con texto descriptivo de la etapa actual.
- **Botón Reimprimir** en TicketDetailActivity — solo visible si `SecurePreferences.getPrinterAddress() != null`.

### Pending Improvements

- [ ] **Device registration** — auto-call `POST /api/devices/register` on login
- [ ] **Retry button** — in HistoryActivity, resend failed/pending orders manually
- [ ] **Almacenar pesos individuales** — guardar el desglose de unidades en `PendingOrderEntity` para poder editarlos individualmente después
