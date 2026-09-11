package io.kotatsuredo.server

import io.kotatsuredo.server.comments.ContentFilter
import io.kotatsuredo.server.filter.FilterRepository
import io.kotatsuredo.server.filter.FilterService
import io.kotatsuredo.server.filter.FilterTier
import io.kotatsuredo.server.filter.WordFilter
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shipped word lists, judged the only way that matters: against ordinary sentences.
 *
 * A filter is easy to test in the direction nobody worries about. What decides whether this feels
 * strict or feels broken is the false-positive rate on real comments, and for *this* product the
 * biggest source of those is that the public lists are assembled from adult-site keywords and
 * therefore swallow the working vocabulary of manga - `hentai`, `ecchi`, `harem`, `tentacle` are all
 * in LDNOOBW, and all of them are ordinary words here.
 */
class WordListTest {

	private val source by lazy { PostgresTestBase.database.source }
	private val repository by lazy { FilterRepository(source) }
	private val filter by lazy { WordFilter(repository) }
	private val service by lazy { FilterService(repository, filter) }

	@BeforeTest
	fun clean() {
		PostgresTestBase.requireDatabase()
		source.connection.use { connection ->
			connection.createStatement().use {
				it.execute("TRUNCATE filter_block, filter_rule, filter_allow CASCADE")
			}
		}
		service.seed()
		service.reload()
	}

	private fun allowed(text: String, lang: String) = assertNull(
		filter.match(text, lang),
		"[$lang] should have been allowed: \"$text\" (matched ${filter.match(text, lang)?.term})",
	)

	private fun blocked(text: String, lang: String) =
		assertNotNull(filter.match(text, lang), "[$lang] should have been blocked: \"$text\"")

	// -- the collision that matters most here -------------------------------------------------------

	@Test
	fun `manga vocabulary is not profanity`() {
		// Every one of these words is in the public lists this ships from. Blocking them would make
		// the feature useless for discussing the medium it exists to discuss.
		listOf(
			"This one has way too much fanservice for me, dropped it at chapter 12.",
			"If you like ecchi harem comedies this is a solid pick honestly.",
			"The hentai doujinshi spinoff is not canon, ignore it completely.",
			"Classic isekai with a yandere lead and surprisingly good art.",
			"It gets shelved as shounen but the pacing is pure seinen.",
			"I dropped it because of the lolicon subplot, which was gross.",
		).forEach { allowed(it, "en") }
	}

	@Test
	fun `plot language survives`() {
		// Four hundred chapters of people killing demons is the genre. A filter that cannot discuss
		// it cannot host a review.
		listOf(
			"He is going to kill the demon king in the next arc, obviously.",
			"The assassin arc had the best fight choreography in the series.",
			"Way too much gore in this one, the execution scene was rough.",
			"The slave arc was handled badly and the author knows it.",
			// The single most important one: a reader warning another reader.
			"Content warning for the rape scene in chapter 40, it is graphic.",
		).forEach { allowed(it, "en") }
	}

	// -- ordinary sentences, every launch language --------------------------------------------------

	@Test
	fun `ordinary comments pass in every launch language`() {
		listOf(
			"en" to "The art in this chapter was genuinely spectacular, especially the fight.",
			"es" to "Este capitulo fue increible, el dibujo mejoro muchisimo desde el arco anterior.",
			"pt" to "Esse capitulo foi otimo, a arte melhorou muito desde o arco passado.",
			"fr" to "Ce chapitre etait vraiment excellent, le dessin s'ameliore beaucoup.",
			"de" to "Dieses Kapitel war wirklich gut, die Zeichnungen sind deutlich besser geworden.",
			"it" to "Questo capitolo e stato bellissimo, il disegno e migliorato molto.",
			"pl" to "Ten rozdzial byl swietny, rysunki sa duzo lepsze niz wczesniej.",
			"ru" to "Эта глава была великолепной, рисовка стала намного лучше.",
			"tr" to "Bu bolum gercekten harikaydi, cizimler oncekinden cok daha iyi.",
			"id" to "Chapter ini bagus sekali, gambarnya jauh lebih baik dari sebelumnya.",
			"vi" to "Chương này thực sự hay, nét vẽ đẹp hơn nhiều so với trước.",
			"ja" to "この章は本当に素晴らしかった。絵がとても綺麗になりました。",
			"zh" to "这一章真的很棒，画面比之前好很多，打斗也很精彩。",
			"ko" to "이번 화는 정말 좋았어요. 그림이 예전보다 훨씬 좋아졌습니다.",
			"th" to "ตอนนี้ดีมากเลย ภาพสวยขึ้นกว่าเดิมมาก",
		).forEach { (lang, text) -> allowed(text, lang) }
	}

