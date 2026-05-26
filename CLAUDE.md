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
6. **CustomerPickerActivity** — carga clientes de QB (`GET /api/customers`). Búsqueda en tiempo real. Cada card muestra nombre + dirección completa (gris). Modal de confirmación con nombre y dirección. Retorna `customer_id`, `customer_name`, `customer_address` en el intent result. **Long-press** en card muestra menú: "Asignar como cliente activo" o "Ver historial de pedidos" → ClientHistoryActivity.
7. **HistoryActivity** — pedidos locales pendientes + remotos del API. Chips: Todos/Enviados/Pendientes/Fallidos + fecha Hoy/Todos. Cada batch mostrado con card azul — click → TicketDetailActivity. Empty state dinámico por chip (ej. "Sin pedidos fallidos"). El chip "Fallidos" filtra batches remotos con `orders.any { status == "FAILED" }`.
8. **TicketDetailActivity** — ticket estilo recibo con header de la tienda, fecha, batch#, factura#, chip del cliente, ítems individuales (nombre + barcode·precio/lb + qty + total), grand total, estado. Botón **"Reimprimir ticket"** visible si hay impresora configurada en Settings.
9. **SettingsActivity** — backend URL, offline mode, **impresora Bluetooth** (lista dispositivos emparejados, botón probar), cerrar sesión.
10. **PreOrderListActivity** — lista pre-órdenes del servidor. Chips: **Pendientes** (DRAFT, default) / **Convertidas** (CONVERTED) / **Canceladas** (CANCELLED) / **Todas**. FAB blanco "Nueva pre-orden" → CreatePreOrderActivity. Click card → PreOrderDetailActivity. Empty state dinámico por chip. Fechas formateadas con `formatDate()` (soporta ISO con ms, sin ms, y `yyyy-MM-dd`).
11. **CreatePreOrderActivity** — crear pre-orden: seleccionar cliente (CustomerPickerActivity launcher), DatePicker para fecha de entrega, notas, agregar ítems por escaneo DataWedge o dialog manual (barcode + qty + price). Guarda via `POST /api/preorders`. Pre-llena cliente activo si hay uno seleccionado.
12. **PreOrderDetailActivity** — detalle de pre-orden con flujo de conversión idéntico a `CurrentOrderActivity`: Firma → Artículos dañados → Método de pago → Check impresora → `POST /api/preorders/:id/convert` → `OrderSuccessActivity`. Loading overlay con 3 pasos. Botones por estado: DRAFT/CONFIRMED → "Convertir" + "Cancelar"; CONVERTED → "Reusar pre-orden" (crea nueva DRAFT con mismos ítems/cliente) + "Ver en historial"; CANCELLED → sin botones. Fechas formateadas con `formatDate()`. Recibe `pre_order_id` por intent.
13. **ClientHistoryActivity** — historial de pedidos de un cliente específico. Header con nombre + resumen (pedidos, total). Lista de batch cards (reutiliza `item_batch_header.xml`). Click → carga orders del cliente y abre TicketDetailActivity. Recibe `customer_id` + `customer_name` por intent. Accesible desde: botón "Historial" en tarjeta de cliente activo de MainActivity, y long-press en CustomerPickerActivity.

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
| `PreOrderListActivity.kt` | Lista pre-órdenes del servidor; chips filtro; FAB → CreatePreOrderActivity |
| `CreatePreOrderActivity.kt` | Crea pre-órdenes: scanner DataWedge + dialog manual; DatePicker; guarda via API |
| `PreOrderDetailActivity.kt` | Detalle de pre-orden; convierte a pedido real (firma → payment → `/api/preorders/:id/convert`) |
| `ClientHistoryActivity.kt` | Historial de batches por cliente; usa `GET /api/customers/:id/orders`; click → TicketDetailActivity |
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
| `activity_pre_order_list.xml` | Lista de pre-órdenes — chips filtro + scroll + FAB |
| `activity_pre_order_detail.xml` | Detalle de pre-orden — info card + items card + botones Convertir/Cancelar |
| `activity_create_pre_order.xml` | Crear pre-orden — card cliente, fecha/notas, lista items dinámica, total estimado, btn Guardar |
| `item_pre_order.xml` | Card de pre-orden en lista — cliente, status, fecha, items count, total |
| `activity_client_history.xml` | Historial por cliente — header resumen (primary card) + lista de batch cards + swipe refresh |

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
      EXCELLENTIA         F7, CENTER
     Ticket de Venta      F4, CENTER (+ ciudad, dir, tel si aplica)
