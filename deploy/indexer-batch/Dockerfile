# 색인 앱 — indexer-batch (ADR 0011)
#
# 빌드: deploy/build-images.sh 를 쓴다.
# 컴파일을 이미지 밖에서 하는 이유는 search-api/Dockerfile 주석 참고.
#
# search-api 와 **같은 베이스·같은 방식**으로 만든다. 두 앱이 같은 모델을 쓰는지는
# 런타임 도장(IndexMeta)이 대조하지만, 애초에 어긋날 여지를 줄이는 편이 낫다.
FROM nearby-model-base:e5-small

WORKDIR /app
COPY services/indexer-batch/build/libs/indexer-batch-*.jar app.jar

# 8081 = 색인. /v1/* 은 여기 없다.
EXPOSE 8081

# ── 힙 비율이 질의기와 다른 이유 ────────────────────────────────────────────
# 색인기는 임베딩 배치를 돌리는데 그 추론은 **힙 밖**(ONNX 네이티브)에서 일어난다.
# 힙을 크게 잡으면 네이티브가 쓸 자리가 줄어 OOMKilled 가 난다 — 실무에서 실시간 색인
# 컨슈머가 월 최대 14건 죽던 게 이 계열의 문제였고, 이 프로젝트가 앱을 쪼갠 이유이기도 하다.
# 질의기(50%)보다 낮게 잡아 네이티브 여유를 더 준다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=40"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
