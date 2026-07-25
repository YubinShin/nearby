import org.springframework.boot.gradle.plugin.SpringBootPlugin

/**
 * **색인기와 질의기가 어긋나면 조용히 망가지는 것**만 담는 라이브러리 (ADR 0011).
 *
 * 실행 가능한 앱이 아니라서 Spring Boot 플러그인을 적용하지 않는다 — 대신 BOM 만 가져와
 * 라이브러리 버전을 두 앱과 정렬한다. `bootJar` 를 만들었다가 꺼는 것보다, 애초에 앱이
 * 아니라고 선언하는 편이 이 모듈이 무엇인지 분명하다.
 */
plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	`java-library`
	id("io.spring.dependency-management")
}

dependencyManagement {
	imports { mavenBom(SpringBootPlugin.BOM_COORDINATES) }
}

/** DJL(Deep Java Library) — 임베딩 모델(ONNX) 추론 (ADR 0010). */
val djlVersion = "0.36.0"

dependencies {
	// --- 스프링: @Component·@Value·@ConditionalOnProperty 만 쓴다 (웹 서버는 앱의 몫) ---
	// api 인 이유: 두 앱이 이 모듈의 빈을 컴포넌트 스캔으로 집어가므로 어노테이션이 노출돼야 한다.
	api("org.springframework.boot:spring-boot-starter")

	/*
	 * --- 여기에 **웹 스택이 없는 이유** (ADR 0013) ---
	 *
	 * 전에는 `api("spring-boot-starter-webflux")` 가 있었다. core 의 `QdrantStore` 가 WebClient 를
	 * 썼기 때문인데, `api` 라서 **이 모듈을 쓰는 모든 앱에 WebFlux 가 딸려 갔다.** 그래서 동시성이
	 * 1인 배치 색인기까지 리액티브 런타임을 짊어졌다.
	 *
	 * 지금은 Qdrant **호출 방법**을 각 앱이 소유하고(질의기=WebClient, 색인기=RestClient),
	 * core 에는 두 앱이 같아야 하는 **규칙**만 남는다(`QdrantContract`). 그 결과
	 * `indexer-batch` 의 클래스패스에서 reactor 가 완전히 사라졌다.
	 *
	 * 규칙: **core 는 계약을 담고, I/O 방법은 앱이 고른다.** 여기에 클라이언트 라이브러리를
	 * 추가하려는 순간 그 선택을 모든 앱에 강요하게 된다.
	 */

	// --- 버전 도장(IndexMetaStore)을 ES 에 읽고 쓴다. 두 앱이 같은 형식을 쓰게 하는 것이 요점 ---
	api("org.springframework.boot:spring-boot-starter-data-elasticsearch")

	// ES 자바 클라이언트는 **Jackson 2** 를 쓴다(Spring Boot 4 의 웹 직렬화는 Jackson 3).
	// 그쪽 Kotlin 모듈이 없으면 `val` 뿐인 data class 가 조용히 전부 기본값이 된다 —
	// 이유는 EsJsonpMapperConfig 주석 참고. api 인 이유: 두 앱의 런타임에도 있어야 한다.
	api("com.fasterxml.jackson.module:jackson-module-kotlin")

	// --- 임베딩 추론. 색인·질의가 **같은 모델·같은 전처리**를 쓰게 하는 것이 이 모듈의 존재 이유 ---
	api("ai.djl:api:$djlVersion")
	implementation("ai.djl.huggingface:tokenizers:$djlVersion")
	runtimeOnly("ai.djl.onnxruntime:onnxruntime-engine:$djlVersion")

	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// --- 테스트 ---
	// 코루틴 테스트 의존성이 없는 게 정상이다 — core 에 suspend 함수가 하나도 없다 (ADR 0013).
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
