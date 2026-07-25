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
3. **ProductDetailActivity** — muestra producto con precio/lb. Múltiples unidades con peso individual cada una (+/- 0.1). Tap en cantidad → diálogo numérico. "Agregar al pedido" → SQLite local. Botón deshabilitado (mismo patrón que "sin stock") si: el producto no tiene barcode asignado (bloquea **siempre**, incluido pre-orden — problema estructural), o no está vinculado a QuickBooks / está inactivo ahí (no aplica en modo pre-orden ni en modo edición). **Modo edición** (extra `EDIT_ORDER_ID`, se llega desde el botón "editar" en `CurrentOrderActivity`): precarga cantidad/precio de la fila existente, botón dice "Save changes" y actualiza esa fila en vez de agregar una nueva.
4. **CurrentOrderActivity** — lista todos los ítems pendientes. Cada ítem con botón **editar** (reabre `ProductDetailActivity` precargada con los datos de la fila — mismo modo Case/Unit/Bucket/Lbs que al agregar, ya no un diálogo genérico de "lb") y **borrar** (con confirmación). Re-escanear un producto Case/Unit ya en el carrito, al mismo precio, suma la cantidad a la fila existente en vez de duplicarla (productos por peso quedan siempre en filas separadas, uno por unidad pesada). Loading overlay mientras se envía el batch. "Ver ticket" → TicketDetailActivity. "Finalizar pedido" → SignatureActivity (luego checkPrinterThenFinalize).
5. **SignatureActivity** — pantalla completa de firma del cliente. Canvas táctil (`SignatureView`). Botones "Limpiar" y "Confirmar firma". Exporta firma como PNG base64. Al confirmar → checkPrinterThenFinalize() en CurrentOrderActivity.
6. **CustomerPickerActivity** — carga clientes de QB (`GET /api/customers`). Búsqueda en tiempo real. Cada card muestra nombre + dirección completa (gris). Modal de confirmación con nombre y dirección. Retorna `customer_id`, `customer_name`, `customer_address` en el intent result. **Long-press** en card muestra menú: "Asignar como cliente activo" o "Ver historial de pedidos" → ClientHistoryActivity.
7. **HistoryActivity** — pedidos locales pendientes + remotos del API. Chips: Todos/Enviados/Pendientes/Fallidos + fecha Hoy/Todos. Cada batch mostrado con card azul — click → TicketDetailActivity. Empty state dinámico por chip (ej. "Sin pedidos fallidos"). El chip "Fallidos" filtra batches remotos con `orders.any { status == "FAILED" }`.
8. **TicketDetailActivity** — ticket estilo recibo con header de la tienda, fecha, batch#, factura#, chip del cliente, ítems individuales (nombre + barcode·precio/lb + qty + total), grand total, estado. Botón **"Reimprimir ticket"** visible si hay impresora configurada en Settings. Botón **"Resend to QuickBooks"** visible si el batch tiene `batchId` y su estado no es `SENT` — reintenta el envío al instante y actualiza el ticket en pantalla; error se muestra en modal ("Got it"), no Snackbar.
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
| `data/local/AppDatabase.kt` | SQLiteOpenHelper v12 — tablas `cached_products` (incluye `qb_item_id`/`qb_active`), `pending_orders` (incluye `case_qty`), `pending_batches`, `cached_customers` |
| `data/local/dao/OrderDao.kt` | `insert`, `getAllPending`, `getById`, `update(id, price, qty)`, `deleteById`, `deleteAll`, `count` |
| `data/local/entities/PendingOrderEntity.kt` | `id, barcode, productName, price, quantity(Double), deviceId, createdAt, retryCount, unit, caseQty` |
| `data/repository/ProductRepository.kt` | API first, SQLite fallback solo en error de red/servidor — un 404 limpio (oculto/inexistente) no cae al cache y lo borra si estaba |
| `data/repository/OrderRepository.kt` | `savePendingOrder(..., merge=true)` (agrupa por barcode+precio salvo que se pase `merge=false`), `updatePendingOrder(id, price, qty)`, `deletePendingOrder`, `sendBatch(items, customerId, customerName, signature)`, `retryBatchSync(batchId)`, `prefetchAllProducts()` (pagina el catálogo completo y poda del cache local lo que no vino en el barrido) |
| `data/sync/SyncWorker.kt` | WorkManager — reenvía solo `pending_batches` (pedidos finalizados sin conexión) cada 15 min. **No** toca `pending_orders` (el carrito actual) — hacerlo vaciaba el carrito en segundo plano |
| `data/sync/OrderStatusWorker.kt` | WorkManager — consulta SENT cada 2 min, notifica cambios |
| `data/local/NotificationHelper.kt` | Canal + notificación de pedido sincronizado |
| `data/print/PrintService.kt` | Bluetooth SPP → ZQ630 Plus. CPCL. `printTicket()` + `printTest()` |
| `data/print/BluetoothPermission.kt` | `hasBtConnectPermission()` helper para Android 12+ |
| `data/Models.kt` | DTOs: `QbCustomer(id, displayName, active, addressLine1?, city?, stateCode?, postalCode?, fullAddress)`, `QbCustomersResponse`, `BatchRequest(items, customerId, customerName, signature)`, `OrderDto(…customerName)`, `DamageItem(barcode, productName, qty, unitPrice)`, `creditsTotalOf(damageItems, authoritative)` |
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
**Overflow:** `wrapText(str, 28)` en nombre de producto, nombre del cliente, dirección del cliente, términos — 28×17px=476px deja ~100px de margen físico seguro. `take(N)` en subtitle/city/address/phone/invoiceId (datos de la empresa, no del cliente). La dirección del cliente **no** se trunca — antes se partía en la primera coma y cada mitad se cortaba con `.take(32)`, perdiendo texto sin aviso si alguna mitad era larga; ahora usa el mismo `wrapText(28)` que todo lo demás.
**Ítems layout:** nombre wrappeable (F4, wrapText 28); luego `twoCol(detail, "$XX.XX", 28)` en línea siguiente. Las líneas se agrupan por producto (ver `GroupedTicketItem` abajo) — un producto escaneado varias veces en el mismo pedido aparece en **una sola línea** con peso y total sumados. El `detail` cambia según la categoría de unidad: **LBS** → `"X.XX lb x $X.XX/lb"` (con decimales, el peso es fraccionario); **CASE/UNIT/BUCKET** → `"N - Case x $XX.XX"` (cantidad entera, sin `.00`, con guion). Los ítems además se agrupan por categoría (LBS → CASE → UNIT → BUCKET → otras alfabético) — el encabezado de categoría (ej. `"CASE"`) solo aparece si el pedido mezcla más de un tipo; con un solo tipo el ticket queda igual que antes. La línea de cantidad total al pie del ticket suma cantidad+unidad solo si hay una única categoría (`"22.80 lb total"`); si hay mezcla, no tiene sentido sumar lb + case + unit, así que muestra `"N items total"` en su lugar. Helpers compartidos con `TicketDetailActivity.buildReceipt()` (misma lógica para el ticket impreso y la vista en pantalla): `ticketCategoryFor()`, `isWeightTicketCategory()`, `byTicketCategory()` en `data/Models.kt`.
**TOTAL:** `twoCol("TOTAL:", "$XX.XX", 28)` en F4 — misma línea, mismo tamaño que el resto del ticket.
**Helpers:** `twoCol(left, right, width)` rellena con espacios; `wrapText(text, maxChars)` primero parte por `\n` (párrafos) y recién ahí divide por palabras dentro de cada uno — necesario porque el comando CPCL `T` es de una sola línea; si una "palabra" arrastraba un salto de línea crudo (típico en el disclaimer guardado desde la webapp, con Enter entre cada punto numerado), rompía ese comando al imprimir y el texto después del salto se perdía. Un párrafo en blanco (`\n\n` seguido) genera una línea vacía para conservar el espaciado.

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
- **Agrupar re-escaneos en el carrito** — `OrderDao.findActiveByBarcodeAndPrice(barcode, price)` + `OrderRepository.savePendingOrder(..., merge=true)` (default): si ya hay una fila activa del mismo barcode y mismo precio, suma la cantidad ahí en vez de duplicar la fila. El precio entra en el match a propósito — si cambió entre escaneos, no se mezclan. `ProductDetailActivity` pasa `merge=false` solo en el loop de productos por peso, para que cada unidad pesada individualmente siga siendo su propia fila editable (Case y cantidad simple usan el default `merge=true`).
- **customer_id + customer_name** en orders — guardados en MySQL, enviados a QB al crear invoice.
- **BatchRequest** incluye `customerId`, `customerName`, y `signature` (base64 PNG) opcionales.
- **SignatureView** — custom View en `com.example.test`. No requiere permisos. Canvas blanco con Paint stroke 7f, bezier suavizado. `getBase64()` crea Bitmap ARGB_8888 y lo comprime a PNG.
- **Flujo firma** — `pendingSignature: String?` en CurrentOrderActivity. Se limpia a `null` tras cada `sendBatch()`. `launchSignatureAfterCustomer: Boolean` flag para lanzar firma automáticamente tras elegir cliente desde "Finalizar pedido".
- **Payment Method** — `pendingPaymentMethod: String?` en CurrentOrderActivity. `askPaymentMethod(skipPrint)` muestra diálogo **obligatorio** (`setCancelable(false)`, sin opción de omitir) con 3 botones: Cash / Check / On Account (`btn_account`, "A cuenta" en `values-es`). Se llama después del ticket #1 (ver **Doble impresión** abajo), nunca antes — antes de la Fase 76 este diálogo existía en el código pero no se llamaba desde ningún lado (dead code), por lo que el método de pago nunca se le preguntaba al usuario en producción. Se muestra en el ticket ("Payment: Cash") y en `CustomerMemo` de QB junto con el negative sale.
- **Doble impresión (Fase 76)** — `CurrentOrderActivity.printFirstTicketThenAskPayment(skipPrint)` corre entre la firma y `finalizeOrder()`: imprime un primer ticket (sin `Payment:` ni número de factura — se llama con `paymentMethod = null`, `invoiceId = null`) usando los datos ya conocidos en ese punto (firma, dañados), y recién entonces muestra `askPaymentMethod()`. Al elegir el método, `finalizeOrder()` corre como siempre (manda el batch, y si hay conexión imprime un **segundo** ticket ya con `Payment:` y — solo si el envío fue online — el número de factura real de QBO; si quedó offline, ese segundo ticket sale igual que el primero sin número de factura, y la factura llega después vía `qb_invoice_id` cuando se sincronice). `toBatchItems()` extrae el mapeo `PendingOrderEntity → BatchItem` compartido por ambos prints para no duplicar la query. Solo aplica a `CurrentOrderActivity` (pedidos normales) — `PreOrderDetailActivity` (conversión de pre-órdenes) sigue con una sola impresión (sin doble print), pero desde la Fase 77 (ver abajo) también pregunta el método de pago de forma obligatoria antes de convertir.
- **`payment_method` persistido en MySQL (Fase 77)** — `orders.payment_method VARCHAR(20) NULL`. Se guarda en `createBatch` (mismo valor repetido en cada fila del batch, igual que `customer_id`/`customer_name`) y en `convertPreOrder`. Antes solo viajaba de paso hacia el `CustomerMemo` de la factura de QBO y se descartaba; ahora queda consultable en MySQL y visible en la webapp (`/orders`: columna "Payment" + filtro + línea en el ticket modal). De paso quedó activado el diálogo obligatorio de pago en `PreOrderDetailActivity` (Cash/Check/On Account, mismo patrón que `CurrentOrderActivity` — antes era dead code ahí también). También corregí `retryBatchSync` en el backend: mandaba `paymentMethod = null` hardcodeado al reintentar una factura fallida (porque `orders` nunca lo guardaba); ahora lee el valor ya persistido en la fila.
- **Negative Sale / Créditos por daño** — `pendingDamageItems: List<DamageItem>` en CurrentOrderActivity (barcode/productName/qty por producto, no un contador único). Tras confirmar firma, `askDamagedItems()` muestra diálogo para capturar unidades dañadas/caducas por producto, y calcula `unitPrice` de cada uno con `unitValueOf()` (espeja la regla del backend: para Case divide `order.price / caseQty` porque ahí `order.price` ya es el precio de la caja completa). Se pasa a `PrintService.printTicket(damageItems, creditsTotal)` y a `OrderRepository.sendBatch()` → `BatchRequest.damageItems` → backend, que calcula el crédito autoritativo (`creditCalculator.ts`), lo persiste, lo agrega como línea negativa real en la factura de QBO (no solo memo), y lo devuelve en `BatchResponse.creditsTotal`. El ticket (impreso y en pantalla) muestra `Subtotal`/`Credits`/`TOTAL` solo si hay crédito — ver `creditsTotalOf()` en `data/Models.kt`. Se limpia a lista vacía tras `sendBatch()`. Detalle completo: Fase 75 en `excellentia/PROGRESS.md`.
- **Leyenda legal** — `wrapText()` en PrintService divide el texto de términos en líneas de ≤ 30 chars. Siempre impresa antes de la firma ("Customer Signature").
- **Company settings refresh** — `MainActivity.refreshCompanySettings()` hace fetch de `GET /api/settings` en cada `onResume()` en background (silencioso). Actualiza `SecurePreferences` sin re-login. El ticket siempre refleja los datos más recientes de la webapp.
- **SecurePreferences** guarda: JWT, refreshToken, backend URL, offline mode, `printer_bt_address`, `printer_bt_name`, `active_customer_id`, `active_customer_name`, `active_customer_address`, user info, last scan, company settings.
- **SecurePreferences — recuperación de EncryptedSharedPreferences corrupta** — `createEncryptedPrefs()` envuelve `EncryptedSharedPreferences.create()` en try/catch por `GeneralSecurityException`. Si la clave del Android Keystore quedó inválida (crash típico: `AEADBadTagException`/`KeyStoreException: VERIFICATION_FAILED` en `BaseActivity.attachBaseContext()` al arrancar), borra el archivo `secure_prefs` corrupto con `context.deleteSharedPreferences()` y lo recrea desde cero. Efecto secundario esperado: se pierde el JWT/config local guardados y el usuario debe volver a iniciar sesión.
- **Offline mode** default true. LoginActivity lo pone false tras login exitoso.
- **TokenAuthenticator** usa OkHttpClient independiente para el refresh (evita loops).
- **registerReceiver con `RECEIVER_NOT_EXPORTED`** — obligatorio API 33+.
- **DataWedge** profile `TestScannerProfile` creado programáticamente al primer lanzamiento.
- **Backend URL default** `http://10.0.2.2:3000` (emulador).
- **No Room/KAPT/KSP** — AGP 9.2.1 embeds Kotlin plugin, raw SQLite.
- **Orden de migraciones en `AppDatabase.onUpgrade`** — el bloque que recrea una tabla entera (rename + CREATE + `INSERT ... SELECT *` + DROP) debe correr **antes** que cualquier `ALTER TABLE ADD COLUMN` sobre esa misma tabla, nunca después — si corre después, la tabla recreada no tiene las columnas nuevas y el `INSERT ... SELECT *` falla por cantidad de columnas despareja, tirando toda la migración. Ya pasó con el bloque `oldVersion < 3` de `pending_orders` (corría al final, después de los `ALTER` de `oldVersion < 4`/`< 8`) — se reordenó para que corra primero.
- **Timezone** — `SimpleDateFormat` usa UTC al parsear timestamps ISO 8601 del backend.
- **Loading overlay** en CurrentOrderActivity — se muestra durante todo el envío del batch + impresión, con texto descriptivo de la etapa actual.
- **Botón Reimprimir** en TicketDetailActivity — solo visible si `SecurePreferences.getPrinterAddress() != null`.
- **Botón "Resend to QuickBooks" en TicketDetailActivity** — visible si `batchId.isNotBlank()` y el estado agregado del batch no es `SENT`. Llama a `OrderRepository.retryBatchSync(batchId)` → `POST /api/orders/batch/:batchId/retry`. Éxito: actualiza `orderStatus` (var, no val) y reconstruye el recibo en pantalla (`ticketContent.removeAllViews()` + `buildReceipt()`) sin salir de la pantalla — mismo patrón que el refresh de firma/damage por API. Error: `MaterialAlertDialogBuilder` con botón "Got it" (`btn_understood`), no Snackbar — a diferencia del resultado de reimprimir, que sí usa Snackbar.
- **SyncWorker no toca el carrito** — `pending_orders` (tabla del carrito, poblada por `ProductDetailActivity.saveOrder()` en cada "Agregar al pedido") y `pending_batches` (pedidos ya finalizados sin conexión al momento de enviarlos) son conceptualmente distintos aunque viven en la misma base. `SyncWorker` solo debe tocar `pending_batches` — antes reenviaba también `pending_orders` fila por fila vía un endpoint legacy (`createOrder`, sin cliente/firma/batch) y borraba cada fila al tener éxito, vaciando el carrito del usuario en segundo plano (con la app cerrada, cada 15 min o al recuperar conexión) antes de que llegara a finalizar el pedido.
- **`signatureForReprint`** — `var` de clase en TicketDetailActivity (análogo a `damageItemsForReprint`). Se inicializa desde el intent `"signature"` extra. Cuando `batchId` existe, `getBatchDamage` retorna la firma desde la API y la actualiza. El botón Reimprimir usa este `var` para que la firma correcta llegue a la impresora también desde historial.
- **TransactionTooLargeException fix** — `listOrders` backend excluye `signature` (nunca fue columna de `orders` tras Fase 48). La firma se carga bajo demanda via `GET /api/orders/damage/:batchId` → `{ data: [...], signature: "..." }`. `HistoryActivity` y `ClientHistoryActivity` pasan orders directo al Intent sin strip.
- **`batch_signatures` table** — tabla dedicada `(batch_id PK, signature MEDIUMTEXT)`. Un batch de N ítems almacena la firma una sola vez. `createBatch` y `convertPreOrder` insertan en esta tabla; `getBatchDamage` la lee.
- **`ApiResponse<T>`** — campo `signature: String? = null` en el modelo genérico para deserializar la firma de `getBatchDamage`.
- **`OrderDto`** — no tiene campo `signature` (eliminado en Fase 48; `listOrders` nunca lo retorna).
- **Stock en ProductDetailActivity** — `Product.stock: Int = 0` propagado desde `ProductDto` → `ProductRepository` → `MainActivity` intent extra `"STOCK"`. `tvStock` debajo del barcode: verde si stock≥1, rojo si stock=0. Stock=0 → botón rojo/blanco `"PRODUCTO SIN STOCK"` deshabilitado + `btnUnitMinus/Plus` deshabilitados. Stock=-1 (offline/sin dato) → sin badge, flujo normal.
- **Estado QuickBooks en ProductDetailActivity** — `Product.qbItemId`/`qbActive` propagados desde `ProductDto` (`qb_item_id`/`qb_active` del backend) → `ProductRepository`/`OrderRepository.prefetchAllProducts` (cache offline incluido) → `MainActivity` (`SuggestionItem` + intents `"QB_ITEM_ID"`/`"QB_ACTIVE"`) → `ProductDetailActivity`. `tvQbStatus` (mismo patrón visual que `tvStock`): sin `qb_item_id` → "Not linked to QuickBooks"; `qb_active == false` → "Inactive in QuickBooks". Cualquiera de los dos → botón "Agregar al pedido" deshabilitado (`btn_qb_not_available`) + `btnUnitMinus/Plus` deshabilitados. `qb_active == null` (nunca sincronizado desde que existe el campo) no bloquea. **No aplica en modo pre-orden** (`isPreOrderMode`) — las pre-órdenes son borradores, el vínculo a QBO solo importa al convertir.
- **Barcode obligatorio en ProductDetailActivity** — `hasBarcode = barcode.isNotBlank() && barcode != "unknown"` (`"unknown"` es el placeholder que usan `MainActivity.openSuggestion()`/`CreatePreOrderActivity` cuando el producto no tiene barcode). Sin barcode real, el matching `orders.barcode = products.barcode` (retry a QBO, SyncEngine — ver Fase 64 en `excellentia/PROGRESS.md`) nunca puede funcionar, sin importar `qb_item_id`/`qb_active`. `tvQbStatus` → "No barcode assigned" (`label_no_barcode`), botón → `btn_no_barcode`, mismo bloqueo visual que el resto. A diferencia del gating de `qb_item_id`/`qb_active`, **este sí bloquea en modo pre-orden** — es un problema estructural, no un estado de QBO que se pueda resolver antes de convertir. Tiene prioridad de mensaje sobre el estado de QBO (si faltan ambos, se muestra "No barcode assigned").
- **Cache offline de productos — poda automática** — `OrderRepository.prefetchAllProducts()` pagina el catálogo completo (antes se cortaba en los primeros 500) y, si termina sin errores, borra de `cached_products` cualquier producto no tocado en ese barrido (`ProductDao.deleteOldCache(syncStartedAt)`) — así un producto ocultado/desactivado en el backend deja de aparecer en búsquedas offline en vez de quedar cacheado para siempre. Corre en cada `onResume`/reconexión con internet. `ProductRepository.findByBarcode()` distingue 404 real (no cae al cache, lo borra si estaba) de error de red/servidor (sí cae al cache) — antes ambos casos caían al cache indistintamente, sirviendo productos ya ocultos incluso online.
- **Modal artículos dañados — scroll fix** — `FrameLayout` wrapper de altura fija (38% de `heightPixels`) pasado como `.setView(wrapper)`. El `ScrollView` vive dentro del wrapper. Sin `setOnShowListener` ni `requestLayout`. Causa del crash anterior: `ViewGroup.LayoutParams` genérico dentro de `setOnShowListener` causaba `ClassCastException` en el contenedor interno del diálogo.
- **Ticket en inglés** — `TicketDetailActivity` y `PrintService`: `"Pedido #"→"Order #"`, `"Factura #"→"Invoice #"`, `"Cliente:"→"Customer:"`, `"lb en total"→"lb total"`. Layout `activity_ticket_detail.xml`: `"Ticket de venta"→"Sale Ticket"`, `"Reimprimir ticket"→"Reprint ticket"`. El subtítulo del encabezado del recibo viene de `SecurePreferences.getCompanySubtitle()` (configurable en webapp `/settings`).
- **HistoryActivity** — botón retry (`btnRetryEntry`) oculto con `GONE` en pedidos locales pendientes. Los pedidos del carrito local aparecen en historial como informativos únicamente. `HistoryActivity.bindRetry()` existe en el código pero **no se llama desde ningún lado** — es código muerto de un intento anterior; el retry real se implementó en `TicketDetailActivity` ("Resend to QuickBooks").
- **Editar ítem del carrito** — `CurrentOrderActivity.editItem(order)` reabre `ProductDetailActivity` con extra `EDIT_ORDER_ID = order.id` (reemplazó al diálogo genérico `dialog_edit_order.xml`, eliminado — hablaba de "lb" para cualquier tipo de producto). `PRODUCT_PRICE` se manda como precio **por unidad**: para Case se reconstruye `order.price / order.caseQty`, así `ProductDetailActivity` puede re-multiplicar con su lógica normal (`isCaseBased → pricePerLb = productPrice * caseQty`) sin casos especiales — target es que `pricePerLb` termine igual a `order.price`. En `ProductDetailActivity`: `resetCount()` precarga la cantidad existente en vez de reiniciar a 1; el stepper +/- funciona igual que al agregar (para peso, agrega/ajusta entradas en `weights`); `saveOrder()` llama `updatePendingOrder(id, price, quantity)` sobre la fila existente — para peso, `quantity = weights.sum()` (suma todo lo que haya en la lista, no solo la primera entrada, por si se incrementó durante la edición). El chequeo de vinculación a QBO (`qbStateBlocked`) se salta en modo edición porque `editItem()` no manda `QB_ITEM_ID`/`QB_ACTIVE` (no viven en `pending_orders`) y el ítem ya pasó esa validación al agregarse; el chequeo de barcode obligatorio sí se mantiene, porque el barcode viaja completo siempre.
- **`formatQty()` en ProductDetailActivity** — muestra cantidades sin decimales de sobra ("2" en vez de "2.00"), conservando la parte fraccionaria cuando sí existe ("6.5"). Usado en los labels de cantidad/peso de esta pantalla (`tvTotalWeight`, `label_weight_display`) — no toca el ticket, que sigue mostrando siempre 2 decimales por convención de recibo.
- **`values-es/strings.xml` existe y duplica algunos strings** — hay traducciones propias para varios recursos (ej. `label_weight_display`). Al cambiar el *formato* de un placeholder (`%.2f` → `%s`, agregar/quitar un argumento) en `values/strings.xml`, hay que revisar si ese mismo string tiene copia en `values-es/` y actualizarla igual — si no, un dispositivo en español carga la versión vieja con el placeholder desincronizado y explota con `IllegalFormatConversionException` en tiempo de ejecución (no lo detecta el compilador). Pasó con `label_weight_display` en la Fase 73.
- **Ticket — agrupación de productos repetidos** — `GroupedTicketItem(barcode, productName, quantity, total)` en `data/Models.kt`, con extensiones `List<OrderDto>.groupedForTicket()` y `List<BatchItem>.groupedForTicket()` (`@JvmName` distinto por choque de firma JVM tras erasure). Agrupa por `barcode` (fallback `productName` si viene vacío), preserva orden de primera aparición, suma `quantity`/`total`; el precio/lb mostrado es el promedio ponderado `total/quantity`. Usado en `TicketDetailActivity.buildReceipt()` y `PrintService.buildCpcl()` — afecta vista "Ver ticket", ticket final, reimpresión e historial por igual. `grandTotal`/`totalQty` se siguen calculando sobre la lista completa (no la agrupada). `CurrentOrderActivity` (pantalla de edición) **no** agrupa — cada escaneo sigue siendo editable individualmente.
- **Ticket — agrupación por categoría de unidad** — sobre el resultado de `groupedForTicket()`, `byTicketCategory()` (`data/Models.kt`) agrupa por `ticketCategoryFor(unit)`: `"LBS"` (unit null/blank/"Lbs"), o el valor de `unit` en mayúsculas para cualquier otro (`"CASE"`, `"UNIT"`, `"BUCKET"`). Orden fijo LBS → CASE → UNIT → BUCKET → otras alfabético. El formato de cantidad depende de `isWeightTicketCategory(category)`: LBS con decimales y conteo de unidades (`"N - X.XX lb x $X.XX/lb"` — N = `GroupedTicketItem.count`, cuántas filas se combinaron en ese peso, ej. 2 chicharrones pesados por separado = "2 - 2.00 lb..."), el resto con enteros y guion (`"N - Case x $XX.XX"`, sin `.00` — no tiene sentido comprar "1.00 caja"). El encabezado de categoría solo se imprime si hay más de una categoría en el pedido (`groupedByCategory.size > 1`) — evita ruido quando todo es del mismo tipo. La línea de cantidad total al pie hace lo mismo: suma cantidad+unidad solo con una categoría, si no muestra cantidad de productos (`"N items total"`) porque sumar lb + case + unit no tiene sentido.
- **`GroupedTicketItem.count`** — cuántas filas (escaneos/pesadas individuales del mismo barcode) se combinaron en una línea agrupada del ticket. Se incrementa en `groupedForTicket()` en cada merge; para LBS es la cantidad de unidades físicas pesadas por separado (no el peso), para Case/Unit normalmente queda en 1 porque esos ya se agrupan al agregarlos al carrito (Fase 70) y llegan al ticket como una sola fila.
- **Ticket — desglose de unidades por caja** — `GroupedTicketItem.caseQty: Int?` (= `products.qty` cuando `unit = "Case"`, unidades por caja — una caja puede traer 1 artículo o varios). Se hilvana desde `ProductDetailActivity.saveOrder()` → `PendingOrderEntity`/`pending_orders` (SQLite) → `BatchItem`/`OrderDto` (`case_qty` en request/response) → `orders.case_qty` en el backend (Fase 69) → de vuelta a `GroupedTicketItem` vía `groupedForTicket()`. Con categoría `"CASE"` y `caseQty > 0`, el renglón de detalle es `"N - Case of Q x $XX.XX"` (Q = `caseQty`); sin el dato (pedidos viejos, o convertidos desde pre-orden — `PreOrderItem` no tiene `caseQty`) cae al formato simple `"N - Case x $XX.XX"`, sin romper nada. Es un atributo del producto, no de la venta — al agrupar líneas repetidas del mismo barcode no se suma, se toma tal cual.

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
- [x] **Retry button** — implementado como "Resend to QuickBooks" en `TicketDetailActivity` (se llega tocando la card del batch desde HistoryActivity), no como botón inline en la lista de HistoryActivity
- [ ] **Almacenar pesos individuales** — guardar el desglose de unidades en `PendingOrderEntity` para poder editarlos individualmente después
- [ ] **Editar pre-orden** — actualmente solo se puede ver y convertir; agregar edición de items/fecha/notas desde PreOrderDetailActivity
- [ ] **Pre-órdenes offline** — actualmente requieren internet; considerar SQLite local (pre_orders v7) para borradores offline
