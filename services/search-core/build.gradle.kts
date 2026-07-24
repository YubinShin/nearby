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

	// --- Qdrant 접근(QdrantStore)이 WebClient + 코루틴을 쓴다 (ADR 0006, 0007) ---
	api("org.springframework.boot:spring-boot-starter-webflux")
	api("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

	// --- 임베딩 추론. 색인·질의가 **같은 모델·같은 전처리**를 쓰게 하는 것이 이 모듈의 존재 이유 ---
	api("ai.djl:api:$djlVersion")
	implementation("ai.djl.huggingface:tokenizers:$djlVersion")
	runtimeOnly("ai.djl.onnxruntime:onnxruntime-engine:$djlVersion")

	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// --- 테스트 ---
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
