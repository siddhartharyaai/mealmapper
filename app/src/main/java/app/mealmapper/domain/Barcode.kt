package app.mealmapper.domain

/**
 * Checks a typed or scanned retail barcode (EAN-8, UPC-A, EAN-13, GTIN-14).
 * The check digit catches most typing mistakes before we call the network.
 * Indian products use EAN-13 starting with 890.
 */
fun isValidBarcode(code: String): Boolean {
    if (code.length !in VALID_LENGTHS || !code.all(Char::isDigit)) return false
    val digits = code.map { it - '0' }
    val body = digits.dropLast(1).reversed()
    // From the right, excluding the check digit, weights alternate 3, 1, 3, 1...
    val sum = body.withIndex().sumOf { (i, d) -> if (i % 2 == 0) d * 3 else d }
    val check = (10 - sum % 10) % 10
    return check == digits.last()
}

fun isIndianBarcode(code: String): Boolean = code.length == 13 && code.startsWith("890")

private val VALID_LENGTHS = setOf(8, 12, 13, 14)
