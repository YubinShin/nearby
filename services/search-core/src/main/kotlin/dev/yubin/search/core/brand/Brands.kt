package dev.yubin.search.core.brand

/**
 * 이 장소의 브랜드가 무엇인지 정하는 **유일한 자리.**
 *
 * ── 왜 한 곳이어야 하나 ────────────────────────────────────────────────────
 * 전에는 규칙이 두 벌이었다. 색인 문서는 `복원분 + 시드 사전` 을 보고, 임베딩 문장과
 * Qdrant payload 는 `복원분` 만 봤다. 그래서 사전으로만 아는 브랜드를 **벡터 채널이 통째로
 * 몰랐다** — `CU` 를 치면 키워드 187건 / 벡터 0건, 하이브리드 합의 0/10 (크리틱 #21).
 *
 * 규칙이 두 벌이면 한쪽만 고치는 사고가 반드시 난다. 실제로 스타벅스 때 같은 사고를 겪고
 * 고쳤는데, 시드 사전을 붙이면서 또 냈다. **진입점을 하나로 두는 게 그 사고의 해결책이다.**
 *
 * ── 브랜드는 두 원천에서 온다 (형태소 사전과 같은 구조 — ADR 0008) ─────────
 *  1. **데이터가 알려주는 쪽** — 인허가와 좌표를 맞춰 복원한 값 (`place_brand`, 스타벅스 86건).
 *     상호명에 브랜드가 **아예 없던** 경우다.
 *  2. **사람만 아는 쪽** — 시드 사전 (`brands.tsv`). `CU = 씨유` 는 데이터의 성질이 아니라
 *     세상 지식이라 유도할 방법이 없다.
 * 복원분이 우선이다 — 그쪽은 **가게 하나를 보고** 판단했고, 사전은 이름 규칙일 뿐이다.
 *
 * 패키지를 따로 둔 이유: 색인·임베딩·벡터·읽기가 전부 이걸 쓴다. 어느 한쪽에 두면 나머지가
 * 그 패키지를 import 하게 되어 경계가 흐려진다 (#25).
 *
 * 모듈을 쪼갠 뒤(ADR 0011) 이 이유는 **더 강해졌다.** 색인기와 질의기가 따로 배포되므로,
 * 브랜드 규칙이 두 아티팩트에 각각 복사돼 있으면 한쪽만 고치는 사고를 컴파일러가 못 잡는다.
 * `search-core` 에 한 벌만 두는 것이 그 사고를 구조로 막는 유일한 방법이다.
 */
object Brands {

	/** 정규형 → 그 브랜드의 모든 표기(정규형 포함). 색인에는 전부 넣어야 어느 표기로 쳐도 걸린다. */
	val aliases: Map<String, List<String>> = load()

	/** 표기(공백 제거·소문자) → 정규형. 긴 표기부터 봐야 `이디야커피` 가 `이디야` 에 먼저 먹히지 않는다. */
	private val byLength: List<Pair<String, String>> =
		aliases.entries
			.flatMap { (canonical, forms) -> forms.map { normalize(it) to canonical } }
			.sortedByDescending { it.first.length }

	/**
	 * **이 장소의 브랜드.** 두 원천을 합치는 지점이다.
	 *
	 * @param recovered 인허가에서 복원한 값 (`PlaceRow.brand`). 있으면 그게 답이다.
	 */
	fun resolve(recovered: String?, name: String, branch: String? = null): String? =
		recovered?.takeIf { it.isNotBlank() } ?: canonical(name, branch)

	/**
	 * 상호명(+지점명)이 어떤 브랜드로 **시작하면** 그 정규형을 준다.
	 *
	 * '포함'이 아니라 '시작'인 이유: 프랜차이즈 상호는 브랜드가 앞에 온다(`씨유역삼점`).
	 * 포함으로 넓히면 `우리집CU앞분식` 같은 게 딸려 들어온다. 실측으로 오탐 0을 확인한 규칙이다
	 * (`CU%` 11건 전부 편의점, `매머드%` 전부 카페).
	 */
	fun canonical(name: String, branch: String? = null): String? {
		val text = normalize(name + (branch ?: ""))
		if (text.isEmpty()) return null
		return byLength.firstOrNull { (form, _) -> text.startsWith(form) }?.second
	}

	/** 색인에 넣을 검색용 문자열 — 정규형과 모든 변형을 함께 담는다. */
	fun searchText(canonical: String): String =
		aliases[canonical]?.joinToString(" ") ?: canonical

	/**
	 * **화면에 보여줄** 이름. 브랜드가 이름에 이미 있으면 붙이지 않는다.
	 *
	 * 상호가 `CU` 인 편의점에 브랜드 `CU` 를 또 붙이면 `CU CU` 가 된다(실측). 브랜드를 앞에
	 * 세우는 건 이름에서 **빠져 있던** 경우(`신사역` → `스타벅스 신사역`)를 위한 것이다.
	 * 정규형만 보면 안 된다 — `씨유역삼점` 은 정규형 `CU` 로 시작하지 않지만 이미 브랜드를
	 * 달고 있다. **모든 표기**로 확인한다.
	 */
	fun display(brand: String?, name: String): String {
		if (brand.isNullOrBlank()) return name
		val normalized = normalize(name)
		val forms = (aliases[brand] ?: listOf(brand)).map(::normalize)
		return if (forms.any { normalized.startsWith(it) }) name else "$brand $name"
	}

	/**
	 * **임베딩 문장에 넣을** 이름. 표시와 규칙이 **다르다.**
	 *
	 * 표시는 중복을 피하지만, 임베딩은 반대로 **정규형 토큰을 넣어야** 한다. `씨유역삼점` 의
	 * 문장에 `CU` 가 없으면 `CU` 로 친 질의와 의미가 가까워질 방법이 없다 — 벡터는 글자가
	 * 아니라 뜻으로 재지만, 그 뜻은 결국 문장에 있는 토큰에서 나온다.
	 *
	 * 그래서 기준이 [display] 와 다르다. display 는 **어떤 표기든** 이미 있으면 안 붙이지만,
	 * 여기서는 **정규형이 없을 때만** 붙인다.
	 *
	 * | 상호 | 정규형 | 결과 | 왜 |
	 * |---|---|---|---|
	 * | `씨유역삼점` | CU | `CU 씨유역삼점` | 정규형이 문장에 없다 → 넣는다 |
	 * | `스타벅스` | 스타벅스 | `스타벅스` | 이미 있다 → 넣으면 `스타벅스 스타벅스` 가 된다 |
	 * | `파리바게트논현점` | 파리바게뜨 | `파리바게뜨 파리바게트논현점` | 다른 표기라 둘 다 남긴다 |
	 */
	fun embedText(brand: String?, name: String): String {
		if (brand.isNullOrBlank()) return name
		return if (normalize(name).startsWith(normalize(brand))) name else "$brand $name"
	}

	private fun normalize(s: String) = s.replace(" ", "").lowercase()

	private fun load(): Map<String, List<String>> {
		val text = javaClass.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
			?: error("브랜드 시드가 없다: $RESOURCE")
		return text.lineSequence()
			.map { it.trim() }
			.filter { it.isNotEmpty() && !it.startsWith("#") }
			.map { line -> line.split("\t").map(String::trim).filter(String::isNotEmpty) }
			.filter { it.isNotEmpty() }
			// 같은 정규형이 두 줄에 나뉘어 있어도 합친다.
			.groupBy({ it.first() }, { it })
			.mapValues { (_, rows) -> rows.flatten().distinct() }
	}

	private const val RESOURCE = "/brands.tsv"
}
