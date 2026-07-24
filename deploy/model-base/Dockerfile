# 임베딩 모델만 담은 **공유 베이스 이미지** (ADR 0010, 0011)
#
# 빌드 (저장소 루트에서):
#   docker build -f deploy/model-base/Dockerfile -t nearby-model-base:e5-small .
#
# ── 왜 모델을 따로 이미지로 빼나 ─────────────────────────────────────────────
# 모델은 470MB 다. 그런데 이걸 쓰는 앱이 **둘**이다 — 색인기는 문서를, 질의기는 검색어를
# 벡터로 만든다. 두 이미지에 각각 470MB 를 넣으면 노드가 940MB 를 받는다.
#
# 두 앱이 이 이미지를 `FROM` 하면 **레이어가 같아서 노드가 한 번만 내려받는다.**
# 두 번째 이미지는 얇은 jar 레이어만 추가로 받는다.
#
# ── 왜 볼륨(PVC)이 아니라 이미지인가 ──────────────────────────────────────────
# 모델은 **불변**이다. 바뀌면 그건 "파일이 갱신된 것"이 아니라 **다른 모델**이고,
# 그때는 벡터를 전부 다시 만들어야 한다 (ADR 0011 의 버전 도장이 이걸 강제한다).
# 불변인 것을 볼륨에 두면 "언제 누가 바꿨나"가 이미지 태그 밖으로 새어 나간다.
# 이미지에 구우면 **모델 버전 = 이미지 태그**가 되어 배포 이력에 그대로 남는다.
#
# ── 왜 JRE 가 아니라 JDK 인가 ─────────────────────────────────────────────
# DJL/ONNX Runtime 이 네이티브 라이브러리를 풀어 쓰는데, 일부 JRE 슬림 이미지에는
# 필요한 libstdc++ 가 빠져 있다. 크기(약 100MB 차이)보다 안 깨지는 쪽을 택했다.
FROM eclipse-temurin:21-jdk

# scripts/fetch_embedding_model.sh 가 저장소 루트 models/ 아래에 받아둔다.
# (git 에는 없다 — 470MB 바이너리라 .gitignore 대상)
COPY models/multilingual-e5-small /opt/models/multilingual-e5-small

# 앱은 이 값을 보고 모델을 찾는다 (EmbeddingModel 의 psp.embedding.model-dir).
ENV PSP_MODEL_DIR=/opt/models/multilingual-e5-small
