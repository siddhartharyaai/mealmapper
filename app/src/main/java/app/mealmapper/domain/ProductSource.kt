package app.mealmapper.domain

/** Where a product's numbers came from. Shown on Review so the user knows how much to trust them. */
sealed interface ProductSource {
    val label: String

    data object OpenFoodFacts : ProductSource {
        override val label = "Open Food Facts · check against the pack"
    }

    /**
     * Web values. [cited] is true when [sites] are the pages Google Search returned (grounding citations);
     * false when Google ran the search but attached no citations and the sites are the ones the answer named.
     */
    data class Web(val sites: List<String>, val agreeing: Int, val cited: Boolean = true) : ProductSource {
        override val label: String
            get() = if (!cited) {
                "Web · Google Search · " + (sites.take(2).joinToString().ifEmpty { "pages not linked" }) + " · check the pack"
            } else if (agreeing >= 2 && sites.size >= 2) {
                "Web · $agreeing sources agree · ${sites.take(3).joinToString()}"
            } else {
                "Web · 1 source · check the pack · ${sites.take(2).joinToString()}"
            }
    }

    /** Offline databank row (tools/fooddb). */
    data class Databank(val sourceName: String, val fryCorrected: Boolean) : ProductSource {
        override val label: String
            get() = "Databank · $sourceName" + if (fryCorrected) " · frying oil corrected to oil absorbed" else ""
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
