# 임베딩 모델 공유 베이스 이미지 (ADR 0010)
#
# 빌드 (저장소 루트에서, scripts/fetch_embedding_model.sh 로 models/ 를 먼저 받습니다):
#   docker build -f deploy/model-base/Dockerfile -t nearby-model-base:e5-small .

FROM eclipse-temurin:21-jdk

COPY models/multilingual-e5-small /opt/models/multilingual-e5-small

ENV PSP_MODEL_DIR=/opt/models/multilingual-e5-small
