plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.yubin"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

/** DJL(Deep Java Library) — 임베딩 모델(ONNX) 추론 (ADR 0010). */
val djlVersion = "0.36.0"

dependencies {
	// --- Web: 리액티브(WebFlux) + 코루틴 (ADR 0006) ---
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")

	// --- 검색 엔진: Elasticsearch (리액티브 클라이언트 사용) ---
	implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

	// --- 원천 창고: PostGIS 읽기 (R2DBC — WebFlux와 정합, 스트림 색인) ---
	implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
	runtimeOnly("org.postgresql:r2dbc-postgresql")

	// --- 벡터(뜻) 검색: 임베딩 추론을 JVM 안에서 (ADR 0010) ---
	// 파이썬 추론 서버를 따로 띄우지 않는 이유: 색인과 질의가 **같은 모델·같은 전처리**를 쓰도록
	// 코드로 강제하기 위해서. 모델을 떼어낼 시점의 판단 기준은 ADR 0010 에 적어둔다.
	implementation("ai.djl:api:$djlVersion")
	implementation("ai.djl.huggingface:tokenizers:$djlVersion")
	runtimeOnly("ai.djl.onnxruntime:onnxruntime-engine:$djlVersion")

	// --- 공통 ---
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// 질의 지연·색인 lag 지표 노출 (/actuator/prometheus)
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	// --- 테스트 ---
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-elasticsearch-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("io.projectreactor:reactor-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
