# KOMORAN 형태소 분석 플러그인을 설치한 Elasticsearch 커스텀 이미지 (ADR 0008)
#
# 빌드 전제: es-analysis-komoran 모듈을 패키징해 이 디렉토리에 zip을 복사해둔다.
#   ./gradlew -p es-analysis-komoran pluginZip
#   cp es-analysis-komoran/build/distributions/komoran-analysis.zip deploy/elasticsearch/
# (compose가 --build 시 자동으로 수행하도록 deploy/up.sh 참고)

FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.2

COPY komoran-analysis.zip /tmp/komoran-analysis.zip
RUN bin/elasticsearch-plugin install --batch file:///tmp/komoran-analysis.zip \
    && rm /tmp/komoran-analysis.zip
