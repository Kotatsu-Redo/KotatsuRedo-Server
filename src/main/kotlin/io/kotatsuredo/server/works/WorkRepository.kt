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

/** A work created from a source's own words, still waiting for a catalogue to describe it. */
data class PendingEnrichment(
	val workId: Long,
	val title: String,
	val year: Int?,
	val contentType: String?,
	val attempts: Int,
)

data class WorkCreationResult(
	val workId: Long,
	val created: Boolean,
	/** True when another request had already claimed this exact source alias. */
	val aliasAlreadyExisted: Boolean,
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
						seed.year?.let {
							require(WorkLimits.isValidYear(it)) { "work year is outside the supported range" }
							statement.setShort(2, it.toShort())
						} ?: statement.setNull(2, Types.SMALLINT)
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
					seed.year?.let {
						require(WorkLimits.isValidYear(it)) { "work year is outside the supported range" }
						statement.setShort(2, it.toShort())
					} ?: statement.setNull(2, Types.SMALLINT)
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
			ON CONFLICT (provider, external_id) DO NOTHING
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
	 * Atomically claims a new source alias and all data belonging to its work.
	 *
	 * Misses are rare after warm-up, so a single transaction-level advisory lock is deliberately
	 * simpler and safer than leaving orphan works when two different aliases discover the same
	 * catalogue id concurrently. Existing alias hits never enter this path.
	 */
	fun createAndLinkWork(
		canonicalTitle: String,
		year: Int?,
		contentType: String?,
		nsfw: Boolean,
		titles: List<TitleToStore>,
		externalIds: Map<String, String>,
		source: String,
		sourceKey: String,
		confidence: Double,
		evidence: String,
		coverPHash: Long?,
	): WorkCreationResult = dataSource.connection.use connectionUse@ { connection ->
		connection.autoCommit = false
		try {
			connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
				statement.setLong(1, WORK_CREATION_LOCK)
				statement.execute()
			}

			val existingAlias = connection.prepareStatement(
				"SELECT work_id FROM work_alias WHERE source = ? AND source_key = ?",
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
			}
			if (existingAlias != null) {
				connection.commit()
				return@connectionUse WorkCreationResult(existingAlias, created = false, aliasAlreadyExisted = true)
			}

			// These identifiers come from a server-side catalogue record. Re-check them under the
			// creation lock: different source aliases can finish the same lookup concurrently, before
			// either work was visible to the resolver. Client-supplied identifiers never select here.
			val existingWork = findByExternalIds(connection, externalIds)
			val workId = existingWork ?: connection.prepareStatement(
				"INSERT INTO work (canonical_title, year, content_type, nsfw) VALUES (?, ?, ?, ?)",
				Statement.RETURN_GENERATED_KEYS,
			).use { statement ->
				statement.setString(1, canonicalTitle)
				year?.let {
					require(WorkLimits.isValidYear(it)) { "work year is outside the supported range" }
					statement.setShort(2, it.toShort())
				} ?: statement.setNull(2, Types.SMALLINT)
				statement.setString(3, contentType)
				statement.setBoolean(4, nsfw)
				statement.executeUpdate()
				statement.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
			}

			storeTitles(connection, workId, titles)
			storeExternalIds(connection, workId, externalIds)
			val aliasInserted = connection.prepareStatement(
				"""
				INSERT INTO work_alias (source, source_key, work_id, confidence, evidence, is_verified)
				VALUES (?, ?, ?, ?, ?, TRUE)
				ON CONFLICT (source, source_key) DO NOTHING
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.setLong(3, workId)
				statement.setDouble(4, confidence)
				statement.setString(5, evidence)
				statement.executeUpdate() > 0
			}
			if (!aliasInserted) {
				// Another resolution path claimed the alias after our initial check. Roll back every
				// provisional row and return its winner instead of leaking an orphan or a 500.
				val winner = connection.prepareStatement(
					"SELECT work_id FROM work_alias WHERE source = ? AND source_key = ?",
				).use { statement ->
					statement.setString(1, source)
					statement.setString(2, sourceKey)
					statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
				}
				connection.rollback()
				return@connectionUse WorkCreationResult(
					checkNotNull(winner) { "conflicting alias disappeared" },
					created = false,
					aliasAlreadyExisted = true,
				)
			}
			coverPHash?.let { phash ->
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

			connection.commit()
			WorkCreationResult(workId, created = existingWork == null, aliasAlreadyExisted = false)
		} catch (e: Exception) {
			connection.rollback()
			throw e
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

	/** Only corroborated aliases may short-circuit resolution for every account. */
	fun findVerifiedAlias(source: String, sourceKey: String): Long? =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT work_id FROM work_alias WHERE source = ? AND source_key = ? AND is_verified",
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
			}
		}

	fun observedAlias(source: String, sourceKey: String, userId: String, titleKeys: Collection<String>): Long? {
		if (titleKeys.isEmpty()) return null
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT work_id FROM work_alias_observation
				WHERE source = ? AND source_key = ? AND user_id = ? AND title_keys && ?::text[]
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.setString(3, userId)
				statement.setArray(4, connection.createArrayOf("text", titleKeys.distinct().toTypedArray()))
				statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
			}
		}
	}

	/** Pending claims sharing a normalized title, ordered by independent support. */
	fun matchingObservedAliases(source: String, sourceKey: String, titleKeys: Collection<String>): List<Long> {
		if (titleKeys.isEmpty()) return emptyList()
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT work_id
				FROM work_alias_observation
				WHERE source = ? AND source_key = ? AND title_keys && ?::text[]
				GROUP BY work_id
				ORDER BY count(*) DESC, work_id
				LIMIT 20
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.setArray(3, connection.createArrayOf("text", titleKeys.distinct().toTypedArray()))
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.getLong(1)) }
				}
			}
		}
	}

	/**
	 * Records one account's claim and promotes it only after independent settled-account agreement.
	 * A previously verified, conflicting alias is never replaced automatically.
	 */
	fun observeAlias(
		source: String,
		sourceKey: String,
		userId: String,
		workId: Long,
		titleKeys: Collection<String>,
	): Boolean = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val verified = observeAlias(connection, source, sourceKey, userId, workId, titleKeys)
			connection.commit()
			verified
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	private fun observeAlias(
		connection: java.sql.Connection,
		source: String,
		sourceKey: String,
		userId: String,
		workId: Long,
		titleKeys: Collection<String>,
	): Boolean {
			// Serialise the count-and-promote decision for this source key. Without this lock, the
			// second and third observations can each see only two committed rows and both skip promotion.
			connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))").use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.execute()
			}
			connection.prepareStatement(
				"""
				INSERT INTO work_alias_observation (source, source_key, user_id, work_id, title_keys)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (source, source_key, user_id) DO UPDATE SET
					work_id = EXCLUDED.work_id, title_keys = EXCLUDED.title_keys, updated_at = now()
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.setString(3, userId)
				statement.setLong(4, workId)
				statement.setArray(5, connection.createArrayOf("text", titleKeys.distinct().toTypedArray()))
				statement.executeUpdate()
			}
			val agreement = connection.prepareStatement(
				"""
				SELECT count(*)
				FROM work_alias_observation observation
				JOIN user_trust trust ON trust.user_id = observation.user_id
				JOIN app_user account ON account.id = observation.user_id
				WHERE observation.source = ? AND observation.source_key = ? AND observation.work_id = ?
				  AND observation.title_keys && ?::text[] AND trust.tier >= ?
				  AND NOT account.is_banned AND NOT account.is_shadowbanned
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.setLong(3, workId)
				statement.setArray(4, connection.createArrayOf("text", titleKeys.distinct().toTypedArray()))
				statement.setInt(5, io.kotatsuredo.server.identity.TrustTier.ESTABLISHED.level)
				statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
			}
			val verified = agreement >= ALIAS_AGREEMENT_REQUIRED
			if (verified) {
				connection.prepareStatement(
					"""
					INSERT INTO work_alias (source, source_key, work_id, confidence, evidence, is_verified)
					VALUES (?, ?, ?, ?, 'account_quorum', TRUE)
					ON CONFLICT (source, source_key) DO UPDATE SET
						work_id = EXCLUDED.work_id,
						confidence = EXCLUDED.confidence,
						evidence = EXCLUDED.evidence,
						is_verified = TRUE
					WHERE NOT work_alias.is_verified
					""".trimIndent(),
				).use { statement ->
					statement.setString(1, source)
					statement.setString(2, sourceKey)
					statement.setLong(3, workId)
					statement.setDouble(4, ResolutionMethod.OBSERVATION.confidence)
					statement.executeUpdate()
				}
			}
			return verified
	}

	/**
	 * A scrobbler link is the strongest anchor there is, so any one of them hitting is enough. Each
	 * provider is a separate cheap indexed lookup rather than one clever query.
	 */
	fun findByExternalIds(ids: Map<String, String>): Long? {
		if (ids.isEmpty()) return null
		dataSource.connection.use { connection ->
			return findByExternalIds(connection, ids)
		}
	}

	private fun findByExternalIds(connection: java.sql.Connection, ids: Map<String, String>): Long? {
		if (ids.isEmpty()) return null
		var match: Long? = null
		connection.prepareStatement(
			"SELECT work_id FROM work_external_id WHERE provider = ? AND external_id = ?",
		).use { statement ->
			ids.forEach { (provider, externalId) ->
				statement.setString(1, provider)
				statement.setString(2, externalId)
				statement.executeQuery().use { rows ->
					if (rows.next()) {
						val candidate = rows.getLong(1)
						// A bad catalogue payload can occasionally contain identifiers belonging to two
						// works. Refuse the ambiguous anchor instead of selecting by map iteration order.
						if (match != null && match != candidate) return null
						match = candidate
					}
				}
			}
		}
		return match
	}

	private fun findCompatibleObservedWork(
		connection: java.sql.Connection,
		userId: String,
		titleKeys: Collection<String>,
		canonicalTitle: String,
		year: Int?,
		contentType: String?,
	): Long? {
		if (titleKeys.isEmpty()) return null
		return connection.prepareStatement(
			"""
			SELECT DISTINCT work.id, work.canonical_title, work.year, work.content_type
			FROM work_alias_observation observation
			JOIN work ON work.id = observation.work_id AND work.merged_into IS NULL
			WHERE observation.user_id = ? AND observation.title_keys && ?::text[]
			ORDER BY work.id
			LIMIT 20
			""".trimIndent(),
		).use { statement ->
			statement.setString(1, userId)
			statement.setArray(2, connection.createArrayOf("text", titleKeys.distinct().toTypedArray()))
			statement.executeQuery().use { rows ->
				while (rows.next()) {
					val candidateYear = rows.getInt(3).takeUnless { rows.wasNull() }
					val metadata = Triple(rows.getString(2), candidateYear, rows.getString(4))
					if (WorkCompatibility.isCompatible(canonicalTitle, year, contentType, metadata)) {
						return rows.getLong(1)
					}
				}
				null
			}
		}
	}

	/** Exact-title works this account has already associated with any source. */
	fun observedWorks(userId: String, titleKeys: Collection<String>): List<Long> {
		if (titleKeys.isEmpty()) return emptyList()
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				SELECT DISTINCT observation.work_id
				FROM work_alias_observation observation
				JOIN work ON work.id = observation.work_id AND work.merged_into IS NULL
				WHERE observation.user_id = ? AND observation.title_keys && ?::text[]
				ORDER BY observation.work_id
				LIMIT 20
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, userId)
				statement.setArray(2, connection.createArrayOf("text", titleKeys.distinct().toTypedArray()))
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.getLong(1)) }
				}
			}
		}
	}

	/** Exact key hit. The hot path once a work knows the renderings sources actually use. */
	fun findByTitleKeys(keys: Collection<String>, minimumWeight: Double = 0.0): List<Long> {
		if (keys.isEmpty()) return emptyList()
		val placeholders = keys.joinToString(",") { "?" }
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT DISTINCT t.work_id FROM work_title t $ACTIVE_WORK " +
					"WHERE t.weight >= ? AND t.title_norm IN ($placeholders)",
			).use { statement ->
				statement.setDouble(1, minimumWeight)
				keys.forEachIndexed { index, key -> statement.setString(index + 2, key) }
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
	fun findSimilarTitles(
		key: String,
		threshold: Double,
		limit: Int = 20,
		minimumWeight: Double = 0.0,
	): List<Pair<Long, Double>> = findSimilarTitles(listOf(key), threshold, limit, minimumWeight)

	/**
	 * Resolves all fuzzy keys with one checkout and one statement. The lateral subquery preserves the
	 * per-key candidate limit and lets PostgreSQL use the pg_trgm index for each input key.
	 */
	fun findSimilarTitles(
		keys: Collection<String>,
		threshold: Double,
		limitPerKey: Int = 20,
		minimumWeight: Double = 0.0,
	): List<Pair<Long, Double>> {
		if (keys.isEmpty()) return emptyList()
		return dataSource.connection.use { connection ->
			connection.prepareStatement("SELECT set_limit(?::real)").use {
				it.setDouble(1, threshold)
				it.execute()
			}
			connection.prepareStatement(
				"""
				SELECT candidate.work_id, MAX(candidate.sim) AS sim
				FROM unnest(?::text[]) AS input(key)
				CROSS JOIN LATERAL (
					SELECT t.work_id, MAX(similarity(t.title_norm, input.key)) AS sim
					FROM work_title t $ACTIVE_WORK
					WHERE t.weight >= ? AND t.title_norm % input.key
					GROUP BY t.work_id
					ORDER BY sim DESC
					LIMIT ?
				) candidate
				GROUP BY candidate.work_id
				ORDER BY sim DESC
				""".trimIndent(),
			).use { statement ->
				statement.setArray(1, connection.createArrayOf("text", keys.distinct().toTypedArray()))
				statement.setDouble(2, minimumWeight)
				statement.setInt(3, limitPerKey)
				statement.executeQuery().use { rows ->
					return@use buildList { while (rows.next()) add(rows.getLong(1) to rows.getDouble(2)) }
				}
			}
		}
	}

	@Deprecated("Use the batched overload")
	private fun findSimilarTitlesOneAtATime(key: String, threshold: Double, limit: Int): List<Pair<Long, Double>> =
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

	fun metadataOf(workIds: Collection<Long>): Map<Long, Triple<String, Int?, String?>> {
		if (workIds.isEmpty()) return emptyMap()
		val placeholders = workIds.joinToString(",") { "?" }
		return dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT id, canonical_title, year, content_type FROM work " +
					"WHERE merged_into IS NULL AND id IN ($placeholders)",
			).use { statement ->
				workIds.forEachIndexed { index, id -> statement.setLong(index + 1, id) }
				statement.executeQuery().use { rows ->
					buildMap {
						while (rows.next()) {
							val year = rows.getInt(3).takeUnless { rows.wasNull() }
							put(rows.getLong(1), Triple(rows.getString(2), year, rows.getString(4)))
						}
					}
				}
			}
		}
	}

	// -- writes ----------------------------------------------------------------------------------

	/** Creates a provisional work and its account observation as one all-or-nothing unit. */
	fun createObservedWork(
		canonicalTitle: String,
		year: Int?,
		contentType: String?,
		nsfw: Boolean,
		titles: List<TitleToStore>,
		externalIds: Map<String, String>,
		source: String,
		sourceKey: String,
		reporterId: String,
		titleKeys: Collection<String>,
		coverPHash: Long?,
	): WorkCreationResult = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			// Resolutions for different source keys use different in-process locks. Serialize only their
			// commit path, then recheck authoritative ids and this account's compatible observations in
			// case another request won the race while this one was resolving.
			connection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
				statement.setLong(1, WORK_CREATION_LOCK)
				statement.execute()
			}
			val existingWork = findByExternalIds(connection, externalIds) ?: findCompatibleObservedWork(
				connection,
				reporterId,
				titleKeys,
				canonicalTitle,
				year,
				contentType,
			)
			val workId = existingWork ?: connection.prepareStatement(
				"INSERT INTO work (canonical_title, year, content_type, nsfw) VALUES (?, ?, ?, ?)",
				Statement.RETURN_GENERATED_KEYS,
			).use { statement ->
				statement.setString(1, canonicalTitle)
				year?.let {
					require(WorkLimits.isValidYear(it)) { "work year is outside the supported range" }
					statement.setShort(2, it.toShort())
				} ?: statement.setNull(2, Types.SMALLINT)
				statement.setString(3, contentType)
				statement.setBoolean(4, nsfw)
				statement.executeUpdate()
				statement.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
			}
			storeTitles(connection, workId, titles)
			storeExternalIds(connection, workId, externalIds)
			coverPHash?.let { phash ->
				connection.prepareStatement(
					"INSERT INTO work_cover_hash (work_id, phash, source) VALUES (?, ?, ?)",
				).use { statement ->
					statement.setLong(1, workId)
					statement.setLong(2, phash)
					statement.setString(3, source)
					statement.executeUpdate()
				}
			}
			observeAlias(connection, source, sourceKey, reporterId, workId, titleKeys)
			connection.commit()
			WorkCreationResult(workId, created = existingWork == null, aliasAlreadyExisted = false)
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	fun createWork(canonicalTitle: String, year: Int?, contentType: String?, nsfw: Boolean): Long =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"INSERT INTO work (canonical_title, year, content_type, nsfw) VALUES (?, ?, ?, ?)",
				Statement.RETURN_GENERATED_KEYS,
			).use { statement ->
				statement.setString(1, canonicalTitle)
				year?.let {
					require(WorkLimits.isValidYear(it)) { "work year is outside the supported range" }
					statement.setShort(2, it.toShort())
				} ?: statement.setNull(2, Types.SMALLINT)
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

	fun linkAlias(source: String, sourceKey: String, workId: Long, confidence: Double, evidence: String): Long =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				WITH linked AS (
				INSERT INTO work_alias (source, source_key, work_id, confidence, evidence, is_verified)
				VALUES (?, ?, ?, ?, ?, TRUE)
				ON CONFLICT (source, source_key) DO UPDATE SET
					work_id = EXCLUDED.work_id,
					confidence = EXCLUDED.confidence,
					evidence = EXCLUDED.evidence,
					is_verified = TRUE
				WHERE NOT work_alias.is_verified
				RETURNING work_id
				)
				SELECT work_id FROM linked
				UNION ALL
				SELECT work_id FROM work_alias WHERE source = ? AND source_key = ?
				LIMIT 1
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, source)
				statement.setString(2, sourceKey)
				statement.setLong(3, workId)
				statement.setDouble(4, confidence)
				statement.setString(5, evidence)
				statement.setString(6, source)
				statement.setString(7, sourceKey)
				statement.executeQuery().use { rows ->
					check(rows.next()) { "alias insert or lookup returned no row" }
					rows.getLong(1)
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
	// -- catalogue enrichment --------------------------------------------------------------------

	/** Records that this work was created from what a source said and still needs a catalogue. */
	fun enqueueEnrichment(
		workId: Long,
		title: String,
		year: Int?,
		contentType: String?,
		delaySeconds: Long = 0,
	) {
		dataSource.connection.use { connection ->
			enqueueEnrichment(connection, workId, title, year, contentType, delaySeconds)
		}
	}

	/** How many entries are due right now, for deciding whether to seed more. */
	fun dueEnrichmentCount(): Int = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"SELECT count(*) FROM work_enrichment WHERE done_at IS NULL AND next_attempt_at <= now()",
		).use { statement ->
			statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
		}
	}

	private fun enqueueEnrichment(
		connection: java.sql.Connection,
		workId: Long,
		title: String,
		year: Int?,
		contentType: String?,
		delaySeconds: Long = 0,
	) {
		connection.prepareStatement(
			"""
			INSERT INTO work_enrichment (work_id, title, year, content_type, next_attempt_at)
			VALUES (?, ?, ?, ?, now() + make_interval(secs => ?)) ON CONFLICT (work_id) DO NOTHING
			""".trimIndent(),
		).use { statement ->
			statement.setLong(1, workId)
			statement.setString(2, title)
			year?.takeIf(WorkLimits::isValidYear)?.let { statement.setShort(3, it.toShort()) }
				?: statement.setNull(3, Types.SMALLINT)
			statement.setString(4, contentType)
			statement.setDouble(5, delaySeconds.toDouble())
			statement.executeUpdate()
		}
	}

	/**
	 * Queues works that no catalogue has ever described, most-read first.
	 *
	 * These are the works created while Kitsu answered 406 to every request and nothing but a title
	 * match could join them, so the duplicates among them are invisible to everything except the ids
	 * a catalogue can now supply. Bounded per call and idempotent: pressing the button twice queues
	 * the next batch rather than the same one.
	 *
	 * @return how many were queued
	 */
	fun enqueueBackfill(limit: Int): Int = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			INSERT INTO work_enrichment (work_id, title, year, content_type)
			SELECT w.id, w.canonical_title, w.year, w.content_type
			FROM work w
			LEFT JOIN work_enrichment e ON e.work_id = w.id
			LEFT JOIN (SELECT DISTINCT work_id FROM work_external_id) x ON x.work_id = w.id
			WHERE w.merged_into IS NULL AND e.work_id IS NULL AND x.work_id IS NULL
			ORDER BY (SELECT count(*) FROM work_alias_observation o WHERE o.work_id = w.id) DESC, w.id
			LIMIT ?
			ON CONFLICT (work_id) DO NOTHING
			""".trimIndent(),
		).use { statement ->
			statement.setInt(1, limit)
			statement.executeUpdate()
		}
	}

	/** How many works are still waiting for their first catalogue description. */
	fun backfillCandidates(): Int = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT count(*) FROM work w
			LEFT JOIN work_enrichment e ON e.work_id = w.id
			LEFT JOIN (SELECT DISTINCT work_id FROM work_external_id) x ON x.work_id = w.id
			WHERE w.merged_into IS NULL AND e.work_id IS NULL AND x.work_id IS NULL
			""".trimIndent(),
		).use { statement ->
			statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
		}
	}

	/** The oldest due entries. Merged-away works are skipped: their enrichment landed elsewhere. */
	fun dueEnrichments(limit: Int): List<PendingEnrichment> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT e.work_id, e.title, e.year, e.content_type, e.attempts
			FROM work_enrichment e
			JOIN work w ON w.id = e.work_id AND w.merged_into IS NULL
			WHERE e.done_at IS NULL AND e.next_attempt_at <= now()
			ORDER BY e.next_attempt_at, e.work_id
			LIMIT ?
			""".trimIndent(),
		).use { statement ->
			statement.setInt(1, limit)
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(
							PendingEnrichment(
								workId = rows.getLong(1),
								title = rows.getString(2),
								year = rows.getInt(3).takeUnless { rows.wasNull() },
								contentType = rows.getString(4),
								attempts = rows.getInt(5),
							),
						)
					}
				}
			}
		}
	}

	fun finishEnrichment(workId: Long) {
		dataSource.connection.use { connection -> finishEnrichment(connection, workId) }
	}

	private fun finishEnrichment(connection: java.sql.Connection, workId: Long) {
		// Marked rather than deleted: the row is the record that this work has been asked about, and
		// without it a work nothing lists looks identical to one nobody has ever looked up.
		connection.prepareStatement(
			"UPDATE work_enrichment SET done_at = now() WHERE work_id = ? AND done_at IS NULL",
		).use { statement ->
			statement.setLong(1, workId)
			statement.executeUpdate()
		}
	}

	/**
	 * Opens a finished entry again, later. Used when an answer was assembled while one provider was
	 * unreachable: what came back is already applied, and the rest is worth asking for once it is up.
	 */
	fun scheduleRecheck(workId: Long, delaySeconds: Long) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"UPDATE work_enrichment SET done_at = NULL, attempts = 0, " +
					"next_attempt_at = now() + make_interval(secs => ?) WHERE work_id = ?",
			).use { statement ->
				statement.setDouble(1, delaySeconds.toDouble())
				statement.setLong(2, workId)
				statement.executeUpdate()
			}
		}
	}

	/** A provider that could not answer gets another go later, never a silent drop. */
	fun retryEnrichmentLater(workId: Long, delaySeconds: Long) {
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"UPDATE work_enrichment SET attempts = attempts + 1, next_attempt_at = now() + " +
					"make_interval(secs => ?) WHERE work_id = ?",
			).use { statement ->
				statement.setDouble(1, delaySeconds.toDouble())
				statement.setLong(2, workId)
				statement.executeUpdate()
			}
		}
	}

	/**
	 * Applies what a catalogue says to a work created from a source's own words, and clears the queue
	 * entry in the same transaction so a crash between the two cannot lose either.
	 *
	 * The canonical title, year and type are only filled in where the work has nothing better: a
	 * source's rendering is what its readers see, and replacing it wholesale would rename works under
	 * people mid-session for no gain.
	 */
	fun applyCatalogue(
		workId: Long,
		canonicalTitle: String,
		year: Int?,
		contentType: String?,
		nsfw: Boolean,
		titles: List<TitleToStore>,
		externalIds: Map<String, String>,
	) = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			connection.prepareStatement(
				"""
				UPDATE work SET
					canonical_title = ?,
					year = COALESCE(year, ?),
					content_type = COALESCE(content_type, ?),
					nsfw = nsfw OR ?
				WHERE id = ? AND merged_into IS NULL
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, canonicalTitle)
				year?.takeIf(WorkLimits::isValidYear)?.let { statement.setShort(2, it.toShort()) }
					?: statement.setNull(2, Types.SMALLINT)
				statement.setString(3, contentType)
				statement.setBoolean(4, nsfw)
				statement.setLong(5, workId)
				statement.executeUpdate()
			}
			storeTitles(connection, workId, titles)
			storeExternalIds(connection, workId, externalIds)
			finishEnrichment(connection, workId)
			connection.commit()
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	// -- duplicate sweep -------------------------------------------------------------------------

	/** Every active work with what [DuplicateFinder] needs, in a handful of full scans. */
	fun dedupeSnapshot(): List<DedupeWork> = dataSource.connection.use { connection ->
		fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
			connection.prepareStatement(sql).use { statement ->
				statement.fetchSize = 5_000
				statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
			}

		val trusted = query(
			"SELECT DISTINCT t.work_id, t.title_raw FROM work_title t $ACTIVE_WORK " +
				"WHERE t.weight >= ${WorkResolver.MIN_TRUSTED_TITLE_WEIGHT}",
		) { it.getLong(1) to it.getString(2) }.groupBy({ it.first }, { it.second })
		val externals = query(
			"SELECT e.work_id, e.provider, e.external_id FROM work_external_id e " +
				"JOIN work w ON w.id = e.work_id AND w.merged_into IS NULL",
		) { Triple(it.getLong(1), it.getString(2), it.getString(3)) }
			.groupBy { it.first }
			.mapValues { (_, rows) -> rows.groupBy({ it.second }, { it.third }).mapValues { it.value.toSet() } }
		val activity = query(
			"""
			SELECT work_id, count(*) FROM (
				SELECT work_id FROM rating
				UNION ALL SELECT work_id FROM comment
				UNION ALL SELECT work_id FROM work_alias_observation
			) content GROUP BY work_id
			""".trimIndent(),
		) { it.getLong(1) to it.getInt(2) }.toMap()

		// A work only a device-local placeholder source ever reported ("Downloads", a folder name) is not
		// a work anyone else can open, so it takes no part in merging.
		val placeholderOnly = query(
			"""
			SELECT work_id FROM (
				SELECT work_id, source FROM work_alias_observation
				UNION ALL SELECT work_id, source FROM work_alias
			) seen GROUP BY work_id
			HAVING bool_and(source IN (${WorkLimits.NON_NETWORK_SOURCES.joinToString(",") { "'$it'" }}))
			""".trimIndent(),
		) { it.getLong(1) }.toSet()

		query("SELECT id, canonical_title, year, content_type FROM work WHERE merged_into IS NULL") { rows ->
			val id = rows.getLong(1)
			DedupeWork(
				id = id,
				canonicalTitle = rows.getString(2),
				year = rows.getInt(3).takeUnless { rows.wasNull() },
				contentType = rows.getString(4),
				trustedTitles = trusted[id].orEmpty(),
				externalIds = externals[id].orEmpty(),
				activity = activity[id] ?: 0,
			)
		}.filterNot { it.id in placeholderOnly }
	}

	/** Automatic merges still in effect, as (from, into), for re-checking against current guards. */
	fun activeAutoMerges(reason: String): List<Pair<DedupeWork, DedupeWork>> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT f.id, f.canonical_title, f.year, f.content_type, i.id, i.canonical_title, i.year, i.content_type
			FROM work_merge_log l
			JOIN work f ON f.id = l.from_work AND f.merged_into = l.into_work
			JOIN work i ON i.id = l.into_work
			WHERE l.reason = ? AND l.undone_at IS NULL
			""".trimIndent(),
		).use { statement ->
			statement.setString(1, reason)
			statement.executeQuery().use { rows ->
				fun work(offset: Int) = DedupeWork(
					id = rows.getLong(offset),
					canonicalTitle = rows.getString(offset + 1),
					year = rows.getInt(offset + 2).takeUnless { rows.wasNull() },
					contentType = rows.getString(offset + 3),
				)
				buildList { while (rows.next()) add(work(1) to work(5)) }
			}
		}
	}

	/** Pairs that must never be merged automatically: undone merges and known sequels/side stories. */
	fun blockedMergePairs(): Set<Pair<Long, Long>> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT from_work, into_work FROM work_merge_log WHERE undone_at IS NOT NULL
			UNION SELECT from_work, to_work FROM work_relation
			""".trimIndent(),
		).use { statement ->
			statement.executeQuery().use { rows ->
				buildSet { while (rows.next()) add(rows.getLong(1) to rows.getLong(2)) }
			}
		}
	}

	/**
	 * Adds the keys the current [TitleNormalizer] produces for stored titles but that are not indexed
	 * yet. Stale keys are left alone: they can only ever make an exact lookup hit, never miss.
	 *
	 * @return the number of key rows added
	 */
	fun reindexTitleKeys(): Int = dataSource.connection.use { connection ->
		data class Title(val workId: Long, val raw: String, val lang: String?, val kind: String)

		val indexed = HashMap<Title, Pair<MutableSet<String>, Double>>()
		connection.prepareStatement(
			"SELECT t.work_id, t.title_raw, t.lang, t.kind, t.title_norm, t.weight FROM work_title t $ACTIVE_WORK",
		).use { statement ->
			statement.fetchSize = 5_000
			statement.executeQuery().use { rows ->
				while (rows.next()) {
					val title = Title(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4))
					val (keys, weight) = indexed.getOrPut(title) { mutableSetOf<String>() to rows.getDouble(6) }
					keys += rows.getString(5)
					if (rows.getDouble(6) > weight) indexed[title] = keys to rows.getDouble(6)
				}
			}
		}
		val missing = indexed.flatMap { (title, stored) ->
			(TitleNormalizer.keys(title.raw) - stored.first).map { key -> Triple(title, key, stored.second) }
		}
		if (missing.isEmpty()) return@use 0
		connection.prepareStatement(
			"""
			INSERT INTO work_title (work_id, title_raw, title_norm, lang, kind, weight)
			VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT (work_id, title_norm, kind) DO NOTHING
			""".trimIndent(),
		).use { statement ->
			missing.forEach { (title, key, weight) ->
				statement.setLong(1, title.workId)
				statement.setString(2, title.raw)
				statement.setString(3, key)
				statement.setString(4, title.lang)
				statement.setString(5, title.kind)
				statement.setDouble(6, weight)
				statement.addBatch()
			}
			statement.executeBatch().count { it != 0 }
		}
	}

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
					put("observations", movedObservations(connection, from))
					put("externals", movedExternalIds(connection, from))
					put("covers", movedCoverHashes(connection, from, into))
				}

				listOf(
					"UPDATE work_alias SET work_id = ? WHERE work_id = ?",
					"UPDATE work_alias_observation SET work_id = ? WHERE work_id = ?",
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
				rebuildRatingAggregate(connection, into)
				connection.prepareStatement("DELETE FROM work_rating_agg WHERE work_id = ?").use { statement ->
					statement.setLong(1, from)
					statement.executeUpdate()
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
			record?.get("observations")?.jsonArray?.forEach { element ->
				val observation = element.jsonObject
				connection.prepareStatement(
					"UPDATE work_alias_observation SET work_id = ? " +
						"WHERE work_id = ? AND source = ? AND source_key = ? AND user_id = ?",
				).use { statement ->
					statement.setLong(1, from)
					statement.setLong(2, into)
					statement.setString(3, observation.getValue("source").jsonPrimitive.content)
					statement.setString(4, observation.getValue("key").jsonPrimitive.content)
					statement.setString(5, observation.getValue("user").jsonPrimitive.content)
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
			rebuildRatingAggregate(connection, into)
			rebuildRatingAggregate(connection, from)

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

	private fun movedObservations(connection: java.sql.Connection, from: Long): JsonArray =
		connection.prepareStatement(
			"SELECT source, source_key, user_id FROM work_alias_observation WHERE work_id = ?",
		).use { statement ->
			statement.setLong(1, from)
			statement.executeQuery().use { rows ->
				buildJsonArray {
					while (rows.next()) {
						add(
							buildJsonObject {
								put("source", rows.getString(1))
								put("key", rows.getString(2))
								put("user", rows.getString(3))
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

	/** Rebuilds one denormalized rating row after a merge changes rating ownership. */
	private fun rebuildRatingAggregate(connection: java.sql.Connection, workId: Long) {
		connection.prepareStatement("DELETE FROM work_rating_agg WHERE work_id = ?").use { statement ->
			statement.setLong(1, workId)
			statement.executeUpdate()
		}
		connection.prepareStatement(
			"""
			INSERT INTO work_rating_agg (work_id, count, value_sum, mean, bayesian, histogram, updated_at)
			SELECT ?, count(*)::integer, sum(value)::bigint, avg(value)::real,
			       ((20.0 * COALESCE(
			           (SELECT sum(value_sum)::float8 / NULLIF(sum(count), 0) FROM rating_global_shard), 0
			       )) + sum(value)) / (20.0 + count(*)),
			       ARRAY[
			           count(*) FILTER (WHERE value BETWEEN 1 AND 2),
			           count(*) FILTER (WHERE value BETWEEN 3 AND 4),
			           count(*) FILTER (WHERE value BETWEEN 5 AND 6),
			           count(*) FILTER (WHERE value BETWEEN 7 AND 8),
			           count(*) FILTER (WHERE value BETWEEN 9 AND 10)
			       ]::integer[], now()
			FROM rating WHERE work_id = ?
			HAVING count(*) > 0
			""".trimIndent(),
		).use { statement ->
			statement.setLong(1, workId)
			statement.setLong(2, workId)
			statement.executeUpdate()
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
		const val ALIAS_AGREEMENT_REQUIRED = 5
		/** Stable, application-specific lock id; held only while committing a newly discovered work. */
		const val WORK_CREATION_LOCK = 0x4B52574FL
		/** Joined into every title lookup: a merged work is a redirect, not a candidate. */
		const val ACTIVE_WORK = "JOIN work w ON w.id = t.work_id AND w.merged_into IS NULL"

		/** A merge chain longer than this is a cycle, and following it forever is worse than stopping. */
		const val MAX_MERGE_HOPS = 8
	}
}
