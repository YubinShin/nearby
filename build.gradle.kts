val serviceModules = listOf("search-core", "search-api", "indexer-batch", "indexer-stream", "ask-api")

tasks.register("build") {
	group = "build"
	description = "services 모듈과 KOMORAN 분석 플러그인을 빌드합니다."
	dependsOn(serviceModules.map { gradle.includedBuild("services").task(":$it:build") })
	dependsOn(gradle.includedBuild("es-analysis-komoran").task(":build"))
}

tasks.register("test") {
	group = "verification"
	description = "services 모듈의 테스트를 실행합니다."
	dependsOn(serviceModules.map { gradle.includedBuild("services").task(":$it:test") })
}
