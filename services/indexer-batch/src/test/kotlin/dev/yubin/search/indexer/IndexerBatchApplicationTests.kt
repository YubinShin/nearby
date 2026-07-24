package dev.yubin.search.indexer

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

/**
 * 쪼갠 뒤 **이 앱이 혼자서도 뜨는지**를 확인한다.
 *
 * 모듈을 나눌 때 제일 흔한 사고가 "빈이 저쪽 모듈에 있어서 안 뜬다"인데, 그건 컴파일러가
 * 잡아주지 않는다. 컨텍스트를 실제로 띄워봐야 `scanBasePackages` 에 `search-core` 를
 * 빠뜨렸는지 같은 실수가 드러난다.
 */
@SpringBootTest
class IndexerBatchApplicationTests {

	@Test
	fun contextLoads() {
	}
}
