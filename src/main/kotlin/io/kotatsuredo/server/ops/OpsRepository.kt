package io.kotatsuredo.server.ops

import javax.sql.DataSource

/**
 * The health numbers that live in the database rather than in memory.
 *
 * Between these and [ServerMetrics], the panel can answer the questions that previously needed an
 * ssh session: are requests being turned away, is enrichment keeping up, and is anything quietly
 * piling up in a queue nobody looks at.
 */
data class OpsCounters(
	/** Works created from a source's own words and still waiting for a catalogue. */
	val enrichmentPending: Int,
	val enrichmentDue: Int,
	/** Entries that have already failed at least once: the shape of a catalogue being unreachable. */
	val enrichmentDeferred: Int,
	val oldestPendingSeconds: Long?,
	val worksCreatedDay: Int,
	val autoMergesDay: Int,
	val autoMergesUndone: Int,
	val disputesOpen: Int,
	/** Works no catalogue has ever described: what the backfill button would queue. */
	val backfillCandidates: Int,
)

class OpsRepository(private val dataSource: DataSource) {

	fun counters(): OpsCounters = dataSource.connection.use { connection ->
		fun scalar(sql: String): Long = connection.prepareStatement(sql).use { statement ->
			statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
		}

		// Every one of these is about work still outstanding. Counting finished entries made the
		// panel report a queue of 149 where 25 were actually waiting, and an oldest-entry age that
		// only ever grew - a health view whose numbers drift from reality is worse than none.
		val oldest = connection.prepareStatement(
			"SELECT EXTRACT(EPOCH FROM now() - min(created_at)) FROM work_enrichment WHERE done_at IS NULL",
		).use { statement ->
			statement.executeQuery().use { rows ->
				if (rows.next()) rows.getLong(1).takeUnless { rows.wasNull() } else null
			}
		}

		OpsCounters(
			enrichmentPending = scalar("SELECT count(*) FROM work_enrichment WHERE done_at IS NULL").toInt(),
			enrichmentDue = scalar(
				"SELECT count(*) FROM work_enrichment WHERE done_at IS NULL AND next_attempt_at <= now()",
			).toInt(),
			enrichmentDeferred = scalar(
				"SELECT count(*) FROM work_enrichment WHERE done_at IS NULL AND attempts > 0",
			).toInt(),
			oldestPendingSeconds = oldest,
			worksCreatedDay = scalar("SELECT count(*) FROM work WHERE created_at > now() - interval '24 hours'").toInt(),
			autoMergesDay = scalar(
				"SELECT count(*) FROM work_merge_log WHERE merged_at > now() - interval '24 hours' " +
					"AND reason IN ('auto_dedupe', 'catalogue_enrichment')",
			).toInt(),
			autoMergesUndone = scalar(
				"SELECT count(*) FROM work_merge_log WHERE undone_at IS NOT NULL",
			).toInt(),
			disputesOpen = scalar("SELECT count(*) FROM work_link_dispute WHERE resolved_at IS NULL").toInt(),
			backfillCandidates = scalar(
				"""
				SELECT count(*) FROM work w
				LEFT JOIN work_enrichment e ON e.work_id = w.id
				LEFT JOIN (SELECT DISTINCT work_id FROM work_external_id) x ON x.work_id = w.id
				WHERE w.merged_into IS NULL AND e.work_id IS NULL AND x.work_id IS NULL
				""".trimIndent(),
			).toInt(),
		)
	}
}
