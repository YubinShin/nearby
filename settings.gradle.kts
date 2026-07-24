rootProject.name = "search-platform"

/**
 * 이 저장소는 **성격이 다른 두 빌드**를 담는다.
 *  - `services`                 : Kotlin/Spring Boot 21, 검색 애플리케이션들의 멀티프로젝트 빌드
 *                                 (`search-core` · `search-api` · `indexer-batch` · `indexer-stream`)
 *  - `es-analysis-komoran`      : Java 21, Elasticsearch 9.4.2 플러그인 (ES가 런타임을 제공)
 *
 * 하나의 멀티프로젝트(`include`)로 합치지 않고 **컴포지트 빌드(`includeBuild`)** 로 둔다.
 * 두 빌드는 의존 관계도 없고 산출물 형태도 다르다(실행 가능한 jar vs ES 플러그인 zip).
 * 억지로 합치면 한쪽 플러그인·툴체인 설정이 다른 쪽에 새어 들어간다.
 *
 * 각 빌드는 자기 디렉토리에서 `./gradlew` 로 **독립적으로** 빌드된다. 이 파일은 그 독립성을
 * 유지한 채, IDE 가 저장소 루트를 열었을 때 두 빌드를 다 인식하게 해 주는 역할만 한다.
 * (루트에 이 파일이 없으면 IntelliJ 는 "Kotlin이 구성되지 않았습니다"만 띄운다.)
 */
includeBuild("services")
includeBuild("es-analysis-komoran")
