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
			useJUnitPlatform {
				if (System.getenv("CI") != null) excludeTags("infra")
			}
		}
	}
}
