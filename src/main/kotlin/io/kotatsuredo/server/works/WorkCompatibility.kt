package io.kotatsuredo.server.works

internal object WorkCompatibility {

	const val YEAR_TOLERANCE = 2

	private val knownTypes = setOf("manga", "manhwa", "manhua", "novel", "oneshot", "doujinshi")
	private val comicTypes = setOf("manga", "manhwa", "manhua")

	fun isCompatible(
		title: String,
		year: Int?,
		contentType: String?,
		metadata: Triple<String, Int?, String?>?,
	): Boolean {
		metadata ?: return false
		val (canonicalTitle, candidateYear, candidateType) = metadata
		if (TitleNormalizer.sequenceSignature(title) != TitleNormalizer.sequenceSignature(canonicalTitle)) return false
		if (year != null && candidateYear != null && kotlin.math.abs(year - candidateYear) > YEAR_TOLERANCE) return false
		return compatibleContentTypes(candidateType, contentType)
	}

	private fun compatibleContentTypes(a: String?, b: String?): Boolean {
		if (a == null || b == null || a.equals(b, ignoreCase = true)) return true
		val normalizedA = a.lowercase()
		val normalizedB = b.lowercase()
		if (normalizedA in comicTypes && normalizedB in comicTypes) return true
		return normalizedA !in knownTypes || normalizedB !in knownTypes
	}
}
