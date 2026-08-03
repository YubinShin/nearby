import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("io.spring.dependency-management")
}

dependencyManagement {
	imports { mavenBom(SpringBootPlugin.BOM_COORDINATES) }
}

dependencies {
	implementation(project(":search-core"))
}
