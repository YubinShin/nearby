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

	/*
	 * --- Web: /admin·/actuator 진입점. **MVC(Tomcat)** 다 (ADR 0013) ---
	 *
	 * 질의기는 WebFlux 인데(ADR 0006) 여기는 MVC 인 게 의도다. 리액티브의 이득은 **동시 연결이
	 * 많을 때** 나오는데, 색인기의 동시성은 1(한 번에 job 하나)이라 그 축이 아예 없다. 대신 요청당
	 * CPU 오버헤드와 디버깅 비용은 그대로 낸다 — 실제로 취소 경로에서 r2dbc 누수를 냈다.
	 *
	 * 이 앱에서 서버가 하는 일은 **관리·관측**뿐이다: job 트리거(즉시 202 반환), 진행 조회,
	 * k8s 프로브, lag 지표 스크레이프. 색인 자체는 Batch 의 job 스레드에서 돈다.
	 *
	 * 코루틴이 없는 것도 의도다 — 이 모듈에 `suspend` 함수가 하나도 없다.
	 */
	implementation("org.springframework.boot:spring-boot-starter-web")

	// --- 검색 엔진: Elasticsearch (인덱스 생성·bulk·alias 스왑) ---
	implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

	// --- 색인 실행 런타임: Spring Batch (ADR 0013) ---
	// job/step/chunk·재시작·JobRepository 를 프레임워크가 갖고 있다. 전에는 이걸 손으로
	// 재구현하고 있었다(CheckpointStore = 가난한 자의 체크포인트).
	implementation("org.springframework.boot:spring-boot-starter-batch")

	// --- 원천 창고: PostGIS 읽기 (JDBC) ---
	// **검색 API 에는 이 의존성이 없다.** 질의 경로가 원천 창고를 여는 일은 없어야 한다.
	// R2DBC 에서 JDBC 로 내려온 이유는 ADR 0013 — Spring Batch 의 chunk 는 트랜잭션
	// 경계가 스레드에 묶인 블로킹 모델이고, JobRepository 도 같은 DataSource 를 쓴다.
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	runtimeOnly("org.postgresql:postgresql")

	// --- 공통 ---
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// 색인 lag 지표 노출 (/actuator/prometheus)
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// --- 테스트 ---
	// Boot 4 는 테스트 스타터를 웹 스택별로 쪼갰다 — MVC 용은 `webmvc-test` 다
	// (`web-test` 라는 이름은 없다. WebFlux 쪽은 `webflux-test`).
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// job 을 테스트에서 직접 돌리는 도구 (JobLauncherTestUtils·JobRepositoryTestUtils)
	testImplementation("org.springframework.batch:spring-batch-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-elasticsearch-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
