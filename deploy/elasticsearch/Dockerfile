# KOMORAN 형태소 분석 플러그인을 설치한 Elasticsearch 커스텀 이미지 (ADR 0008)
#
# 빌드 전제: es-analysis-komoran 모듈을 패키징해 이 디렉토리에 zip을 복사해둔다.
#   ./gradlew -p es-analysis-komoran pluginZip
#   cp es-analysis-komoran/build/distributions/komoran-analysis.zip deploy/elasticsearch/
# (compose가 --build 시 자동으로 수행하도록 deploy/up.sh 참고)

FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.2

COPY komoran-analysis.zip /tmp/komoran-analysis.zip
# rm 은 생략: ES 9.x 컨테이너는 elasticsearch 유저로 실행되어 root 소유의 /tmp 파일을 지울 수 없음.
# 남는 zip(5MB)은 무해하다.
RUN bin/elasticsearch-plugin install --batch file:///tmp/komoran-analysis.zip

# nori: komoran 과 나란히 비교(ADR 0008 근거)하기 위한 대조군. 운영에선 komoran 을 사용.
RUN bin/elasticsearch-plugin install --batch analysis-nori
