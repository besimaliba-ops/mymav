package com.mymav.glasses.api

import org.json.JSONObject

/**
 * Shared vision analysis for Food / Shop / Identify. Uses [MymavVision] (same AI keys).
 * Every result carries an honest [source] label (phone CameraX vs glasses/simulator feed).
 *
 * Product identity is for lookup queries only — never invent live store prices here.
 */
object MymavScanVision {

    data class FoodAnalysis(
        val label: String,
        val kcal: Int?,
        val proteinG: Int?,
        val carbsG: Int?,
        val fatG: Int?,
        val healthScore: Int?,
        val uncertainty: String,
        val notes: String,
        val source: String,
        val raw: String,
        /** meal | packaged_food | drink | supplement | not_food */
        val kind: String = "meal",
        /** Visible portion, e.g. "1 bowl (~350 g)". */
        val portion: String = "",
    ) {
        /** Supplements, medicines and objects are never logged as a 0 kcal meal. */
        val isFood: Boolean get() = kind == "meal" || kind == "packaged_food" || kind == "drink"
    }

    data class ProductAnalysis(
        val title: String,
        val brand: String,
        val productName: String,
        val variant: String,
        val size: String,
        val detail: String,
        /** Visible shelf/tag price only — never a remembered/LLM quote. */
        val priceHint: String,
        val barcode: String,
        val labelText: String,
        val searchQuery: String,
        val matchQuality: String,
        val needsCloserLook: Boolean,
        val recaptureHint: String,
        val uncertainty: String,
        val source: String,
        val raw: String,
    )

    data class IdentifyAnalysis(
        val label: String,
        val confidence: Float,
        val detail: String,
        val brand: String = "",
        val productName: String = "",
        val variant: String = "",
        val size: String = "",
        val barcode: String = "",
        val labelText: String = "",
        val searchQuery: String = "",
        val confirmed: Boolean = false,
        val needsCloserLook: Boolean = false,
        val recaptureHint: String = "",
        val source: String,
        val raw: String,
    )

    fun analyseFood(frame: MymavVision.Frame, dietaryNotes: String): FoodAnalysis {
        val diet = dietaryNotes.ifBlank { "none stated" }
        val question =
            "Estimate the meal or food in this photo for a glasses HUD. " +
                "User dietary prefs: $diet. " +
                "First decide kind: meal, packaged_food, drink, supplement (vitamins, pills, capsules, powders sold " +
                "as supplements), or not_food (medicine, objects, packaging with no food). For supplement or not_food " +
                "set kcal, proteinG, carbsG, fatG and healthScore to null — never 0. " +
                "For food, estimate the visible portion (e.g. \"1 bowl (~350 g)\") and real numbers for kcal and " +
                "macros for that portion; healthScore rates nutritional quality 0-100. " +
                "If unsure, say so — never invent a barcode. " +
                "Respond JSON only: " +
                "{\"kind\":\"meal\",\"label\":\"short name\",\"portion\":\"1 plate (~300 g)\",\"kcal\":<int>," +
                "\"proteinG\":<int>,\"carbsG\":<int>,\"fatG\":<int>,\"healthScore\":<int 0-100>," +
                "\"uncertainty\":\"one short phrase\",\"notes\":\"diet flags or empty\"}"
        val res = MymavVision.describe(question, frame)
        if (res.kind == AssistantLog.Kind.Error) {
            return FoodAnalysis(
                label = "Analysis failed",
                kcal = null, proteinG = null, carbsG = null, fatG = null, healthScore = null,
                uncertainty = res.text.take(80),
                notes = "",
                source = frame.source,
                raw = res.text,
            )
        }
        val json = extractJson(res.text)
        if (json != null) {
            val kind = json.optString("kind", "meal").trim().lowercase().ifBlank { "meal" }
            val food = kind == "meal" || kind == "packaged_food" || kind == "drink"
            val numbers = listOf("kcal", "proteinG", "carbsG", "fatG").map { json.optIntOrNull(it) }
            // An all-zero meal is the model echoing a template, not an estimate; show "?" instead of logging 0 kcal.
            val echoed = numbers.all { it == null || it == 0 }
            val macro: (String) -> Int? = { key -> json.optIntOrNull(key)?.takeIf { food && !echoed } }
            return FoodAnalysis(
                kind = kind,
                portion = json.optString("portion", "").takeIf { food }.orEmpty(),
                label = json.optString("label", "Food").ifBlank { "Food" },
                kcal = macro("kcal"),
                proteinG = macro("proteinG"),
                carbsG = macro("carbsG"),
                fatG = macro("fatG"),
                healthScore = json.optIntOrNull("healthScore")?.coerceIn(0, 100)?.takeIf { food && !echoed },
                uncertainty = json.optString("uncertainty", "Estimate only").ifBlank { "Estimate only" },
                notes = json.optString("notes", ""),
                source = frame.source,
                raw = res.text,
            )
        }
        return FoodAnalysis(
            label = res.text.take(48).ifBlank { "Food" },
            kcal = null, proteinG = null, carbsG = null, fatG = null, healthScore = null,
            uncertainty = "Could not parse structured macros — showing description",
            notes = "",
            source = frame.source,
            raw = res.text,
        )
    }

