package dev.yubin.search.indexer.batch

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import javax.sql.DataSource

/**
 * Spring Batch 기반 설정 (ADR 0013).
 *
 * ### 왜 `@EnableBatchProcessing` 이 아니라 클래스를 상속하나
 * Spring Batch 6 의 기본 [org.springframework.batch.core.configuration.support.DefaultBatchConfiguration]
 * 은 **인메모리(resourceless)** 다. JDBC 메타데이터를 쓰려면 [JdbcDefaultBatchConfiguration] 로
 * 갈아타야 하는데, 그 방법이 두 가지다.
 * - `@EnableJdbcJobRepository` — 어노테이션만 붙이면 되지만 **손댈 구멍이 없다.**
 * - 이 클래스를 상속 — `getDataSource()`·`getTaskExecutor()` 를 열어준다.
 *
 * 우리는 둘 다 손봐야 해서 상속을 골랐다. 그리고 이 빈이 있으면 부트의
 * `BatchAutoConfiguration` 이 `@ConditionalOnMissingBean(DefaultBatchConfiguration)` 으로
 * 알아서 물러난다 — 설정이 두 겹으로 겹치지 않는다.
 *
 * ### 왜 `spring.batch.job.enabled=false` 인가 (application.yml)
 * 부트는 기본적으로 **기동 직후 job 을 한 번 돌린다**(`JobLauncherApplicationRunner`). 우리는
 * 상시 서버라 그게 필요 없고, 뜨자마자 원천을 훑으면 시연·실측이 방해받는다. job 은
 * `/admin` 아래 엔드포인트나 스케줄러가 **부를 때만** 돈다.
 */
@Configuration
class BatchConfig(private val dataSource: DataSource) : JdbcDefaultBatchConfiguration(), DisposableBean {

	/**
	 * job 을 돌리는 스레드. **core=max=1 — 한 번에 job 하나만 돈다.**
	 *
	 * 전에는 스프링 스케줄러의 단일 스레드가 이 역할을 우연히 해주고 있었다(증분과 전체가
	 * 겹치지 않고 줄 서던 것). 지금은 스케줄러든 HTTP 든 같은 이 풀로 들어오므로, **어느 경로로
	 * 들어와도** 같은 인덱스를 동시에 만지지 않는다는 보장이 한 곳에 모인다.
	 *
	 * 큐가 있는 이유: 8분짜리 전체 재색인 도중 증분 주기가 와도 거절하지 않고 뒤에 세운다.
	 * 가상 스레드를 쓰지 않는 것은 의도다 — 색인은 I/O 대기가 아니라 **CPU 바운드**(임베딩 추론이
	 * 색인 시간의 96.1%)이고, ONNX 네이티브 호출은 가상 스레드를 캐리어에 고정시킨다.
	 */
	private val jobExecutor = ThreadPoolTaskExecutor().apply {
		corePoolSize = 1
		maxPoolSize = 1
		queueCapacity = 8
		setThreadNamePrefix("index-job-")
		// 종료 시 돌던 색인을 끊지 않는다 — 스왑 전에 죽으면 고아 인덱스가 남는다.
		setWaitForTasksToCompleteOnShutdown(true)
		setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS)
		initialize()
	}

	/**
	 * 이걸 안 갈면 [org.springframework.core.task.SyncTaskExecutor] 라서 `JobOperator.start()` 가
	 * **호출한 스레드에서 job 을 끝까지 돌린다.** 그러면 HTTP 요청이 8분간 매달리고, 예전의
	 * "curl 끊으면 색인도 죽는다" 문제가 그대로 재현된다. 비동기 실행이 202 + jobId 폴링의 전제다.
	 */
	override fun getTaskExecutor(): TaskExecutor = jobExecutor

	/**
	 * Batch 메타데이터용 DataSource. 넘기기 전에 `BATCH_*` 테이블이 있는지 보장한다 —
	 * 부트 4 에는 `spring.batch.jdbc.initialize-schema` 가 **없어졌다**([BatchSchema] 주석 참고).
	 */
	override fun getDataSource(): DataSource {
		BatchSchema.ensure(dataSource)
		return dataSource
	}

	override fun destroy() {
		jobExecutor.shutdown()
	}

	private companion object {
		/**
		 * 전체 재색인(벡터 8분 32초 실측)이 셧다운에 걸려도 스왑까지는 마치게 둔다.
		 *
		 * **이 값 혼자서는 아무것도 보장하지 못한다.** k8s 는 `terminationGracePeriodSeconds`(기본
		 * 30초)가 지나면 SIGKILL 하므로, 그 값이 이보다 작으면 여기서 아무리 기다려도 프로세스가
		 * 먼저 죽는다. `deploy/k8s/base/indexer-batch.yaml` 이 630 으로 맞춰져 있다 — **둘은 한 쌍이라
		 * 한쪽만 바꾸면 다른 쪽이 조용히 무의미해진다.**
		 */
		const val AWAIT_TERMINATION_SECONDS = 600
	}
}
