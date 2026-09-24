package app.mealmapper.ui.common

/** 12 -> "12", 2.345 -> "2.3". Used for grams and kcal on screen. */
fun Double.fmt(): String = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)

/** Rounds kcal to a whole number: labels and Google Health both show whole calories. */
fun Double.kcal(): String = Math.round(this).toString()
