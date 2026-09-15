package io.kotatsuredo.server

import io.kotatsuredo.server.telemetry.ProbeLimits
import io.kotatsuredo.server.works.WorkLimits
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputBoundsTest {

	@Test
	fun `telemetry source identifiers use the parser id alphabet and are never truncated`() {
		assertTrue(ProbeLimits.isValidSource("MANGADEX_en-US.v2"))
		assertFalse(ProbeLimits.isValidSource("source with spaces"))
		assertFalse(ProbeLimits.isValidSource("x".repeat(ProbeLimits.MAX_SOURCE_NAME + 1)))
		assertFalse(ProbeLimits.isValidSource("source\nforged"))
	}

	@Test
	fun `work years are realistic and cannot overflow a smallint`() {
		assertTrue(WorkLimits.isValidYear(null))
		assertTrue(WorkLimits.isValidYear(2026))
		assertFalse(WorkLimits.isValidYear(999))
		assertFalse(WorkLimits.isValidYear(Int.MAX_VALUE))
	}
}
