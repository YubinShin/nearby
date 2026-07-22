plugins {
	java
}

group = "dev.yubin"
version = "0.0.1"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// 플러그인 zip에 함께 넣을 KOMORAN 런타임 jar (core=형태소 모델 포함, 순수 Java, 외부 의존 없음)
val bundled: Configuration by configurations.creating

configurations {
	compileOnly.get().extendsFrom(bundled)
}

dependencies {
	// ES + lucene 10.4.0 은 런타임에 Elasticsearch가 제공하므로 compileOnly
	compileOnly("org.elasticsearch:elasticsearch:9.4.2")

	// KOMORAN 런타임 — 로컬 jar (네트워크 독립, 재현 가능)
	bundled(files("libs/core.jar", "libs/commons-1.0.1.jar", "libs/aho-corasick-1.1.0.jar"))
}

tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}

// Elasticsearch 플러그인 배포용 zip 패키징
tasks.register<Zip>("pluginZip") {
	dependsOn(tasks.jar)
	archiveFileName.set("komoran-analysis.zip")
	destinationDirectory.set(layout.buildDirectory.dir("distributions"))
	from(tasks.jar)                                     // 우리 플러그인 클래스
	from(bundled)                                       // KOMORAN core/commons/aho-corasick
	from("src/main/resources/plugin-descriptor.properties")
}
