package dev.yubin.search.indexer.batch

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * Spring Batch 메타데이터 테이블(`BATCH_*`)이 없으면 만든다. (ADR 0013)
 *
 * ### 왜 손으로 만드나 — 부트가 해주던 일이었다
 * 부트 3 까지는 `spring.batch.jdbc.initialize-schema=always` 한 줄이면 됐다. **부트 4 에서 이
 * 프로퍼티가 없어졌다** — `spring.batch` 아래에 남은 건 `job.enabled` 와 `job.name` 뿐이다.
 * 스키마 초기화는 이제 앱이 알아서 할 일이 됐다. (프로퍼티가 없어진 걸 모르고 그대로 두면
 * 설정은 조용히 무시되고, 첫 job 실행에서 `relation "batch_job_instance" does not exist` 로 죽는다.)
 *
 * ### 왜 그냥 스크립트를 돌리지 않고 존재 여부를 먼저 보나
 * Batch 가 배포하는 `schema-postgresql.sql` 은 `CREATE TABLE` 에 `IF NOT EXISTS` 가 없다.
 * 그냥 매번 돌리면 **두 번째 기동부터 실패한다.** 오류를 무시하도록(`continueOnError`) 두는 방법도
 * 있지만, 그러면 진짜 실패(권한 없음·디스크 꽉 참)도 같이 삼켜서 문제를 첫 job 실행까지 미룬다.
 * 그래서 부트가 하던 것과 같은 방식 — **테이블이 있는지 먼저 보고, 없을 때만 스크립트를 돌린다.**
 *
 * ### 운영에서는
 * 앱이 DDL 권한을 갖는 건 로컬·데모 편의다. 실제 운영이면 이 테이블은 마이그레이션 도구
 * (Flyway/Liquibase)나 DBA 가 미리 만들고, 앱 계정은 DML 권한만 갖는 게 맞다. 그 경우에도 이
 * 코드는 그대로 둬도 된다 — 테이블이 이미 있으면 아무 일도 하지 않는다.
 */
object BatchSchema {

	private val log = LoggerFactory.getLogger(BatchSchema::class.java)

	/** 프레임워크가 `getDataSource()` 를 여러 번 부를 수 있어, 실제 확인은 한 번만 한다. */
	private val done = AtomicBoolean(false)

	/** Batch 6 이 jar 안에 넣어 배포하는 postgres DDL. 우리가 베껴 쓰지 않고 그걸 그대로 쓴다. */
	private const val SCRIPT = "org/springframework/batch/core/schema-postgresql.sql"

	/** 존재 여부를 판단하는 기준 테이블. 이게 있으면 나머지도 같은 스크립트로 함께 생겼다. */
	private const val SENTINEL = "BATCH_JOB_INSTANCE"

	fun ensure(dataSource: DataSource) {
		if (!done.compareAndSet(false, true)) return

		if (exists(dataSource)) {
			log.debug("Batch 메타데이터 테이블 확인됨 — 초기화 건너뜀")
			return
		}

		log.info("Batch 메타데이터 테이블이 없어 생성한다 ({})", SCRIPT)
		ResourceDatabasePopulator(ClassPathResource(SCRIPT)).execute(dataSource)
	}

	/**
	 * `DatabaseMetaData` 로 확인한다 — `select` 를 던져보고 예외를 잡는 방식보다 낫다.
	 * 후자는 실패한 문장이 트랜잭션을 오염시키고(postgres 는 aborted 상태가 된다), '테이블 없음'과
	 * '접속 실패'를 구분하지 못한다.
	 *
	 * postgres 는 식별자를 소문자로 접어 저장하므로 대문자로 물으면 못 찾는다. 양쪽 다 물어본다.
	 */
	private fun exists(dataSource: DataSource): Boolean =
		dataSource.connection.use { connection ->
			val meta = connection.metaData
			listOf(SENTINEL, SENTINEL.lowercase()).any { name ->
				meta.getTables(null, null, name, arrayOf("TABLE")).use { it.next() }
			}
		}
}
