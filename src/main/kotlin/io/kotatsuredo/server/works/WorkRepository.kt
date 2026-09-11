package io.kotatsuredo.server.works

import java.sql.Statement
import java.sql.Types
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.sql.DataSource

data class TitleToStore(
	val raw: String,
	val kind: String,
	val lang: String? = null,
	val weight: Double = 1.0,
)

data class WorkSeed(
	val canonicalTitle: String,
	val year: Int?,
	val contentType: String?,
	val nsfw: Boolean,
	val titles: List<TitleToStore>,
	val externalIds: Map<String, String>,
)

class WorkRepository(private val dataSource: DataSource) {

	/**
	 * Creates or updates a work identified by an external id, and returns its id.
	 *
	 * Keyed on the external id rather than the title so the importer is **idempotent**: re-running it
	 * updates the same rows instead of duplicating the catalogue, which matters because it will be
	 * re-run - on a schedule, and by hand whenever something goes wrong.
	 */
	fun upsertSeeded(provider: String, externalId: String, seed: WorkSeed): Long =
		dataSource.connection.use { connection ->
			connection.autoCommit = false
			try {
				val existing = connection.prepareStatement(
					"SELECT work_id FROM work_external_id WHERE provider = ? AND external_id = ?",
				).use { statement ->
					statement.setString(1, provider)
					statement.setString(2, externalId)
					statement.executeQuery().use { if (it.next()) it.getLong(1) else null }
				}

				val workId = existing?.also { id ->
					connection.prepareStatement(
						"UPDATE work SET canonical_title = ?, year = ?, content_type = ?, nsfw = ? WHERE id = ?",
					).use { statement ->
						statement.setString(1, seed.canonicalTitle)
						seed.year?.let { statement.setShort(2, it.toShort()) } ?: statement.setNull(2, Types.SMALLINT)
						statement.setString(3, seed.contentType)
						statement.setBoolean(4, seed.nsfw)
						statement.setLong(5, id)
						statement.executeUpdate()
					}
				} ?: connection.prepareStatement(
					"INSERT INTO work (canonical_title, year, content_type, nsfw) VALUES (?, ?, ?, ?)",
					Statement.RETURN_GENERATED_KEYS,
				).use { statement ->
					statement.setString(1, seed.canonicalTitle)
					seed.year?.let { statement.setShort(2, it.toShort()) } ?: statement.setNull(2, Types.SMALLINT)
					statement.setString(3, seed.contentType)
					statement.setBoolean(4, seed.nsfw)
					statement.executeUpdate()
					statement.generatedKeys.use { keys ->
						keys.next()
						keys.getLong(1)
					}
				}

				storeTitles(connection, workId, seed.titles)
				storeExternalIds(connection, workId, seed.externalIds)

				connection.commit()
				workId
			} catch (e: Exception) {
				connection.rollback()
				throw e
			}
		}

	/**
	 * Every title is stored under every key the normalizer produces, so a source using a different
	 * romanization still finds the work with an exact lookup instead of a fuzzy one.
	 */
	private fun storeTitles(connection: java.sql.Connection, workId: Long, titles: List<TitleToStore>) {
		connection.prepareStatement(
			"""
			INSERT INTO work_title (work_id, title_raw, title_norm, lang, kind, weight)
			VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT (work_id, title_norm, kind) DO UPDATE SET
				weight = GREATEST(work_title.weight, EXCLUDED.weight)
			""".trimIndent(),
		).use { statement ->
			titles.forEach { title ->
				TitleNormalizer.keys(title.raw).forEach { key ->
					statement.setLong(1, workId)
					statement.setString(2, title.raw)
					statement.setString(3, key)
					statement.setString(4, title.lang)
					statement.setString(5, title.kind)
					statement.setDouble(6, title.weight)
					statement.addBatch()
				}
			}
			statement.executeBatch()
		}
	}

