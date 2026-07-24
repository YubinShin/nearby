# 질의 앱 — search-api (ADR 0011)
#
# 빌드: deploy/build-images.sh 를 쓴다 (jar 빌드 → 이미지 3개를 순서대로).
#
# ── 왜 이미지 안에서 gradle 을 돌리지 않나 ───────────────────────────────────
# 개발은 Apple Silicon(arm64), 배포 대상은 x86 EKS 다. 이미지 안에서 컴파일하면
# amd64 이미지를 만들 때 **QEMU 에뮬레이션으로 JVM 컴파일**이 돌아 몇 배 느려진다.
#
# 그런데 **jar 은 아키텍처와 무관하다** — 자바 바이트코드니까. 그래서 컴파일은 호스트에서
# 네이티브로 하고, 이미지는 그 jar 을 복사만 한다. 크로스 빌드가 COPY 한 번으로 끝난다.
#
# 대가: 이미지 빌드가 "먼저 gradle 을 돌렸다"에 의존한다. build-images.sh 가 그 순서를
# 강제하고, jar 이 없으면 실패시킨다.
#
# 베이스(nearby-model-base)에는 470MB 임베딩 모델이 들어 있고, 색인기와 **같은 레이어**를
# 공유한다. 노드는 모델을 한 번만 내려받는다.
FROM nearby-model-base:e5-small

WORKDIR /app
COPY services/search-api/build/libs/search-api-*.jar app.jar

# 8080 = 질의. 이 앱에는 /admin 이 없다 — 플래그로 끈 게 아니라 클래스가 jar 에 없다.
EXPOSE 8080

# 컨테이너 메모리의 절반만 힙으로. 나머지는 ONNX 네이티브 메모리(약 0.5GB)가 쓴다.
# 이 비율을 안 잡으면 힙이 컨테이너 한계까지 차오른 뒤 네이티브 할당이 실패해
# **힙에 여유가 있는데도** OOMKilled 로 죽는다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
