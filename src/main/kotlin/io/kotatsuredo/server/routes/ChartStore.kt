package io.kotatsuredo.server.routes

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/** A ranking as it was last built, and when. */
data class StoredRanking(val generatedAt: Long, val works: List<SimilarWorkDto>)

/** Where [Charts] keeps its rankings between restarts. Both calls may throw; the caller copes. */
interface ChartStore {

	fun load(): Map<ChartWindow, StoredRanking>

	fun save(window: ChartWindow, ranking: StoredRanking)
}

/** Keeps the rankings in the `chart_ranking` table, one row per window. */
class DatabaseChartStore(private val dataSource: DataSource) : ChartStore {

	override fun load(): Map<ChartWindow, StoredRanking> = dataSource.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery("SELECT window_name, generated_at, ranking FROM chart_ranking").use { rows ->
				buildMap {
					while (rows.next()) {
						// A row for a window this version does not know is skipped, not an error.
						val window = ChartWindow.parse(rows.getString("window_name")) ?: continue
						put(
							window,
							StoredRanking(
								generatedAt = rows.getTimestamp("generated_at").time,
								works = json.decodeFromString(serializer, rows.getString("ranking")),
							),
						)
					}
				}
			}
		}
	}

	override fun save(window: ChartWindow, ranking: StoredRanking) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO chart_ranking (window_name, generated_at, ranking) VALUES (?, ?, ?)
				ON CONFLICT (window_name) DO UPDATE
				SET generated_at = EXCLUDED.generated_at, ranking = EXCLUDED.ranking
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, window.wire)
				statement.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(ranking.generatedAt)))
				statement.setString(3, json.encodeToString(serializer, ranking.works))
				statement.executeUpdate()
			}
		}
	}

	private companion object {
		val json = Json { ignoreUnknownKeys = true }
		val serializer = ListSerializer(SimilarWorkDto.serializer())
	}
}
