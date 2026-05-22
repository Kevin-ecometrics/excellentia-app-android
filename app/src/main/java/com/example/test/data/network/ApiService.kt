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

    @GET("api/settings")
    suspend fun getCompanySettings(): Response<CompanySettingsResponse>

    @PUT("api/auth/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Response<Unit>

    @GET("api/products")
    suspend fun searchProducts(
        @Query("search") search: String,
        @Query("limit") limit: Int = 20
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

    @DELETE("api/preorders/{id}")
    suspend fun deletePreOrder(@Path("id") id: Int): Response<Unit>

    @POST("api/preorders/{id}/convert")
    suspend fun convertPreOrder(@Path("id") id: Int, @Body request: ConvertPreOrderRequest): Response<ConvertPreOrderResponse>

    // ── Customer History ──

    @GET("api/customers/{customerId}/orders")
    suspend fun getCustomerOrders(
        @Path("customerId") customerId: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<List<CustomerBatchSummary>>>
}
