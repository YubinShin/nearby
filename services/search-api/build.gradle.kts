plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

val mockitoAgent: Configuration by configurations.creating

dependencies {
	implementation(project(":search-core"))

	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")

	implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

	implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:3.0.0")

	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-elasticsearch-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("io.projectreactor:reactor-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	testImplementation("org.mockito:mockito-core")
	mockitoAgent("org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test> {
	jvmArgumentProviders.add(
		CommandLineArgumentProvider { listOf("-javaagent:${mockitoAgent.asPath}") },
	)
}
