package com.example.test

import android.content.Context
import com.example.test.data.CustomerCreditsResponse
import com.example.test.data.local.SecurePreferences
import com.example.test.data.network.RetrofitClient
import com.example.test.data.print.PrintService

// Ticket 2 (2026-10-27) — imprime la nota de crédito de un crédito 'cr…'.
// Lo usan CustomerCreditsActivity (reimprimir, ya tiene el detalle cargado) e
// IssueCreditActivity (al guardar: pide el detalle al servidor para imprimir
// lo mismo que se reimprime después — productos, motivo, operador y saldo).
// Devuelve el mensaje de error listo para mostrar, o null si imprimió.
object CreditNotePrinter {

    suspend fun print(context: Context, statement: CustomerCreditsResponse, batchId: String): String? {
        val address = SecurePreferences(context).getPrinterAddress()
            ?: return context.getString(R.string.error_no_printer_for_credit_note)
        val credit = statement.movements.firstOrNull { it.batchId == batchId }
            ?: return context.getString(R.string.error_credit_note_not_found)
        return PrintService.printCreditNote(context, address, statement.customerName, credit, statement.summary.balance)
            .fold(
                onSuccess = { null },
                onFailure = { e -> context.getString(R.string.error_print_generic, e.localizedMessage ?: context.getString(R.string.error_no_connection)) }
            )
    }

    suspend fun fetchAndPrint(context: Context, customerId: String, batchId: String): String? {
        val statement = try {
            val resp = RetrofitClient.getApi().getCustomerCredits(customerId)
            if (!resp.isSuccessful) return context.getString(R.string.msg_server_error, resp.code().toString())
            resp.body() ?: return context.getString(R.string.error_credit_note_not_found)
        } catch (e: Exception) {
            return e.localizedMessage ?: context.getString(R.string.error_no_connection)
        }
        return print(context, statement, batchId)
    }
}
