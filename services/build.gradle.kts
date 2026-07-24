/**
 * 모든 모듈이 공유하는 빌드 설정.
 *
 * 플러그인 **버전만** 여기서 못박고(`apply false`), 실제 적용은 각 모듈이 자기 `build.gradle.kts`
 * 에서 한다. 버전이 한 곳에 있어야 모듈끼리 Kotlin·Spring Boot 버전이 어긋나지 않고, 적용은
 * 각자 해야 "이 모듈이 무엇인지"(실행 가능한 앱인가, 라이브러리인가)가 그 파일만 봐도 보인다.
 */
plugins {
	kotlin("jvm") version "2.3.21" apply false
	kotlin("plugin.spring") version "2.3.21" apply false
	id("org.springframework.boot") version "4.1.0" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
	group = "dev.yubin"
	version = "0.0.1-SNAPSHOT"

	repositories {
		mavenCentral()
	}

	// 툴체인·컴파일 옵션·테스트 실행기는 모듈마다 달라야 할 이유가 없다.
	// 모듈이 Kotlin 플러그인을 적용한 뒤에 걸어야 해서 pluginManager 콜백을 쓴다.
	pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
		extensions.configure<JavaPluginExtension> {
			toolchain {
				languageVersion = JavaLanguageVersion.of(21)
			}
		}
		extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
			compilerOptions {
				freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
			}
		}
		tasks.withType<Test> {
			useJUnitPlatform()
		}
	}
}
