package com.example.test.data.network

import com.example.test.data.*
import com.example.test.data.RefreshRequest
import com.example.test.data.RefreshResponse
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @GET("api/products/{barcode}")
    suspend fun getProductByBarcode(@Path("barcode") barcode: String): Response<ApiResponse<ProductDto>>

    @POST("api/orders")
    suspend fun createOrder(@Body request: CreateOrderRequest): Response<OrderResponse>

    @POST("api/orders/batch")
    suspend fun createBatch(@Body request: BatchRequest): Response<BatchResponse>

    @PUT("api/orders/batch/{batchId}/payment")
    suspend fun updateBatchPayment(
        @Path("batchId") batchId: String,
        @Body request: UpdatePaymentRequest
    ): Response<Unit>

    @GET("api/orders")
    suspend fun listOrders(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
        @Query("status") status: String? = null,
        @Query("customer_id") customerId: String? = null
    ): Response<ApiResponse<List<OrderDto>>>

    @POST("api/scans")
    suspend fun createScan(@Body request: ScanRequest): Response<ScanResponse>

    @POST("api/devices/register")
    suspend fun registerDevice(@Body request: DeviceRegisterRequest): Response<DeviceResponse>

    @PUT("api/devices/{id}/heartbeat")
    suspend fun heartbeat(@Path("id") deviceId: Int): Response<Unit>

    @POST("api/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): Response<RefreshResponse>

    @GET("api/customers")
    suspend fun getCustomers(): Response<QbCustomersResponse>

    @GET("api/customers/{id}")
    suspend fun getCustomer(@Path("id") customerId: String): Response<QbCustomer>

    @GET("api/settings")
    suspend fun getCompanySettings(): Response<CompanySettingsResponse>

    @PUT("api/auth/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Response<Unit>

    @GET("api/products")
    suspend fun searchProducts(
        @Query("search") search: String,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<ProductDto>>>

    @GET("api/products")
    suspend fun getAllProducts(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 500
    ): Response<ApiResponse<List<ProductDto>>>

    @GET("api/stats")
    suspend fun getStats(): Response<StatsResponse>

    @GET("api/products/{barcode}/history")
    suspend fun getProductPriceHistory(
        @Path("barcode") barcode: String,
        @Query("customer_id") customerId: String
    ): Response<PriceHistoryResponse>

    @GET("api/orders/damage/{batchId}")
    suspend fun getBatchDamage(
        @Path("batchId") batchId: String
    ): Response<ApiResponse<List<DamageItem>>>

    @GET("api/customers/{customerId}/credit-balance")
    suspend fun getCustomerCreditBalance(
        @Path("customerId") customerId: String
    ): Response<CreditBalance>

    @POST("api/credits/issue")
    suspend fun issueCredit(@Body request: IssueCreditRequest): Response<IssueCreditResponse>

    @POST("api/orders/batch/{batchId}/retry")
    suspend fun retryBatchSync(
        @Path("batchId") batchId: String
    ): Response<RetryBatchResponse>

    // ── Pre-Orders ──

    @POST("api/preorders")
    suspend fun createPreOrder(@Body request: PreOrderRequest): Response<PreOrderResponse>

    @GET("api/preorders")
    suspend fun listPreOrders(
        @Query("status") status: String? = null,
        @Query("customer_id") customerId: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 30
    ): Response<ApiResponse<List<PreOrderDto>>>

    @GET("api/preorders/{id}")
    suspend fun getPreOrder(@Path("id") id: Int): Response<ApiResponse<PreOrderDto>>

    @PUT("api/preorders/{id}")
    suspend fun updatePreOrder(@Path("id") id: Int, @Body request: PreOrderRequest): Response<Unit>

    @PUT("api/preorders/{id}")
    suspend fun updatePreOrderStatus(@Path("id") id: Int, @Body request: UpdatePreOrderStatusRequest): Response<Unit>

    @DELETE("api/preorders/{id}")
    suspend fun deletePreOrder(@Path("id") id: Int): Response<Unit>

    @POST("api/preorders/{id}/convert")
    suspend fun convertPreOrder(@Path("id") id: Int, @Body request: ConvertPreOrderRequest): Response<ConvertPreOrderResponse>

    // ── Customer History ──

    @GET("api/users/salespersons")
    suspend fun listSalespersons(): Response<ApiResponse<List<UserBrief>>>

    @GET("api/customers/{customerId}/orders")
    suspend fun getCustomerOrders(
        @Path("customerId") customerId: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<CustomerBatchSummary>>>

    // ── Módulo Almacén (rutas de entrega + manifiesto de carga) ──

    @GET("api/routes")
    suspend fun listRoutes(
        @Query("date") date: String? = null,
        @Query("driver_user_id") driverUserId: Int? = null
    ): Response<ApiResponse<List<RouteDto>>>

    @POST("api/routes")
    suspend fun createRoute(@Body request: RouteRequest): Response<CreateRouteResponse>

    @GET("api/routes/{id}")
    suspend fun getRoute(@Path("id") id: Int): Response<ApiResponse<RouteDetailDto>>

    @PUT("api/routes/{id}")
    suspend fun updateRoute(@Path("id") id: Int, @Body request: RouteRequest): Response<Unit>

    @GET("api/routes/available")
    suspend fun listAvailableStops(@Query("date") date: String? = null): Response<AvailableStopsResponse>

    @POST("api/routes/{id}/stops")
    suspend fun addRouteStop(@Path("id") id: Int, @Body request: AddStopRequest): Response<AddStopResponse>

    @DELETE("api/routes/{id}/stops/{stopId}")
    suspend fun removeRouteStop(@Path("id") id: Int, @Path("stopId") stopId: Int): Response<Unit>

    @PUT("api/routes/{id}/stops/reorder")
    suspend fun reorderRouteStops(@Path("id") id: Int, @Body request: ReorderStopsRequest): Response<Unit>

    @PUT("api/routes/{id}/stops/{stopId}/status")
    suspend fun updateStopStatus(@Path("id") id: Int, @Path("stopId") stopId: Int, @Body request: UpdateStopStatusRequest): Response<UpdateStopStatusResponse>

    @POST("api/routes/{id}/items")
    suspend fun addRouteItem(@Path("id") id: Int, @Body request: AddRouteItemRequest): Response<RouteItemResponse>

    @DELETE("api/routes/{id}/items/{itemId}")
    suspend fun removeRouteItem(@Path("id") id: Int, @Path("itemId") itemId: Int): Response<Unit>

    // ── Devoluciones (Fase 112) ──

    @GET("api/routes/{id}/returns/expected")
    suspend fun getExpectedReturns(@Path("id") id: Int): Response<ApiResponse<List<RouteReturnExpectedDto>>>

    @POST("api/routes/{id}/returns")
    suspend fun createReturns(@Path("id") id: Int, @Body request: CreateReturnsRequest): Response<CreateReturnsResponse>

    @GET("api/routes/{id}/returns")
    suspend fun listReturns(@Path("id") id: Int): Response<ApiResponse<List<RouteReturnDto>>>

    // ── Almacén: recepción, FIFO, sub-inventario, liquidación (Fase 112) ──

    @GET("api/warehouse/warehouses")
    suspend fun listWarehouses(): Response<ApiResponse<List<WarehouseDto>>>

    @POST("api/warehouse/receipts")
    suspend fun createReceipt(@Body request: CreateReceiptRequest): Response<CreateReceiptResponse>

    @GET("api/warehouse/lots")
    suspend fun listLots(
        @Query("warehouse_id") warehouseId: Int? = null,
        @Query("product_id") productId: Int? = null,
        @Query("status") status: String? = null
    ): Response<ApiResponse<List<ProductLotDto>>>

    @GET("api/warehouse/lots/suggest")
    suspend fun suggestLots(
        @Query("product_id") productId: Int,
        @Query("quantity") quantity: Double,
        @Query("warehouse_id") warehouseId: Int? = null
    ): Response<ApiResponse<List<FifoAllocationDto>>>

    @GET("api/warehouse/lots/available-products")
    suspend fun listAvailableProducts(@Query("warehouse_id") warehouseId: Int? = null): Response<ApiResponse<List<ProductDto>>>

    @POST("api/warehouse/lots/{id}/condition")
    suspend fun setLotCondition(@Path("id") id: Int, @Body request: LotConditionRequest): Response<Unit>

    @PUT("api/warehouse/lots/{id}")
    suspend fun updateLot(@Path("id") id: Int, @Body request: UpdateLotRequest): Response<Unit>

    @GET("api/warehouse/movements")
    suspend fun listMovements(
        @Query("warehouse_id") warehouseId: Int? = null,
        @Query("date") date: String? = null,
        @Query("settled") settled: Boolean? = null
    ): Response<ApiResponse<List<InventoryMovementDto>>>

    // La liquidación diaria (preview/confirm) pasó a ser admin-only en la
    // webapp — Android ya no la llama (ver WarehouseActivity/SettlementActivity
    // removidos a pedido del usuario).
}
