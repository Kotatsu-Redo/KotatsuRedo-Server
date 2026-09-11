package io.kotatsuredo.server.identity

/**
 * Nicknames are user-chosen and deliberately **not** globally unique. Each carries a four-character
 * discriminator derived from the user id, which kills impersonation without creating a name
 * land-grab - and means there is no "that name is taken" flow to build (PLAN.md §1).
 *
 * Display form: `raph#4f2a`.
 */
object Nicknames {

	const val MIN_LENGTH = 2
	const val MAX_LENGTH = 24
	const val DISCRIMINATOR_LENGTH = 4

	/**
	 * Names that would let someone pose as the service. This is an identity concern, not a speech
	 * one - the word filter (M4a) is separate and applies on top.
	 */
	private val reserved = setOf(
		"admin", "administrator", "mod", "moderator", "staff", "system", "support",
		"kotatsu", "kotatsuredo", "official", "help", "root", "null", "deleted",
	)

	/** Zero-width and bidi controls: invisible characters used to fake another user's name. */
	private val forbiddenChars = setOf(
		'​', '‌', '‍', '‎', '‏', '⁠', '﻿',
		'‪', '‫', '‬', '‭', '‮',
	)

	fun discriminatorFor(userId: String): String = userId.take(DISCRIMINATOR_LENGTH).lowercase()

	fun display(nickname: String?, userId: String): String =
		"${nickname ?: "anon"}#${discriminatorFor(userId)}"

	fun validate(raw: String): NicknameResult {
		val trimmed = raw.trim()
		return when {
			trimmed.length < MIN_LENGTH -> NicknameResult.TooShort
			trimmed.length > MAX_LENGTH -> NicknameResult.TooLong
			trimmed.any { it in forbiddenChars } -> NicknameResult.InvalidCharacters
			trimmed.any { it.isISOControl() } -> NicknameResult.InvalidCharacters
			trimmed.contains('#') -> NicknameResult.InvalidCharacters
			normalize(trimmed) in reserved -> NicknameResult.Reserved
			else -> NicknameResult.Valid(trimmed)
		}
	}

	/**
	 * Fold the tricks used to slip past a reserved list: case, spacing, punctuation, and the digits
	 * that stand in for letters. `A d m 1 n` and `admin` collapse to the same string.
	 */
	private fun normalize(value: String): String = value
		.lowercase()
		.map { c ->
			when (c) {
				'0' -> 'o'; '1' -> 'i'; '3' -> 'e'; '4' -> 'a'; '5' -> 's'; '7' -> 't'; '@' -> 'a'
				else -> c
			}
		}
		.filter { it.isLetterOrDigit() }
		.joinToString("")
}

sealed interface NicknameResult {
	data class Valid(val nickname: String) : NicknameResult
	data object TooShort : NicknameResult
	data object TooLong : NicknameResult
	data object Reserved : NicknameResult
	data object InvalidCharacters : NicknameResult

	/**
	 * A term matched the word filter.
	 *
	 * Nicknames run the same filter as comments, and they need it more: a nickname is shown on every
	 * comment its owner writes, so one that gets through is on the screen far longer than any single
	 * message would be (PLAN.md §6).
	 */
	data class Blocked(val term: String, val tier: String, val blockId: Long?) : NicknameResult
}
