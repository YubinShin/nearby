import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	`java-library`
	id("io.spring.dependency-management")
}

dependencyManagement {
	imports { mavenBom(SpringBootPlugin.BOM_COORDINATES) }
}

val djlVersion = "0.36.0"

dependencies {
	api("org.springframework.boot:spring-boot-starter")

	api("org.springframework.boot:spring-boot-starter-data-elasticsearch")
	api("com.fasterxml.jackson.module:jackson-module-kotlin")

	api("ai.djl:api:$djlVersion")
	implementation("ai.djl.huggingface:tokenizers:$djlVersion")
	runtimeOnly("ai.djl.onnxruntime:onnxruntime-engine:$djlVersion")

	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")

	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