	/**
	 * The bug the homoglyph fold used to cause.
	 *
	 * Folding Cyrillic lookalikes unconditionally turned `соска` - the Russian for "pacifier" - into
	 * `cocka`, which the *English* list then blocked as a near-miss for `cock`. A false positive
	 * invented entirely by the normaliser, in a language whose users would never find out why.
	 */
	@Test
	fun `russian words are not folded into english profanity`() {
		listOf("соска", "сосок", "кот сидит", "мама дома", "Привет всем").forEach {
			allowed("$it и глава была очень хорошая", "ru")
		}
		// While genuine mixed-script evasion still folds.
		assertNotNull(filter.match("what the fuсk was that ending", "en"), "mixed-script evasion missed")
	}

	// -- and it does still block ---------------------------------------------------------------------

	@Test
	fun `the common swearing is caught in every launch language`() {
		listOf(
			"en" to "this chapter was absolute shit honestly",
			"es" to "este capitulo es una mierda enorme",
			"pt" to "esse capitulo foi uma merda completa",
			"fr" to "ce chapitre est vraiment de la merde",
			"de" to "dieses kapitel war komplette scheisse",
			"it" to "questo capitolo e una merda totale",
			"pl" to "ten rozdzial to kurwa jakas tragedia",
			"ru" to "эта глава полное говно если честно",
			"tr" to "bu bolum tam bir siktir tarzi olmus",
			"id" to "chapter ini bangsat banget sumpah",
			"vi" to "chương này đéo ra gì cả",
		).forEach { (lang, text) -> blocked(text, lang) }
	}

	@Test
	fun `severe applies in every language, not just the one it was written for`() {
		// The only global tier. A slur in a French comment is caught by the same rule as in English.
		listOf("en", "fr", "ru", "ja", "id").forEach { lang ->
			val hit = assertNotNull(filter.match("quelle bande de nigger dans ce chapitre", lang))
			assertEquals(FilterTier.SEVERE, hit.tier, "[$lang] should have matched severe")
		}
	}

	// -- everything the corpus audit caught ----------------------------------------------------------

	/**
	 * Vietnamese carries its meaning in the tone marks the normaliser used to strip.
	 *
	 * Measured against ordinary Vietnamese prose, stripping them put the false-positive rate at
	 * **31.6%** - one sentence in three. `các` is the plural marker and among the commonest words in
	 * the language; folded, it landed on a blocklist entry.
	 */
	@Test
	fun `vietnamese keeps its tone marks`() {
		listOf(
			"Các chương mới ra hàng tuần và nét vẽ rất đẹp",
			"Tỉnh này xuất hiện trong chương trước của truyện",
			"Cho tôi biết bạn nghĩ gì về kết thúc này",
			"Nó được phân bố trong nhiều chương khác nhau",
			"Truyện này có nhiều nhân vật thú vị và hay",
			"Vành đai tiểu hành tinh trong truyện khoa học viễn tưởng",
		).forEach { allowed(it, "vi") }

		// While the words that are actually vulgar still are.
		blocked("chương này đéo ra gì cả thật sự", "vi")
	}

	/**
	 * Turkish has the same shape as Vietnamese: `şık` means chic, and stripping its diacritics lands
	 * it on a vulgarity.
	 *
	 * Keeping the marks makes the properly-spelled word safe. It does **not** rescue the same word
	 * typed without them, and cannot: undiacriticked `sik` is genuinely the vulgar word, so the two
	 * are indistinguishable by then. Turkish speakers often type without diacritics, which makes this
	 * a real residual false positive rather than a solved problem - and one for the panel's
	 * one-click allowlist, not for the normaliser.
	 */
	@Test
	fun `turkish keeps its diacritics`() {
		allowed("Çok şık bir bölüm olmuş, çizimler gerçekten güzel", "tr")
		allowed("Bu bölümün çizimleri gerçekten çok güzel olmuş bence", "tr")
		blocked("bu bolum tam bir siktir tarzi olmus gercekten", "tr")
	}

	/**
	 * Inflections and compounds, which is where an English dictionary quietly runs out.
	 *
	 * `passenger` was in the list and `passengers` was not; `classify` was and `reclassified` was
	 * not; `Lead-bismuth` folded into one string no dictionary would ever hold. Each was a real
	 * blocked sentence in the audit, and each is now handled by a rule rather than by another entry.
	 */
	@Test
	fun `english inflections, prefixes and compounds survive`() {
		listOf(
			"The passengers in that arc were barely characters at all",
			"The rules that constituted the tournament were never explained",
			"The analysts in this series are unreasonably good at their jobs",
			"That character gets reclassified about four times in one arc",
			"A lead-bismuth alloy turns up in the science chapters somehow",
			"It is set among the tallgrass prairie, which is a first-class setting",
			"She spent the whole chapter analysing the villain's motive",
		).forEach { allowed(it, "en") }
	}

