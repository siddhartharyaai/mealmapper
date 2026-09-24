package app.mealmapper.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeTest {
    @Test fun validEan13() = assertTrue(isValidBarcode("4006381333931"))
    @Test fun validIndianEan13() = assertTrue(isValidBarcode("8901063010031"))
    @Test fun validEan8() = assertTrue(isValidBarcode("96385074"))
    @Test fun validUpcA() = assertTrue(isValidBarcode("036000291452"))
    @Test fun typoFailsCheckDigit() = assertFalse(isValidBarcode("8901063010032"))
    @Test fun lettersRejected() = assertFalse(isValidBarcode("89010630100A1"))
    @Test fun wrongLengthRejected() = assertFalse(isValidBarcode("12345"))
    @Test fun indianPrefix() = assertTrue(isIndianBarcode("8901063010031"))
    @Test fun nonIndianPrefix() = assertFalse(isIndianBarcode("4006381333931"))
}
