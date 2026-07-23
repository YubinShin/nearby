package dev.yubin.search.index

/**
 * 브랜드 시드 사전 — 사람이 관리하는 목록(`brands.tsv`)으로 상호명에서 브랜드를 알아낸다.
 *
 * **왜 자동이 아닌가.** 상호명 앞말 빈도를 세어 브랜드를 뽑아보려 했더니 상위가
 * `에이`(923)·`제이`(901)·`강남`(852)·`세무법인`(421) 이었다. 브랜드가 아니라 흔한 음절이다.
 * `CU = 씨유` 는 데이터의 성질이 아니라 **세상 지식**이라 데이터에서 유도할 방법이 없다.
 *
 * 그래서 브랜드는 두 갈래로 채워진다 — 형태소 사전과 같은 구조다 (ADR 0008).
 *  - **데이터가 알려주는 쪽**: 인허가와 좌표를 맞춰 복원한 것 (`place_brand`, 스타벅스 86건)
 *  - **사람만 아는 쪽**: 여기 (`brands.tsv`, 표기가 갈린 브랜드)
 */
object BrandDictionary {

	/** 정규형 → 그 브랜드의 모든 표기(정규형 포함). 색인에는 전부 넣어야 어느 표기로 쳐도 걸린다. */
	val aliases: Map<String, List<String>> = load()

	/** 표기(공백 제거·소문자) → 정규형. 긴 표기부터 봐야 `이디야커피` 가 `이디야` 에 먼저 먹히지 않는다. */
	private val byLength: List<Pair<String, String>> =
		aliases.entries
			.flatMap { (canonical, forms) -> forms.map { normalize(it) to canonical } }
			.sortedByDescending { it.first.length }

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
