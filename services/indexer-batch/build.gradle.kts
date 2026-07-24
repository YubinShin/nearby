// 버전은 루트(`services/build.gradle.kts`)가 못박는다. 여기서는 "무엇인지"만 선언한다 —
// 이 모듈은 **실행 가능한 앱**이다(bootJar).
plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

dependencies {
	// --- 질의기와 공유하는 계약: 문서 스키마·브랜드 규칙·임베딩 모델 (ADR 0011) ---
	implementation(project(":search-core"))

	// --- Web: /admin 진입점. 리액티브(WebFlux) + 코루틴 (ADR 0006) ---
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")

	// --- 검색 엔진: Elasticsearch (인덱스 생성·bulk·alias 스왑) ---
	implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

	// --- 원천 창고: PostGIS 읽기 (R2DBC — 64k+ 행을 스트림으로) ---
	// **검색 API 에는 이 의존성이 없다.** 질의 경로가 원천 창고를 여는 일은 없어야 한다.
	implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
	runtimeOnly("org.postgresql:r2dbc-postgresql")

	// --- 공통 ---
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// 색인 lag 지표 노출 (/actuator/prometheus)
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// --- 테스트 ---
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-elasticsearch-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("io.projectreactor:reactor-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
