plugins {
	kotlin("jvm") version "2.3.21"
	application
}

repositories { mavenCentral() }

dependencies {
	implementation("co.elastic.clients:elasticsearch-java:9.4.2")
	implementation("co.elastic.clients:elasticsearch-rest5-client:9.4.2")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
	implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")
	implementation("org.slf4j:slf4j-simple:2.0.16")
}

kotlin { jvmToolchain(21) }

application { mainClass.set("BenchKt") }

tasks.named<JavaExec>("run") { standardInput = System.`in` }