	/** Splitting on hyphens must not hand anyone an evasion. */
	@Test
	fun `hyphens do not become an escape hatch`() {
		blocked("what the f-u-c-k was that ending supposed to mean", "en")
		blocked("this chapter was s-h-i-t from start to finish honestly", "en")
	}

	/**
	 * Ordinary words the public lists swept in, each found by the audit firing on real prose.
	 */
	@Test
	fun `ordinary words the source lists got wrong are gone`() {
		// es - `negro` is simply the colour black.
		allowed("El gato negro aparece en el primer capitulo de la serie", "es")
		// id - the Indonesian list was the weakest of the fifteen.
		listOf(
			"Ruang kelas di chapter ini digambar dengan sangat bagus",
			"Pertandingan bola di arc ini seru sekali sejujurnya",
			"Tokoh asing itu muncul lagi di chapter kemarin",
		).forEach { allowed(it, "id") }
		// pt
		allowed("A mama do bebe aparece numa cena tocante deste capitulo", "pt")
		// Drug vocabulary is not profanity, and `heroína` is also the Spanish for heroine.
		allowed("La heroina de esta historia es mucho mejor que el protagonista", "es")
	}

	/** `ratio` and `sheep` are ordinary English words; flagging every "the ratio of" is noise. */
	@Test
	fun `the watch list does not flag ordinary english`() {
		allowed("The ratio of action to dialogue is finally right in this arc", "en")
		allowed("The sheep in the background panel are weirdly detailed", "en")
		// While the drama vocabulary it exists for still flags.
		assertNotNull(filter.match("this whole arc is completely overrated honestly", "en"))
	}

	// -- structural guarantees about the lists themselves --------------------------------------------

	@Test
	fun `no rule is short enough to be catastrophic`() {
		// `severe` matches as a substring, so a one-character rule blocks every comment containing
		// that letter. V2's English list ships "b", "c" and "f".
		val tooShort = repository.activeRules().filter { it.term.length < 2 }
		assertTrue(tooShort.isEmpty(), "rules too short to be safe: ${tooShort.map { it.termRaw }}")

		// Short severe terms are allowed, because the matcher only applies them as whole tokens - a
		// three-character slur is real and `жид` sits inside the ordinary Russian for "liquid".
		val shortSevere = repository.activeRules()
			.filter { it.tier == FilterTier.SEVERE && it.term.length < WordFilter.MIN_SEVERE_SUBSTRING_LENGTH }
		assertTrue(shortSevere.isNotEmpty(), "expected some short severe terms to exercise this path")
		allowed("эта жидкость выглядит странно в этой главе", "ru")
		blocked("этот жид опять пишет ерунду", "ru")
	}

	/**
	 * Every `severe` term that has an innocent superstring must have that superstring in the
	 * dictionary, or the substring tier blocks an ordinary word.
	 */
	@Test
	fun `severe terms do not eat the words that contain them`() {
		listOf(
			"raccoon", "cocoon", "tycoon",           // coon
			"suspicious", "despicable", "spice",     // spic
			"pakistani",                             // paki
			"retardant",                             // retard
		).forEach { word ->
			allowed("the $word chapter was a good read all the way through", "en")
		}
	}

	@Test
	fun `every launch language actually has rules`() {
		val byLanguage = repository.activeRules()
			.filter { it.tier == FilterTier.PROFANITY }
			.groupingBy { it.lang }
			.eachCount()

		listOf("en", "es", "pt", "fr", "de", "it", "pl", "ru", "tr", "id", "vi", "ja", "zh", "ko", "th")
			.forEach { lang ->
				val count = byLanguage[lang] ?: 0
				// Not a quality bar - a language with ten rules is a language nobody curated. It is a
				// tripwire for the failure mode where a file is renamed or fails to parse and the
				// language silently stops being filtered at all.
				assertTrue(count >= MIN_RULES_PER_LANGUAGE, "[$lang] has only $count rules")
			}
	}

	@Test
	fun `the seed is large enough to be worth having and small enough to have been read`() {
		val total = repository.activeRules().size
		assertTrue(total > 2000, "only $total rules seeded")
		// The whole argument against shipping V2 raw: 50,000 terms nobody has read is not coverage,
		// it is an unreviewed liability with a false-positive rate nobody can predict.
		assertTrue(total < 5000, "$total rules is past the point anyone has actually reviewed")
	}

	private companion object {
		const val MIN_RULES_PER_LANGUAGE = 20

		/** Unused directly; kept so the import of ContentFilter documents what `match` feeds. */
		val CONTEXT = ContentFilter.Context(null, "comment")
	}
}
