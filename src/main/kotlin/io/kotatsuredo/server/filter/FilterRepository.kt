package io.kotatsuredo.server.filter

import io.kotatsuredo.server.identity.Nicknames
import io.kotatsuredo.server.identity.TrustTier
import java.sql.ResultSet
import java.time.OffsetDateTime
import javax.sql.DataSource

class FilterRepository(private val dataSource: DataSource) {
	data class RuleSeed(
		val term: String,
		val termRaw: String,
		val tier: FilterTier,
		val lang: String?,
		val source: String,
	)

	// -- rules -----------------------------------------------------------------------------------

	/** Everything the automaton is built from: enabled, not self-demoted. */
	fun activeRules(): List<FilterRule> = query("WHERE is_enabled AND demoted_at IS NULL")

	fun allRules(limit: Int): List<FilterRule> = query("ORDER BY tier, lang NULLS FIRST, term LIMIT $limit")

	fun rule(id: Long): FilterRule? = query("WHERE id = ?") { it.setLong(1, id) }.firstOrNull()

	/**
	 * @return true when the rule was new. Idempotent, so seeding the lists on every boot is safe and
	 *  cannot undo a moderator's later edit to the same term.
	 */
	fun addRule(term: String, termRaw: String, tier: FilterTier, lang: String?, source: String): Boolean =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO filter_rule (term, term_raw, tier, lang, source) VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (term, tier, COALESCE(lang, '*')) DO NOTHING
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, term)
				statement.setString(2, termRaw)
				statement.setShort(3, tier.code)
				statement.setString(4, lang)
				statement.setString(5, source)
				statement.executeUpdate() > 0
			}
		}

	/** Inserts an idempotent seed corpus with one checkout, one transaction, and JDBC batches. */
	fun addRules(rules: Collection<RuleSeed>): Int {
		if (rules.isEmpty()) return 0
		return dataSource.connection.use { connection ->
			connection.autoCommit = false
			try {
				val inserted = connection.prepareStatement(
					"""
					INSERT INTO filter_rule (term, term_raw, tier, lang, source) VALUES (?, ?, ?, ?, ?)
					ON CONFLICT (term, tier, COALESCE(lang, '*')) DO NOTHING
					""".trimIndent(),
				).use { statement ->
					rules.forEach { rule ->
						statement.setString(1, rule.term)
						statement.setString(2, rule.termRaw)
						statement.setShort(3, rule.tier.code)
						statement.setString(4, rule.lang)
						statement.setString(5, rule.source)
						statement.addBatch()
					}
					statement.executeBatch().sumOf { count -> if (count > 0) count else 0 }
				}
				connection.commit()
				inserted
			} catch (error: Exception) {
				connection.rollback()
				throw error
			} finally {
				connection.autoCommit = true
			}
		}
	}

	fun setRuleEnabled(id: Long, enabled: Boolean): Boolean = update(
		"UPDATE filter_rule SET is_enabled = ?, updated_at = now() WHERE id = ?",
	) {
		it.setBoolean(1, enabled)
		it.setLong(2, id)
	}

	/** Distinct from disabling: this is the rule turning itself off, not a human deciding. */
	fun demoteRule(id: Long): Boolean = update(
		"UPDATE filter_rule SET demoted_at = now(), updated_at = now() WHERE id = ? AND demoted_at IS NULL",
	) { it.setLong(1, id) }

	fun restoreRule(id: Long): Boolean = update(
		"UPDATE filter_rule SET demoted_at = NULL, is_enabled = TRUE, updated_at = now() WHERE id = ?",
	) { it.setLong(1, id) }

	// -- allowlist -------------------------------------------------------------------------------

	fun allowedTerms(): Set<String> = dataSource.connection.use { connection ->
		connection.createStatement().use { statement ->
			statement.executeQuery("SELECT term FROM filter_allow").use { rows ->
				buildSet { while (rows.next()) add(rows.getString(1)) }
			}
		}
	}

	fun allow(term: String, origin: String, note: String?, moderatorId: String?): Boolean =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"""
				INSERT INTO filter_allow (term, origin, note, by_moderator) VALUES (?, ?, ?, ?)
				ON CONFLICT (term) DO NOTHING
				""".trimIndent(),
			).use { statement ->
				statement.setString(1, term)
				statement.setString(2, origin)
				statement.setString(3, note)
				statement.setString(4, moderatorId)
				statement.executeUpdate() > 0
			}
		}

	fun disallow(term: String): Boolean =
		update("DELETE FROM filter_allow WHERE term = ?") { it.setString(1, term) }

	/**
	 * Tokens appearing in a known work title, which are exempt from `profanity`.
	 *
	 * People discuss works with deliberately crude titles, and romanised Japanese collides with
	 * profanity in several European languages - `Kuso` and `Shito` are ordinary words in a title and
	 * unfortunate ones in a wordlist.
	 */
	fun catalogueTitleTokens(limit: Int): Set<String> = dataSource.connection.use { connection ->
		connection.prepareStatement("SELECT title_norm FROM work_title LIMIT ?").use { statement ->
			statement.setInt(1, limit)
			statement.executeQuery().use { rows ->
				buildSet {
					while (rows.next()) {
						FilterNormalizer.tokenize(rows.getString(1))
							.map(FilterNormalizer::setA)
							.filter { it.length >= MIN_TITLE_TOKEN }
							.forEach(::add)
					}
				}
			}
		}
	}

	// -- the block log ---------------------------------------------------------------------------

	fun recordBlock(
		ruleId: Long?,
		term: String,
		tier: FilterTier,
		lang: String?,
		surface: String,
		userId: String?,
		context: String?,
	): Long = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			INSERT INTO filter_block (rule_id, term, tier, lang, surface, user_id, context)
			VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
			""".trimIndent(),
		).use { statement ->
			ruleId?.let { statement.setLong(1, it) } ?: statement.setNull(1, java.sql.Types.BIGINT)
			statement.setString(2, term)
			statement.setShort(3, tier.code)
			statement.setString(4, lang)
			statement.setString(5, surface)
			statement.setString(6, userId)
			statement.setString(7, context?.take(FilterRules.CONTEXT_LENGTH))
			statement.executeQuery().use { it.next(); it.getLong(1) }
		}
	}

	/**
	 * The user pressed "this was wrong" in the rejection dialog.
	 *
	 * One account gets one signal per rule, not one signal per generated block row. Locking the user
	 * serialises concurrent disputes for different rows of the same rule, so two simultaneous requests
	 * cannot both pass the NOT EXISTS check.
	 */
	fun dispute(blockId: Long, userId: String): Boolean = dataSource.connection.use { connection ->
		connection.autoCommit = false
		try {
			val ownerExists = connection.prepareStatement(
				"SELECT id FROM app_user WHERE id = ? FOR UPDATE",
			).use { statement ->
				statement.setString(1, userId)
				statement.executeQuery().use { it.next() }
			}
			val updated = if (!ownerExists) 0 else connection.prepareStatement(
				"""
				UPDATE filter_block AS target SET disputed_at = now()
				WHERE target.id = ? AND target.user_id = ? AND target.disputed_at IS NULL
				  AND NOT EXISTS (
				      SELECT 1 FROM filter_block AS prior
				      WHERE prior.user_id = target.user_id
				        AND prior.rule_id IS NOT DISTINCT FROM target.rule_id
				        AND prior.disputed_at IS NOT NULL
				  )
				""".trimIndent(),
			).use { statement ->
				statement.setLong(1, blockId)
				statement.setString(2, userId)
				statement.executeUpdate()
			}
			connection.commit()
			updated > 0
		} catch (error: Exception) {
			connection.rollback()
			throw error
		} finally {
			connection.autoCommit = true
		}
	}

	fun resolveBlock(blockId: Long, resolution: String): Boolean = update(
		"UPDATE filter_block SET reviewed_at = now(), resolution = ? WHERE id = ?",
	) {
		it.setString(1, resolution)
		it.setLong(2, blockId)
	}

	fun block(id: Long): FilterBlockRecord? = blocks("WHERE b.id = ?", 1) { it.setLong(1, id) }.firstOrNull()

	/** Disputed blocks first: those are the ones already known to be wrong. */
	fun openBlocks(limit: Int): List<FilterBlockRecord> = blocks(
		"WHERE b.reviewed_at IS NULL ORDER BY b.disputed_at DESC NULLS LAST, b.created_at DESC LIMIT ?",
		1,
	) { it.setInt(1, limit) }

	fun recentBlocks(limit: Int): List<FilterBlockRecord> =
		blocks("ORDER BY b.created_at DESC LIMIT ?", 1) { it.setInt(1, limit) }

	/** Per-rule false-positive rates, worst first. What auto-demotion reads. */
	fun ruleStats(minBlocks: Int): List<RuleStats> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT b.rule_id, b.term, b.tier, b.lang, count(*) AS blocks,
			       count(b.disputed_at) AS disputed
			FROM filter_block b
			GROUP BY b.rule_id, b.term, b.tier, b.lang
			HAVING count(*) >= ?
			ORDER BY count(b.disputed_at)::float / count(*) DESC, count(*) DESC
			""".trimIndent(),
		).use { statement ->
			statement.setInt(1, minBlocks)
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(
							RuleStats(
								ruleId = rows.getLong(1).takeUnless { rows.wasNull() },
								term = rows.getString(2),
								tier = FilterTier.of(rows.getShort(3)),
								lang = rows.getString(4),
								blocks = rows.getInt(5),
								disputed = rows.getInt(6),
							),
						)
					}
				}
			}
		}
	}

	/** Independent, settled-account signals used exclusively by automatic demotion. */
	fun autoDemotionStats(minReporters: Int): List<RuleStats> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT b.rule_id, b.term, b.tier, b.lang,
			       count(DISTINCT b.user_id) AS reporters,
			       count(DISTINCT b.user_id) FILTER (WHERE b.disputed_at IS NOT NULL) AS disputed
			FROM filter_block b
			JOIN user_trust trust ON trust.user_id = b.user_id
			JOIN app_user account ON account.id = b.user_id
			WHERE b.rule_id IS NOT NULL AND trust.tier >= ?
			  AND NOT account.is_banned AND NOT account.is_shadowbanned
			GROUP BY b.rule_id, b.term, b.tier, b.lang
			HAVING count(DISTINCT b.user_id) >= ?
			ORDER BY count(DISTINCT b.user_id) FILTER (WHERE b.disputed_at IS NOT NULL)::float /
			         count(DISTINCT b.user_id) DESC,
			         count(DISTINCT b.user_id) DESC
			""".trimIndent(),
		).use { statement ->
			statement.setInt(1, TrustTier.ESTABLISHED.level)
			statement.setInt(2, minReporters)
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(
							RuleStats(
								ruleId = rows.getLong(1),
								term = rows.getString(2),
								tier = FilterTier.of(rows.getShort(3)),
								lang = rows.getString(4),
								blocks = rows.getInt(5),
								disputed = rows.getInt(6),
							),
						)
					}
				}
			}
		}
	}

	/**
	 * Block rates per language.
	 *
	 * An outlier means a bad list, not a rude userbase - and without watching this, a broken `pt` or
	 * `tr` list quietly ruins the feature for that language and nobody finds out.
	 */
	fun languageStats(): List<LanguageStats> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT lang, count(*), count(disputed_at) FROM filter_block
			GROUP BY lang ORDER BY count(*) DESC
			""".trimIndent(),
		).use { statement ->
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						add(LanguageStats(rows.getString(1), rows.getInt(2), rows.getInt(3)))
					}
				}
			}
		}
	}

	/** Drops the retained text once the review window passes. The rows, and the counts, stay. */
	fun purgeOldContext(before: OffsetDateTime): Int = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"UPDATE filter_block SET context = NULL WHERE context IS NOT NULL AND created_at < ?",
		).use { statement ->
			statement.setObject(1, before)
			statement.executeUpdate()
		}
	}

	// -- plumbing --------------------------------------------------------------------------------

	private fun query(clause: String, bind: (java.sql.PreparedStatement) -> Unit = {}): List<FilterRule> =
		dataSource.connection.use { connection ->
			connection.prepareStatement(
				"SELECT id, term, term_raw, tier, lang, is_enabled, demoted_at, source FROM filter_rule $clause",
			).use { statement ->
				bind(statement)
				statement.executeQuery().use { rows ->
					buildList { while (rows.next()) add(rows.toRule()) }
				}
			}
		}

	private fun blocks(
		clause: String,
		@Suppress("UNUSED_PARAMETER") binds: Int,
		bind: (java.sql.PreparedStatement) -> Unit,
	): List<FilterBlockRecord> = dataSource.connection.use { connection ->
		connection.prepareStatement(
			"""
			SELECT b.id, b.rule_id, b.term, b.tier, b.lang, b.surface, b.user_id, u.nickname,
			       b.context, b.created_at, b.disputed_at, b.reviewed_at, b.resolution
			FROM filter_block b LEFT JOIN app_user u ON u.id = b.user_id
			$clause
			""".trimIndent(),
		).use { statement ->
			bind(statement)
			statement.executeQuery().use { rows ->
				buildList {
					while (rows.next()) {
						val userId = rows.getString(7)
						add(
							FilterBlockRecord(
								id = rows.getLong(1),
								ruleId = rows.getLong(2).takeUnless { rows.wasNull() },
								term = rows.getString(3),
								tier = FilterTier.of(rows.getShort(4)),
								lang = rows.getString(5),
								surface = rows.getString(6),
								userId = userId,
								userName = userId?.let { Nicknames.display(rows.getString(8), it) },
								context = rows.getString(9),
								createdAt = rows.getObject(10, OffsetDateTime::class.java),
								disputedAt = rows.getObject(11, OffsetDateTime::class.java),
								reviewedAt = rows.getObject(12, OffsetDateTime::class.java),
								resolution = rows.getString(13),
							),
						)
					}
				}
			}
		}
	}

	private fun update(sql: String, bind: (java.sql.PreparedStatement) -> Unit): Boolean =
		dataSource.connection.use { connection ->
			connection.prepareStatement(sql).use { statement ->
				bind(statement)
				statement.executeUpdate() > 0
			}
		}

	private fun ResultSet.toRule() = FilterRule(
		id = getLong("id"),
		term = getString("term"),
		termRaw = getString("term_raw"),
		tier = FilterTier.of(getShort("tier")),
		lang = getString("lang"),
		isEnabled = getBoolean("is_enabled"),
		demotedAt = getObject("demoted_at", OffsetDateTime::class.java),
		source = getString("source"),
	)

	private companion object {
		/** Below this a title token is too short to be worth exempting and too likely to collide. */
		const val MIN_TITLE_TOKEN = 3
	}
}