	private fun storeExternalIds(connection: java.sql.Connection, workId: Long, ids: Map<String, String>) {
		if (ids.isEmpty()) return
		connection.prepareStatement(
			"""
			INSERT INTO work_external_id (provider, external_id, work_id) VALUES (?, ?, ?)
			ON CONFLICT (provider, external_id) DO UPDATE SET work_id = EXCLUDED.work_id
			""".trimIndent(),
		).use { statement ->
			ids.forEach { (provider, externalId) ->
				statement.setString(1, provider)
				statement.setString(2, externalId)
				statement.setLong(3, workId)
				statement.addBatch()
			}
			statement.executeBatch()
		}
	}

	/**
	 * Relations are stored in a second pass: an edge often points at a work the importer has not
	 * reached yet, and creating a placeholder for it would pollute the catalogue with title-less rows.
	 */
	fun linkRelations(provider: String, edges: List<Triple<String, String, String>>): Int {
		if (edges.isEmpty()) return 0
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO work_relation (from_work, to_work, type)
				SELECT f.work_id, t.work_id, ?
				FROM work_external_id f, work_external_id t
				WHERE f.provider = ? AND f.external_id = ?
				  AND t.provider = ? AND t.external_id = ?
				ON CONFLICT DO NOTHING
				""".trimIndent(),
			).use { statement ->
				edges.forEach { (fromExternal, toExternal, type) ->
					statement.setString(1, type)
					statement.setString(2, provider)
					statement.setString(3, fromExternal)
					statement.setString(4, provider)
					statement.setString(5, toExternal)
					statement.addBatch()
				}
				statement.executeBatch().count { it > 0 }
			}
		}
	}

	/** Works that still exist as themselves. A merged-away row is a redirect, not a work. */
	fun countWorks(): Long = dataSource.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery("SELECT count(*) FROM work WHERE merged_into IS NULL").use {
				it.next()
				it.getLong(1)
			}
		}
	}

	fun findByTitle(normalized: String): List<Long> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"SELECT DISTINCT t.work_id FROM work_title t $ACTIVE_WORK WHERE t.title_norm = ?",
		).use { statement ->
			statement.setString(1, normalized)
			statement.executeQuery().use { rows ->
				buildList { while (rows.next()) add(rows.getLong(1)) }
			}
		}
	}

	// -- resumable progress ----------------------------------------------------------------------

	fun lastPage(provider: String): Int = dataSource.connection.use { connection ->
		connection.prepareStatement("SELECT last_page FROM seed_progress WHERE provider = ?").use { statement ->
			statement.setString(1, provider)
			statement.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
		}
	}

	fun recordProgress(provider: String, page: Int, completed: Boolean) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO seed_progress (provider, last_page, last_run_at, completed)
				VALUES (?, ?, now(), ?)
				ON CONFLICT (provider) DO UPDATE SET
					last_page = EXCLUDED.last_page,
					last_run_at = EXCLUDED.last_run_at,
					completed = EXCLUDED.completed
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, provider)
				statement.setInt(2, page)
				statement.setBoolean(3, completed)
				statement.executeUpdate()
			}
		}
	}

	// -- resolution lookups ----------------------------------------------------------------------

	fun findByAlias(source: String, sourceKey: String): Long? =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT work_id FROM work_alias WHERE source = ? AND source_key = ?",
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.executeQuery().use { if (it.next()) it.getLong(1) else null }
			}
		}

	/**
	 * A scrobbler link is the strongest anchor there is, so any one of them hitting is enough. Each
	 * provider is a separate cheap indexed lookup rather than one clever query.
	 */
	fun findByExternalIds(ids: Map<String, String>): Long? {
		if (ids.isEmpty()) return null
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT work_id FROM work_external_id WHERE provider = ? AND external_id = ?",
			).use { statement ->
				ids.forEach { (provider, externalId) ->
					statement.setString(1, provider)
					statement.setString(2, externalId)
					statement.executeQuery().use { rows ->
						if (rows.next()) return rows.getLong(1)
					}
				}
			}
		}
		return null
	}

	/** Exact key hit. The hot path once a work knows the renderings sources actually use. */
	fun findByTitleKeys(keys: Collection<String>): List<Long> {
		if (keys.isEmpty()) return emptyList()
		val placeholders = keys.joinToString(",") { "?" }
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT DISTINCT t.work_id FROM work_title t $ACTIVE_WORK " +
					"WHERE t.title_norm IN ($placeholders)",
			).use { statement ->
				keys.forEachIndexed { index, key -> statement.setString(index + 1, key) }
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.getLong(1)) }
				}
			}
		}
	}

	/**
	 * Trigram candidates, ranked. Uses the pg_trgm GIN index via the `%` operator rather than
	 * computing similarity over every row.
	 */
	fun findSimilarTitles(key: String, threshold: Double, limit: Int = 20): List<Pair<Long, Double>> =
		dataSource.connection.use { connection ->
			// set_limit takes `real`, so the cast is required; it also sets the threshold the `%`
			// operator uses, and it is session-scoped - hence doing it on this same connection.
			connection.prepareStatement("SELECT set_limit(?::real)").use {
				it.setDouble(1, threshold)
				it.execute()
			}
			connection.prepareStatement(
				"""
				SELECT t.work_id, MAX(similarity(t.title_norm, ?)) AS sim
				FROM work_title t $ACTIVE_WORK
				WHERE t.title_norm % ?
				GROUP BY t.work_id
				ORDER BY sim DESC
				LIMIT ?
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, key)
				statement.setString(2, key)
				statement.setInt(3, limit)
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.getLong(1) to rows.getDouble(2)) }
				}
			}
		}

	fun coverHashesFor(workIds: Collection<Long>): Map<Long, List<Long>> {
		if (workIds.isEmpty()) return emptyMap()
		val placeholders = workIds.joinToString(",") { "?" }
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT work_id, phash FROM work_cover_hash WHERE work_id IN ($placeholders)",
			).use { statement ->
				workIds.forEachIndexed { index, id -> statement.setLong(index + 1, id) }
				statement.executeQuery().use { rows ->
					buildMap<Long, MutableList<Long>> {
						while (rows.next()) {
							getOrPut(rows.getLong(1)) { mutableListOf() }.add(rows.getLong(2))
						}
					}
				}
			}
		}
	}

	fun titlesOf(workId: Long): Set<String> = dataSource.connection.use { connection ->
		connection.prepareStatement("SELECT title_norm FROM work_title WHERE work_id = ?").use { statement ->
			statement.setLong(1, workId)
			statement.executeQuery().use { rows ->
				buildSet { while (rows.next()) add(rows.getString(1)) }
			}
		}
	}

	fun metadataOf(workId: Long): Triple<String, Int?, String?>? = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"SELECT canonical_title, year, content_type FROM work WHERE id = ? AND merged_into IS NULL",
		)
			.use { statement ->
				statement.setLong(1, workId)
				statement.executeQuery().use { rows ->
					if (!rows.next()) return@use null
					val year = rows.getInt(2).takeUnless { rows.wasNull() }
					Triple(rows.getString(1), year, rows.getString(3))
				}
			}
	}

	// -- writes ----------------------------------------------------------------------------------

	fun createWork(canonicalTitle: String, year: Int?, contentType: String?, nsfw: Boolean): Long =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"INSERT INTO work (canonical_title, year, content_type, nsfw) VALUES (?, ?, ?, ?)",
				Statement.RETURN_GENERATED_KEYS,
			).use { statement ->
				statement.setString(1, canonicalTitle)
				year?.let { statement.setShort(2, it.toShort()) } ?: statement.setNull(2, Types.SMALLINT)
				statement.setString(3, contentType)
				statement.setBoolean(4, nsfw)
				statement.executeUpdate()
				statement.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
			}
		}

	fun addTitles(workId: Long, titles: List<TitleToStore>) {
		if (titles.isEmpty()) return
		dataSource.connection.use { connection -> storeTitles(connection, workId, titles) }
	}

	fun addExternalIds(workId: Long, ids: Map<String, String>) {
		if (ids.isEmpty()) return
		dataSource.connection.use { connection -> storeExternalIds(connection, workId, ids) }
	}

	fun linkAlias(source: String, sourceKey: String, workId: Long, confidence: Double, evidence: String) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO work_alias (source, source_key, work_id, confidence, evidence)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (source, source_key) DO UPDATE SET
					work_id = EXCLUDED.work_id,
					confidence = EXCLUDED.confidence,
					evidence = EXCLUDED.evidence
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.setLong(3, workId)
				statement.setDouble(4, confidence)
				statement.setString(5, evidence)
				statement.executeUpdate()
			}
		}
	}

	fun storeCoverHash(workId: Long, source: String, phash: Long) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO work_cover_hash (work_id, phash, source) VALUES (?, ?, ?)
				ON CONFLICT (work_id, source) DO UPDATE SET phash = EXCLUDED.phash
				""".trimIndent(),
			).use { statement ->
				statement.setLong(1, workId)
				statement.setLong(2, phash)
				statement.setString(3, source)
				statement.executeUpdate()
			}
		}
	}

	/**
	 * True when a work already carries user content, which makes an automatic merge unacceptable.
	 *
	 * Checks whatever content tables exist, so it starts returning real answers the moment M3 and M4a
	 * create `rating` and `comment` - rather than needing to be remembered and updated then.
	 */
	fun hasUserContent(workId: Long): Boolean = dataSource.connection.use { connection ->
		val present = connection.prepareStatement(
			"SELECT table_name FROM information_schema.tables " +
				"WHERE table_schema = 'public' AND table_name IN ('rating', 'comment')",
		).use { statement ->
			statement.executeQuery().use { rows ->
				buildList { while (rows.next()) add(rows.getString(1)) }
			}
		}
		present.any { table ->
			connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM $table WHERE work_id = ?)")
				.use { statement ->
					statement.setLong(1, workId)
					statement.executeQuery().use { it.next(); it.getBoolean(1) }
				}
		}
	}

	/**
	 * Moves everything from one work onto another.
	 *
	 * **Nothing is destroyed.** The losing work keeps its row and becomes a redirect, which is what
	 * makes this reversible: V5 promised as much and the first implementation deleted the row anyway,
	 * turning `comment.origin_work_id` into decoration. Aliases, external ids and cover hashes move
	 * and are recorded in `work_merge_log.moved_rows`; comments and ratings move and carry their own
	 * origin; titles are copied, so the losing work can still describe itself if it comes back.
	 *
	 * Rows that would collide - a cover hash from the same source, a rating by a user who rated both -
	 * stay where they are rather than being dropped. They are unreachable while the merge stands,
	 * because every lookup ignores merged works, and they are correct again the moment it is undone.
	 */
	fun mergeWorks(from: Long, into: Long, reason: String, moderatorId: String? = null) {
		require(from != into) { "cannot merge a work into itself" }
		dataSource.connection.use { connection ->
			connection.autoCommit = false
			try {
				val moved = buildJsonObject {
					put("aliases", movedAliases(connection, from))
					put("externals", movedExternalIds(connection, from))
					put("covers", movedCoverHashes(connection, from, into))
				}

				listOf(
					"UPDATE work_alias SET work_id = ? WHERE work_id = ?",
					"UPDATE work_external_id SET work_id = ? WHERE work_id = ?",
					"UPDATE comment SET work_id = ? WHERE work_id = ?",
				).forEach { sql ->
					connection.prepareStatement(sql).use { statement ->
						statement.setLong(1, into)
						statement.setLong(2, from)
						statement.executeUpdate()
					}
				}

				// (work_id, source) is the primary key on one and (work_id, user_id) on the other, so
				// a hash from a source the target already has, or a rating by someone who rated both,
				// would collide. Those rows stay behind rather than being dropped.
				listOf(
					"UPDATE work_cover_hash SET work_id = ? WHERE work_id = ? " +
						"AND source NOT IN (SELECT source FROM work_cover_hash WHERE work_id = ?)",
					"UPDATE rating SET work_id = ? WHERE work_id = ? " +
						"AND user_id NOT IN (SELECT user_id FROM rating WHERE work_id = ?)",
				).forEach { sql ->
					connection.prepareStatement(sql).use { statement ->
						statement.setLong(1, into)
						statement.setLong(2, from)
						statement.setLong(3, into)
						statement.executeUpdate()
					}
				}

				// Titles can collide on (work_id, title_norm, kind), so let the conflict drop them.
				connection.prepareStatement(
					"""
					INSERT INTO work_title (work_id, title_raw, title_norm, lang, kind, weight)
					SELECT ?, title_raw, title_norm, lang, kind, weight FROM work_title WHERE work_id = ?
					ON CONFLICT (work_id, title_norm, kind) DO NOTHING
					""".trimIndent(),
				).use { statement ->
					statement.setLong(1, into)
					statement.setLong(2, from)
					statement.executeUpdate()
				}

				connection.prepareStatement(
					"""
					INSERT INTO work_merge_log (from_work, into_work, reason, moved_rows, by_moderator)
					VALUES (?, ?, ?, ?::jsonb, ?)
					""".trimIndent(),
				).use { statement ->
					statement.setLong(1, from)
					statement.setLong(2, into)
					statement.setString(3, reason)
					statement.setString(4, moved.toString())
					statement.setString(5, moderatorId)
					statement.executeUpdate()
				}
				connection.prepareStatement("UPDATE work SET merged_into = ? WHERE id = ?").use { statement ->
					statement.setLong(1, into)
					statement.setLong(2, from)
					statement.executeUpdate()
				}
				connection.commit()
			} catch (e: Exception) {
				connection.rollback()
				throw e
			}
		}
	}

	/**
	 * Undoes a merge, putting back exactly the rows it moved.
	 *
	 * Content follows its `origin_work_id`, which never changed, so a comment written on the losing
	 * work goes home even if it was edited, voted on or replied to in the meantime. Anything created
	 * *after* the merge stays with the surviving work, because that is where it was written.
	 *
	 * @return the pair (from, into) that was undone, or null if there is nothing to undo.
	 */
	fun unmergeWork(from: Long): Pair<Long, Long>? = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val pending = connection.prepareStatement(
				"""
				SELECT id, into_work, moved_rows::text FROM work_merge_log
				WHERE from_work = ? AND undone_at IS NULL
				ORDER BY merged_at DESC LIMIT 1
				""".trimIndent(),
			).use { statement ->
				statement.setLong(1, from)
				statement.executeQuery().use { rows ->
					if (rows.next()) Triple(rows.getLong(1), rows.getLong(2), rows.getString(3)) else null
				}
			}
			if (pending == null) {
				connection.rollback()
				return@use null
			}
			val (logId, into, moved) = pending
			val record = moved?.let { Json.parseToJsonElement(it).jsonObject }

			record?.get("aliases")?.jsonArray?.forEach { element ->
				val alias = element.jsonObject
				connection.prepareStatement(
					"UPDATE work_alias SET work_id = ? WHERE source = ? AND source_key = ?",
				).use { statement ->
					statement.setLong(1, from)
					statement.setString(2, alias.getValue("source").jsonPrimitive.content)
					statement.setString(3, alias.getValue("key").jsonPrimitive.content)
					statement.executeUpdate()
				}
			}
			record?.get("externals")?.jsonArray?.forEach { element ->
				val external = element.jsonObject
				connection.prepareStatement(
					"UPDATE work_external_id SET work_id = ? WHERE provider = ? AND external_id = ?",
				).use { statement ->
					statement.setLong(1, from)
					statement.setString(2, external.getValue("provider").jsonPrimitive.content)
					statement.setString(3, external.getValue("id").jsonPrimitive.content)
					statement.executeUpdate()
				}
			}
			record?.get("covers")?.jsonArray?.forEach { element ->
				connection.prepareStatement(
					"UPDATE work_cover_hash SET work_id = ? WHERE work_id = ? AND source = ?",
				).use { statement ->
					statement.setLong(1, from)
					statement.setLong(2, into)
					statement.setString(3, element.jsonObject.getValue("source").jsonPrimitive.content)
					statement.executeUpdate()
				}
			}

			listOf(
				"UPDATE comment SET work_id = origin_work_id WHERE work_id = ? AND origin_work_id = ?",
				"UPDATE rating SET work_id = origin_work_id WHERE work_id = ? AND origin_work_id = ?",
			).forEach { sql ->
				connection.prepareStatement(sql).use { statement ->
					statement.setLong(1, into)
					statement.setLong(2, from)
					statement.executeUpdate()
				}
			}

			connection.prepareStatement("UPDATE work SET merged_into = NULL WHERE id = ?").use { statement ->
				statement.setLong(1, from)
				statement.executeUpdate()
			}
			connection.prepareStatement("UPDATE work_merge_log SET undone_at = now() WHERE id = ?")
				.use { statement ->
					statement.setLong(1, logId)
					statement.executeUpdate()
				}
			connection.commit()
			from to into
		} catch (e: Exception) {
			connection.rollback()
			throw e
		}
	}

	private fun movedAliases(connection: java.sql.Connection, from: Long): JsonArray =
		connection.prepareStatement("SELECT source, source_key FROM work_alias WHERE work_id = ?")
			.use { statement ->
				statement.setLong(1, from)
				statement.executeQuery().use { rows ->
					buildJsonArray {
						while (rows.next()) {
							add(
								buildJsonObject {
									put("source", rows.getString(1))
									put("key", rows.getString(2))
								},
							)
						}
					}
				}
			}

	private fun movedExternalIds(connection: java.sql.Connection, from: Long): JsonArray =
		connection.prepareStatement("SELECT provider, external_id FROM work_external_id WHERE work_id = ?")
			.use { statement ->
				statement.setLong(1, from)
				statement.executeQuery().use { rows ->
					buildJsonArray {
						while (rows.next()) {
							add(
								buildJsonObject {
									put("provider", rows.getString(1))
									put("id", rows.getString(2))
								},
							)
						}
					}
				}
			}

	/** Only the ones that will actually move; a colliding source stays put and must not be recorded. */
	private fun movedCoverHashes(connection: java.sql.Connection, from: Long, into: Long): JsonArray =
		connection.prepareStatement(
			"""
			SELECT source FROM work_cover_hash WHERE work_id = ?
			  AND source NOT IN (SELECT source FROM work_cover_hash WHERE work_id = ?)
			""".trimIndent(),
		).use { statement ->
			statement.setLong(1, from)
			statement.setLong(2, into)
			statement.executeQuery().use { rows ->
				buildJsonArray {
					while (rows.next()) add(buildJsonObject { put("source", rows.getString(1)) })
				}
			}
		}

	/**
	 * Where a merged-away work went, so clients holding a stale id can be redirected (PLAN.md §5).
	 *
	 * Follows a chain, because A can be merged into B and B later into C, and a client still holding
	 * A's id deserves C rather than a dead end.
	 */
	fun mergedInto(workId: Long): Long? = dataSource.connection.use { connection ->
		connection.prepareStatement("SELECT merged_into FROM work WHERE id = ?").use { statement ->
			var current = workId
			repeat(MAX_MERGE_HOPS) {
				statement.setLong(1, current)
				val next = statement.executeQuery().use { rows ->
					if (rows.next()) rows.getLong(1).takeUnless { rows.wasNull() } else null
				}
				if (next == null) return@use current.takeIf { it != workId }
				current = next
			}
			current
		}
	}

	private companion object {
		/** Joined into every title lookup: a merged work is a redirect, not a candidate. */
		const val ACTIVE_WORK = "JOIN work w ON w.id = t.work_id AND w.merged_into IS NULL"

		/** A merge chain longer than this is a cycle, and following it forever is worse than stopping. */
		const val MAX_MERGE_HOPS = 8
	}
}
