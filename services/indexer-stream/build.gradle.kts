plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("io.spring.dependency-management")
}

dependencies {
	implementation(project(":search-core"))
}
