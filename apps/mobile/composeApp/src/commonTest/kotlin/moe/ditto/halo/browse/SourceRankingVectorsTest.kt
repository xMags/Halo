package moe.ditto.halo.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import moe.ditto.halo.api.HaloJson
import moe.ditto.halo.vectors.RuleVectors

/**
 * The shared ranking vectors (`packages/core/test/vectors/source-ranking.json`),
 * the cases `@halo/core`'s `sourceRanking.test.ts` runs: every ranking case
 * and every line-speed case.
 */
class SourceRankingVectorsTest {
    private val vectors = HaloJson.parseToJsonElement(RuleVectors.sourceRanking).jsonObject

    private fun quality(label: String?): SourceQuality? =
        label?.let { wanted -> checkNotNull(SourceQuality.entries.firstOrNull { it.label == wanted }) { "unknown quality $wanted" } }

    private fun source(row: JsonObject): RankableSource {
        val cached = row.getValue("cached").let { if (it is JsonNull) null else it.jsonPrimitive.booleanOrNull }
        val qualityLabel = row.getValue("quality").let { if (it is JsonNull) null else it.jsonPrimitive.content }
        val size = row.getValue("sizeBytes").let { if (it is JsonNull) null else it.jsonPrimitive.long }
        return RankableSource(cached, quality(qualityLabel), size, row.getValue("neededMbps").jsonPrimitive.double)
    }

    @Test
    fun everyRankingCaseOrdersAndFitsAsTheSharedRule() {
        val cases = vectors.getValue("ranking").jsonArray
        assertTrue(cases.size >= 20)
        for (case in cases.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val device = case.getValue("device").jsonObject.let {
                RankingDevice(it.getValue("lineMbps").jsonPrimitive.double, it.getValue("screenHeight").jsonPrimitive.double)
            }
            val rows = case.getValue("sources").jsonArray.map { it.jsonObject }
            val ids = rows.map { it.getValue("id").jsonPrimitive.content }
            val ranks = rankSources(rows.map(::source), device)

            val order = ids.indices.sortedBy { ranks[it].rank }.map { ids[it] }
            assertEquals(case.getValue("order").jsonArray.map { it.jsonPrimitive.content }, order, name)
            // Ranks are dense: 0..n-1, each once.
            assertEquals(ids.indices.map { it.toLong() }, ranks.map { it.rank }.sorted(), name)
            val fits = case.getValue("fit").jsonObject
            ids.forEachIndexed { index, id ->
                assertEquals(fits.getValue(id).jsonPrimitive.content, ranks[index].fit.wire, "$name: $id")
            }
        }
    }

    @Test
    fun everyLineSpeedCaseFoldsAsTheSharedRule() {
        val cases = vectors.getValue("lineSpeed").jsonArray
        assertTrue(cases.size >= 15)
        for (case in cases.map { it.jsonObject }) {
            val actual = foldLineSpeed(
                case.getValue("previous").jsonPrimitive.double,
                case.getValue("sessionPeak").jsonPrimitive.double,
                case.getValue("stalled").jsonPrimitive.boolean,
            )
            assertEquals(case.getValue("expect").jsonPrimitive.double, actual, case.getValue("name").jsonPrimitive.content)
        }
    }
}