================================ separador principal 32x'='
12/06/2026 10:30          F4, LEFT x=0
Pedido  #XXXXXXXX         F4, LEFT x=0 (si aplica)
Factura #XXXXX            F4, LEFT x=0 (si aplica)
-------------------------------- separador si hay cliente
Cliente: Cool Cars         F4, LEFT x=0
Payment: Cash             F4, LEFT x=0 (si aplica)
  1 Infinite Loop         F4, LEFT x=8 (dirección)
  Cupertino, CA 95014     F4, LEFT x=8
================================ separador
Sprinkler Pipes     $4.10 F4, LEFT — twoCol(nombre, total, 32)
  22.8 lb x $0.18/lb      F4, LEFT x=8
================================ separador
           TOTAL           F4, CENTER
          $XX.XX           F7, CENTER
    XX.XX lb en total      F4, CENTER
      Excellentia          F4, CENTER
-------------------------------- solo si damageQty > 0
      Negative Sale        F4, CENTER
   X unit(s) damaged       F4, CENTER
--------------------------------
[terms 30ch word-wrap]     F4, CENTER — siempre
-------------------------------- solo si hay firma
   Customer Signature      F4, CENTER
[imagen PNG firma]
```
**Regla de alignment:** TODO LEFT — incluyendo nombre empresa y subtítulo. No se usa CENTER en ninguna línea del ticket (eliminado para coherencia y para evitar truncado en nombres largos) — city, address, phone, date, pedido, cliente, ítems, TOTAL (F7), lb total, footer, negative sale, terms, firma.
**Overflow:** `wrapText(str, 28)` en nombre de producto, nombre del cliente, términos — 28×17px=476px deja ~100px de margen físico seguro. `take(N)` en subtitle/city/address/phone/invoiceId.
**Ítems layout:** nombre wrappeable (F4, wrapText 28); luego `twoCol("X.XX lb x $X.XX/lb", "$XX.XX", 28)` en línea siguiente.
**TOTAL:** `twoCol("TOTAL:", "$XX.XX", 28)` en F4 — misma línea, mismo tamaño que el resto del ticket.
**Helpers:** `twoCol(left, right, width)` rellena con espacios; `wrapText(text, maxChars)` divide por palabras.

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
- **`signatureForReprint`** — `var` de clase en TicketDetailActivity (análogo a `damageItemsForReprint`). Se inicializa desde el intent `"signature"` extra. Cuando `batchId` existe, `getBatchDamage` retorna la firma desde la API y la actualiza. El botón Reimprimir usa este `var` para que la firma correcta llegue a la impresora también desde historial.
- **TransactionTooLargeException fix** — `listOrders` backend excluye `signature` (nunca fue columna de `orders` tras Fase 48). La firma se carga bajo demanda via `GET /api/orders/damage/:batchId` → `{ data: [...], signature: "..." }`. `HistoryActivity` y `ClientHistoryActivity` pasan orders directo al Intent sin strip.
- **`batch_signatures` table** — tabla dedicada `(batch_id PK, signature MEDIUMTEXT)`. Un batch de N ítems almacena la firma una sola vez. `createBatch` y `convertPreOrder` insertan en esta tabla; `getBatchDamage` la lee.
- **`ApiResponse<T>`** — campo `signature: String? = null` en el modelo genérico para deserializar la firma de `getBatchDamage`.
- **`OrderDto`** — no tiene campo `signature` (eliminado en Fase 48; `listOrders` nunca lo retorna).
- **Stock en ProductDetailActivity** — `Product.stock: Int = 0` propagado desde `ProductDto` → `ProductRepository` → `MainActivity` intent extra `"STOCK"`. `tvStock` debajo del barcode: verde si stock≥1, rojo si stock=0. Stock=0 → botón rojo/blanco `"PRODUCTO SIN STOCK"` deshabilitado + `btnUnitMinus/Plus` deshabilitados. Stock=-1 (offline/sin dato) → sin badge, flujo normal.
- **Modal artículos dañados — scroll fix** — `FrameLayout` wrapper de altura fija (38% de `heightPixels`) pasado como `.setView(wrapper)`. El `ScrollView` vive dentro del wrapper. Sin `setOnShowListener` ni `requestLayout`. Causa del crash anterior: `ViewGroup.LayoutParams` genérico dentro de `setOnShowListener` causaba `ClassCastException` en el contenedor interno del diálogo.
- **Ticket en inglés** — `TicketDetailActivity` y `PrintService`: `"Pedido #"→"Order #"`, `"Factura #"→"Invoice #"`, `"Cliente:"→"Customer:"`, `"lb en total"→"lb total"`. Layout `activity_ticket_detail.xml`: `"Ticket de venta"→"Sale Ticket"`, `"Reimprimir ticket"→"Reprint ticket"`. El subtítulo del encabezado del recibo viene de `SecurePreferences.getCompanySubtitle()` (configurable en webapp `/settings`).
- **HistoryActivity** — botón retry (`btnRetryEntry`) oculto con `GONE` en pedidos locales pendientes. Los pedidos del carrito local aparecen en historial como informativos únicamente.
- **Edit dialog (`dialog_edit_order.xml`)** — dos inputs: "Cantidad total (lb)" (`etQty`) y "Precio / lb" (`etPricePerLb`). Resumen dinámico `tvTotal` ("X.XX lb = $Y.YY"). Sin input de precio total — se eliminó; el precio/lb es editable directamente. Advertencia `tvMinWarning` compara `qty × rate` contra `minPrice`.

- **Pre-órdenes** — server-only (sin SQLite local); requieren internet. Flujo de conversión idéntico a `CurrentOrderActivity`: firma → dañados → método de pago → check impresora → convert → print → `OrderSuccessActivity`. `reusePreOrder()` crea una nueva pre-orden DRAFT con los mismos ítems.
- **Pre-órdenes ENUM bug** — la tabla `pre_orders.status` fue creada con `CONVERTED` partido por salto de línea de Windows. Fix: `ALTER TABLE pre_orders MODIFY COLUMN status ENUM(...)` + `UPDATE pre_orders SET status='CONVERTED' WHERE status=''`. Las SQLs en `ensureTables()` y `setup.ts` deben escribirse en una sola línea para evitar que se repita.
- **express.json limit** — configurado a `10mb` en `index.ts` para soportar firmas base64 PNG en el body.
- **Historial por cliente** — `ClientHistoryActivity` usa `GET /api/customers/:id/orders` (endpoint nuevo en backend). Al hacer click en un batch carga los orders del cliente y abre TicketDetailActivity.
- **CustomerPickerActivity long-press** — menú contextual con "Asignar" (comportamiento original) y "Ver historial" (abre ClientHistoryActivity).
- **MainActivity** — botón "Pre-órdenes" (ic_schedule) + botón "Historial" dentro de la tarjeta de cliente activo.
- **HistoryActivity FAILED filter** — el chip "Fallidos" filtra batches remotos con `orders.any { it.status == "FAILED" }`. El empty state muestra mensaje dinámico según el chip activo (`tvEmptyMessage`).
- **formatDate()** — helper privado en `PreOrderListActivity` y `PreOrderDetailActivity` que prueba múltiples formatos ISO (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, `'Z'`, sin Z, `yyyy-MM-dd`) para manejar variaciones de serialización MySQL.

### Pending Improvements

- [ ] **Device registration** — auto-call `POST /api/devices/register` on login
- [ ] **Retry button** — in HistoryActivity, resend failed/pending orders manually
- [ ] **Almacenar pesos individuales** — guardar el desglose de unidades en `PendingOrderEntity` para poder editarlos individualmente después
- [ ] **Editar pre-orden** — actualmente solo se puede ver y convertir; agregar edición de items/fecha/notas desde PreOrderDetailActivity
- [ ] **Pre-órdenes offline** — actualmente requieren internet; considerar SQLite local (pre_orders v7) para borradores offline
