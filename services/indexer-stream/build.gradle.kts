// 아직 소스가 없다. 이 파일은 **이음매가 어디에 있는지** 를 못박아 두는 용도다 — README 참고.
//
// Spring Boot 플러그인을 아직 붙이지 않는다. 실행 가능한 앱이 아니라서 bootJar 를 만들 수 없고,
// 억지로 붙여 두면 "뜨긴 뜨는데 아무것도 안 하는 앱"이 생긴다. 그건 뼈대가 아니라 거짓말이다.
plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("io.spring.dependency-management")
}

dependencies {
	// 채워질 때 무엇에 기대게 되는지만 미리 적어 둔다: 문서 스키마·브랜드 규칙·임베딩은
	// `indexer-batch` 와 **같은 코드**를 써야 한다. 두 색인기가 다른 문서를 만들면
	// 어느 경로로 색인됐느냐에 따라 검색 결과가 달라진다 (ADR 0011).
	implementation(project(":search-core"))
}
