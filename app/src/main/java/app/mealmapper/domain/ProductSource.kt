package app.mealmapper.domain

/** Where a product's numbers came from. Shown on Review so the user knows how much to trust them. */
sealed interface ProductSource {
    val label: String

    data object OpenFoodFacts : ProductSource {
        override val label = "Open Food Facts · check against the pack"
    }

    /** Web values with the pages Google Search actually returned (grounding), not pages the model named. */
    data class Web(val sites: List<String>, val agreeing: Int) : ProductSource {
        override val label: String
            get() = if (agreeing >= 2 && sites.size >= 2) {
                "Web · $agreeing sources agree · ${sites.take(3).joinToString()}"
            } else {
                "Web · 1 source · check the pack · ${sites.take(2).joinToString()}"
            }
    }

    data object LabelPhoto : ProductSource {
        override val label = "Read from your label photo · check the highlighted values"
    }

    data object Manual : ProductSource {
        override val label = "Your label values"
    }

    data object Saved : ProductSource {
        override val label = "Saved from your last confirmed entry"
    }
}
