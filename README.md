# Excellentia — Android App (TC22)

App de escaneo y ventas para **Zebra TC22** con integración a **QuickBooks Online** e impresión Bluetooth a **Zebra ZQ630 Plus**.

---

## Stack

| Tecnología | Versión |
|---|---|
| Lenguaje | Kotlin |
| Mínimo SDK | 30 |
| Compilar SDK | 36 |
| HTTP | Retrofit 2.11 + OkHttp 4.12 + Gson |
| Auth | JWT Bearer + EncryptedSharedPreferences |
| DB local | SQLite (SQLiteOpenHelper, sin Room) |
| Offline | WorkManager (SyncWorker 15 min) |
| Escáner | DataWedge (Zebra TC22) |
| Impresión | Bluetooth Classic SPP, lenguaje CPCL |

---

## Activities

| Activity | Función |
|---|---|
| `LoginActivity` | Login con backend URL, email, password |
| `MainActivity` | Dashboard: seleccionar cliente, escanear, badge "Ver pedido" |
| `ProductDetailActivity` | Detalle producto, múltiples unidades, precio editable, historial |
| `CurrentOrderActivity` | Carrito: cada unidad por separado, editar/borrar, finalizar |
| `CustomerPickerActivity` | Selección de cliente QB con búsqueda |
| `TicketDetailActivity` | Ticket preview + botón reimprimir |
| `HistoryActivity` | Historial de pedidos (locales + remotos) |
| `SettingsActivity` | Backend URL, offline mode, impresora BT, logout |
| `OrderSuccessActivity` | Pantalla de éxito post-envío |

---

## Flujo Completo

```
1. Login → MainActivity
2. Seleccionar cliente (obligatorio antes de escanear)
3. Escanear producto → ProductDetailActivity
   - Múltiples unidades con peso individual (+/- 0.1)
   - Precio total editable (tap → diálogo)
   - Timeline historial de precios del cliente
   - Validación contra precio mínimo (min_price)
4. Agregar al pedido → guarda 1 registro por unidad en SQLite
5. Ver pedido → CurrentOrderActivity
   - Cada unidad visible con su peso y total
   - Editar: cantidad (lb) y precio total vinculados
   - Borrar con confirmación
6. Finalizar pedido → envía batch al backend
   - Loading overlay con 3 pasos
   - Imprime ticket vía Bluetooth (si configurada)
   - Limpia cliente activo
```

---

## Capa de Datos

### Red
- `ApiService.kt` — Retrofit interface (productos, órdenes, clientes, historial precios)
- `AuthInterceptor.kt` — JWT Bearer en headers
- `RetrofitClient.kt` — Singleton + TokenAuthenticator (refresh 401)

### Local
- `AppDatabase.kt` — SQLiteOpenHelper (tablas: `cached_products`, `pending_orders`)
- `OrderDao.kt` — CRUD de pedidos pendientes
- `SecurePreferences.kt` — JWT, refresh, backend URL, printer BT, active customer

### Repositorios
- `ProductRepository.kt` — API first, SQLite fallback
- `OrderRepository.kt` — offline queue, batch send, price history

---

## Características Clave

| Feature | Detalle |
|---|---|
| **Customer-first** | Seleccionar cliente antes de escanear. Persiste hasta finalizar el pedido |
| **Precio negociable** | Tap en precio total → diálogo para modificarlo, validado contra mínimo |
| **Historial precios** | Timeline visual de precios anteriores del mismo cliente para el producto |
| **Precio mínimo** | `min_price` desde backend. Bloquea si está por debajo |
| **Unidades individuales** | Cada unidad se guarda como registro separado en el carrito |
| **Edición vinculada** | En CurrentOrder: lb ↔ total se actualizan mutuamente (tasa fija) |
| **Offline** | SQLite local + WorkManager reenvía pendientes cada 15 min |
| **Bluetooth print** | CPCL → ZQ630 Plus, con test desde Settings |
| **QuickBooks sync** | SyncEngine en backend procesa orders PENDING → Invoice en QBO |

---

## Compilar

```powershell
.\gradlew clean assembleDebug
adb uninstall com.example.test
adb install app\build\outputs\apk\debug\app-debug.apk
```
