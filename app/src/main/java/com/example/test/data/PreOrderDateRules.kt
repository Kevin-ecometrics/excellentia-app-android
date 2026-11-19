package com.example.test.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// Límite de las 2 pm para la fecha de entrega de una pre-orden (2026-11-19, pedido
// del cliente). Misma regla que el backend (services/preOrderRules.ts, que es quien
// manda): antes de las 2:00 pm hora de San Diego la entrega más temprana es mañana;
// desde las 2:00 pm, pasado mañana. Nunca el mismo día. Aplica a todos los roles,
// admin incluido. Zona del negocio, NO la del teléfono.
object PreOrderDateRules {
    private val BUSINESS_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    private const val CUTOFF_HOUR = 14

    fun earliestDeliveryDate(now: Instant = Instant.now()): LocalDate {
        val local = ZonedDateTime.ofInstant(now, BUSINESS_ZONE)
        return local.toLocalDate().plusDays(if (local.hour >= CUTOFF_HOUR) 2 else 1)
    }

    // `date` = 'YYYY-MM-DD' (lo que guarda la app en selectedDate).
    fun isAllowed(date: String, now: Instant = Instant.now()): Boolean =
        try { !LocalDate.parse(date.take(10)).isBefore(earliestDeliveryDate(now)) }
        catch (_: Exception) { false }

    // minDate del DatePicker: medianoche de esa fecha en la zona del teléfono (el
    // DatePicker trabaja en la zona del teléfono).
    fun minDateMillis(): Long =
        earliestDeliveryDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
