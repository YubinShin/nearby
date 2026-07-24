package dev.yubin.search.core.es

import co.elastic.clients.json.JsonpMapper
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * ES 클라이언트가 `_source` 를 **Kotlin data class 로 제대로 채우게** 한다.
 *
 * ### 왜 필요한가 — Jackson 이 두 개 올라와 있다
 * | 누가 | 어떤 Jackson |
 * |---|---|
 * | Spring Boot 4 (웹 직렬화) | **Jackson 3** (`tools.jackson`) |
 * | Elasticsearch 자바 클라이언트 | **Jackson 2** (`com.fasterxml.jackson`) |
 *
 * 프로젝트가 의존하는 Kotlin 모듈은 `tools.jackson.module:jackson-module-kotlin` — **Jackson 3
 * 쪽에만** 붙는다. ES 클라이언트가 쓰는 Jackson 2 에는 Kotlin 모듈이 없었다.
 *
 * ### 왜 조용히 틀렸나
 * 우리 문서 클래스는 전부 `val` 에 기본값이 있는 data class 다. Kotlin 모듈 없이 Jackson 2 가
 * 이걸 만나면 합성 무인자 생성자로 객체를 만든 뒤 프로퍼티를 채우려 하는데, `val` 이라 세터가
 * 없다. 그래서 **예외 없이 전부 기본값**(`""`, `null`, `0`)이 된다.
 *
 * 실측에서 이렇게 드러났다: `/v1/search?q=CU` 가 **187건을 정상 응답**하는데 결과의 이름이
 * 전부 빈 문자열이었다. total 도 맞고 하이라이트도 나온다 — 그건 ES 메타데이터라서.
 * `_source` 에서 오는 값만 사라진다. 200 OK, 에러 로그 없음.
 *
 * 영향 범위가 문서 조회만이 아니었다:
 * - `CheckpointStore.Checkpoint` → 체크포인트를 **항상 못 읽어** 증분 색인이 매번 폴백했다
 *   (크리틱 #1·#2 를 고치려고 만든 장치가 무력화돼 있었다).
 * - `IndexMeta.Stamp` → 버전 도장을 `schema_version=0` 으로 읽어 질의기가 기동에 실패했을 것이다.
 *
 * ### 왜 여기(core)에 두나
 * ES 를 읽는 코드가 두 앱과 core 에 흩어져 있다. 한쪽 앱에만 매퍼를 고쳐두면 다른 쪽은 계속
 * 조용히 빈 값을 받는다 — 이 저장소가 `search-core` 를 두는 이유와 정확히 같은 이유다 (ADR 0011).
 */
@Configuration
class EsJsonpMapperConfig {

	/**
	 * Spring Boot 는 `JsonpMapper` 를 `@ConditionalOnMissingBean` 으로 만든다. 이 빈이 있으면
	 * 그쪽 기본값 대신 이걸 쓴다.
	 */
	@Bean
	fun jsonpMapper(): JsonpMapper = JacksonJsonpMapper(
		ObjectMapper()
			.registerModule(KotlinModule.Builder().build())
			// `_source` 에는 우리 문서 클래스가 안 받는 필드도 있다(`brand_text`·`sido`·`updated_at`).
			// 색인 스키마가 앞서 나가도 질의가 깨지지 않아야 한다.
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
	)
}
