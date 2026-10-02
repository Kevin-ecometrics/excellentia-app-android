package com.example.test

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.example.test.data.PreOrderItem
import com.example.test.data.ProductDto
import com.example.test.data.isBucketUnit
import com.example.test.data.isCaseUnitType
import com.example.test.data.lineTotal
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// Fase 146 — diálogo de cantidad de una pre-orden: "¿Cuántas cajas?" (Lbs y
// Case/Unit) o "¿Cuántos baldes?" (Bucket). Es el mismo en CreatePreOrderActivity
// (al crear) y PreOrderDetailActivity (al editar), para que todos los tipos de
// producto se manejen igual en las pantallas de pre-orden: el vendedor solo dice
// cuántas, nunca captura peso en un stepper aparte.
const val MAX_PREORDER_COUNT = 200

fun Context.showCountDialog(productName: String, unit: String?, initial: Int = 1, onConfirm: (Int) -> Unit) {
    val bucket = isBucketUnit(unit)
    val density = resources.displayMetrics.density
    val input = EditText(this).apply {
        hint = getString(if (bucket) R.string.preorder_buckets_hint else R.string.preorder_boxes_hint)
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(initial.toString())
        setSelectAllOnFocus(true)
    }
    val container = LinearLayout(this).apply {
        setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(getString(if (bucket) R.string.preorder_buckets_title else R.string.preorder_boxes_title))
        .setMessage(productName)
        .setView(container)
        .setPositiveButton(getString(R.string.btn_confirm), null)
        .setNegativeButton(getString(R.string.btn_cancel), null)
        .create()
    // El click del positivo se asigna después de show() para poder validar sin
    // que el diálogo se cierre solo con un valor inválido.
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val count = input.text.toString().trim().toIntOrNull()
            if (count == null || count < 1 || count > MAX_PREORDER_COUNT) {
                input.error = getString(R.string.preorder_count_invalid)
            } else {
                dialog.dismiss()
                onConfirm(count)
            }
        }
    }
    dialog.show()
}

// Ítem de pre-orden Case/Unit/Bucket con la cantidad elegida en el diálogo. El
// precio es el del catálogo (`price` es por UNIDAD, ver Fase 122) y el total pasa
// por lineTotal(), igual que lo que armaba el stepper de ProductDetailActivity;
// al convertir se vuelve a consultar el precio fresco. caseQty: tamaño de caja
// (products.qty cuando el catálogo no trae caseQty, mismo fallback que el stepper).
fun buildCountedPreOrderItem(
    barcode: String,
    product: ProductDto,
    count: Int,
    unit: String? = product.unit,
    caseQty: Int? = null
): PreOrderItem {
    val finalCaseQty = if (isCaseUnitType(unit)) {
        caseQty?.takeIf { it > 0 } ?: product.caseQty?.takeIf { it > 0 } ?: product.qty.takeIf { it > 0 }
    } else null
    val quantity = count.toDouble()
    return PreOrderItem(
        barcode = barcode,
        productName = product.name,
        price = product.price,
        quantity = quantity,
        total = lineTotal(product.price, quantity, unit, finalCaseQty),
        unit = unit,
        caseQty = finalCaseQty,
        shortName = product.shortName
    )
}