    fun analyseProduct(frame: MymavVision.Frame): ProductAnalysis {
        val question =
            "Identify the retail product for a shopping HUD. Read brand, exact product name, " +
                "variant (e.g. EDT/EDP, colour, flavour), and size from the FRONT LABEL and any barcode digits. " +
                "Do NOT guess from bottle/box shape alone. If the label is unreadable, set needsCloserLook=true " +
                "and recaptureHint to \"Move closer to front label\" or \"Show barcode\". " +
                "priceHint ONLY if a price tag is literally visible — never invent a store price. " +
                "matchQuality: exact|possible|ambiguous. " +
                "Respond JSON only: " +
                "{\"brand\":\"\",\"productName\":\"\",\"variant\":\"\",\"size\":\"\",\"title\":\"display name\"," +
                "\"detail\":\"short facts\",\"priceHint\":\"visible price or empty\",\"barcode\":\"digits or empty\"," +
                "\"labelText\":\"OCR of front label\",\"searchQuery\":\"best Google Shopping query\"," +
                "\"matchQuality\":\"exact|possible|ambiguous\",\"needsCloserLook\":false," +
                "\"recaptureHint\":\"\",\"uncertainty\":\"one short phrase\"}"
        val res = MymavVision.describe(question, frame)
        if (res.kind == AssistantLog.Kind.Error) {
            return ProductAnalysis(
                title = "Analysis failed",
                brand = "", productName = "", variant = "", size = "",
                detail = res.text.take(80),
                priceHint = "",
                barcode = "", labelText = "", searchQuery = "",
                matchQuality = "ambiguous",
                needsCloserLook = true,
                recaptureHint = "Retry capture",
                uncertainty = res.text.take(60),
                source = frame.source,
                raw = res.text,
            )
        }
        val json = extractJson(res.text)
        if (json != null) {
            val brand = json.optString("brand", "").trim()
            val productName = json.optString("productName", "").trim()
            val variant = json.optString("variant", "").trim()
            val size = json.optString("size", "").trim()
            val title = json.optString("title").ifBlank {
                listOf(brand, productName, variant, size).filter { it.isNotBlank() }.joinToString(" ")
                    .ifBlank { "Product" }
            }
            val search = json.optString("searchQuery").ifBlank {
                listOf(brand, productName, variant, size).filter { it.isNotBlank() }.joinToString(" ")
            }
            return ProductAnalysis(
                title = title,
                brand = brand,
                productName = productName,
                variant = variant,
                size = size,
                detail = json.optString("detail", ""),
                priceHint = json.optString("priceHint", ""),
                barcode = json.optString("barcode", "").filter { it.isDigit() },
                labelText = json.optString("labelText", ""),
                searchQuery = search,
                matchQuality = json.optString("matchQuality", "possible").lowercase().ifBlank { "possible" },
                needsCloserLook = json.optBoolean("needsCloserLook", false),
                recaptureHint = json.optString("recaptureHint", ""),
                uncertainty = json.optString("uncertainty", "Estimate only").ifBlank { "Estimate only" },
                source = frame.source,
                raw = res.text,
            )
        }
        return ProductAnalysis(
            title = res.text.take(48).ifBlank { "Product" },
            brand = "", productName = "", variant = "", size = "",
            detail = "",
            priceHint = "",
            barcode = "", labelText = "", searchQuery = res.text.take(48),
            matchQuality = "ambiguous",
            needsCloserLook = true,
            recaptureHint = "Move closer to front label",
            uncertainty = "Could not parse structured product fields",
            source = frame.source,
            raw = res.text,
        )
    }

    fun analyseIdentify(frame: MymavVision.Frame): IdentifyAnalysis {
        val question =
            "Identify the main object or product. For fragrance/cosmetics/packaged goods: " +
                "read brand, fragrance/product name, concentration (EDT/EDP), and size from the FRONT LABEL " +
                "and barcode digits when visible. Do NOT guess from bottle shape. " +
                "If label is unclear, set needsCloserLook=true and recaptureHint " +
                "(\"Move closer to front label\" or \"Show barcode\") while keeping any partial brand. " +
                "confirmed=true only when brand+name are clearly readable. " +
                "Respond JSON only: " +
                "{\"label\":\"short display name\",\"confidence\":0.0,\"detail\":\"one short fact\"," +
                "\"brand\":\"\",\"productName\":\"\",\"variant\":\"\",\"size\":\"\",\"barcode\":\"\"," +
                "\"labelText\":\"\",\"searchQuery\":\"shopping query if product\",\"confirmed\":false," +
                "\"needsCloserLook\":false,\"recaptureHint\":\"\"}. confidence 0-1."
        val res = MymavVision.describe(question, frame)
        if (res.kind == AssistantLog.Kind.Error) {
            return IdentifyAnalysis("Unavailable", 0f, res.text.take(80), source = frame.source, raw = res.text)
        }
        val json = extractJson(res.text)
        if (json != null) {
            val brand = json.optString("brand", "").trim()
            val productName = json.optString("productName", "").trim()
            val variant = json.optString("variant", "").trim()
            val size = json.optString("size", "").trim()
            val label = json.optString("label").ifBlank {
                listOf(brand, productName, variant, size).filter { it.isNotBlank() }.joinToString(" ")
                    .ifBlank { "Object" }
            }
            val search = json.optString("searchQuery").ifBlank {
                listOf(brand, productName, variant, size).filter { it.isNotBlank() }.joinToString(" ")
            }
            return IdentifyAnalysis(
                label = label,
                confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
                detail = json.optString("detail", "").ifBlank { "From ${frame.source}" },
                brand = brand,
                productName = productName,
                variant = variant,
                size = size,
                barcode = json.optString("barcode", "").filter { it.isDigit() },
                labelText = json.optString("labelText", ""),
                searchQuery = search,
                confirmed = json.optBoolean("confirmed", false),
                needsCloserLook = json.optBoolean("needsCloserLook", false),
                recaptureHint = json.optString("recaptureHint", ""),
                source = frame.source,
                raw = res.text,
            )
        }
        return IdentifyAnalysis(
            label = res.text.take(40).ifBlank { "Object" },
            confidence = 0.55f,
            detail = "From ${frame.source}",
            source = frame.source,
            raw = res.text,
        )
    }

    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getInt(key) }.getOrNull()
    }
}
