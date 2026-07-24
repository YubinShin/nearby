# 원천 데이터가 **이미 들어 있는** PostGIS 이미지 (k8s 배포용)
#
# 빌드 (저장소 루트에서):
#   ./deploy/postgis/make-seed.sh                                  # seed.sql.gz 생성
#   docker build -f deploy/postgis/Dockerfile -t nearby-postgis:seeded deploy/postgis
#
# ── 왜 데이터를 이미지에 굽나 ───────────────────────────────────────────────
# 로컬(compose)에서는 CSV 를 스크립트로 적재하지만, k8s 에서는 그 스크립트가 돌 자리가
# 마땅치 않다 — 1.4GB CSV 를 클러스터로 옮겨야 하고, 적재 Job 이 실패하면 파드는 떴는데
# 데이터가 없는 어정쩡한 상태가 된다.
#
# 덤프가 3.6MB 라 이미지에 구우면 **파드가 뜨는 순간 데이터가 있다.** 실측해서 이 크기를
# 확인하기 전에는 S3 + 적재 Job 을 만들 뻔했다 — 재보니 그럴 필요가 없었다.
#
# 이건 **데모용 결정**이다. 원천이 계속 자라는 실제 서비스라면 데이터를 이미지에 굽는 건
# 틀린 선택이다(이미지가 데이터만큼 커지고, 갱신하려면 재배포해야 한다). 여기서는
# 원천이 고정된 공개 데이터 스냅샷이라 성립한다.
FROM postgis/postgis:16-3.4

# 이 디렉토리의 파일은 **데이터 디렉토리가 비어 있을 때만** 실행된다.
# PVC 를 붙여 재시작하면 다시 돌지 않으므로 데이터가 중복 적재되지 않는다.
# postgres 엔트리포인트가 .sql / .sql.gz / .sh 를 알아서 처리한다.
COPY seed.sql.gz /docker-entrypoint-initdb.d/10-seed.sql.gz
