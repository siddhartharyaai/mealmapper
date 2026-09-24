package app.mealmapper.domain

/** Whether nutrition values are per 100 g (solids) or per 100 ml (drinks, milk). */
enum class Basis(val unit: String) { GRAMS("g"), MILLILITRES("ml") }

/** A packaged product, with nutrition per 100 g or per 100 ml as printed on Indian (FSSAI) labels. */
data class FoodProduct(
    val barcode: String,
    val name: String,
    val brand: String?,
    val per100: Nutrients,
    val basis: Basis,
    val servingSize: Double?,
    val packSize: Double?,
    /** Extra portion choices from the databank: "1 roti · 40 g", "1 katori · 150 g". */
    val portions: List<PortionOption> = emptyList(),
)

data class PortionOption(val label: String, val amount: Double)

/**
 * Quick portion choices for the Review screen. Serving and pack come from the product.
 * Drinks also get Indian household measures: a cup of chai (150 ml) and a glass (250 ml).
 */
fun portionOptions(product: FoodProduct): List<PortionOption> {
    val unit = product.basis.unit
    val options = product.portions.map { if (Regex("\\d (g|ml)\\b").containsMatchIn(it.label)) it else it.copy(label = "${it.label} · ${it.amount.clean()} $unit") }.toMutableList()
    product.servingSize?.let { options += PortionOption("1 serving · ${it.clean()} $unit", it) }
    options += PortionOption("100 $unit", 100.0)
    if (product.basis == Basis.MILLILITRES) {
        options += PortionOption("1 cup · 150 ml", 150.0)
        options += PortionOption("1 glass · 250 ml", 250.0)
    }
    product.packSize?.let { options += PortionOption("Whole pack · ${it.clean()} $unit", it) }
    return options.distinctBy { it.amount }
}

/** The default portion: one serving if the label gives one, otherwise 100 g/ml. */
fun defaultPortion(product: FoodProduct): Double = product.servingSize ?: 100.0

fun FoodProduct.nutrientsFor(amount: Double): Nutrients = per100.scaled(amount / 100.0)

private fun Double.clean(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)
