FROM nearby-model-base:e5-small

WORKDIR /app
COPY services/indexer-batch/build/libs/indexer-batch-*.jar app.jar

EXPOSE 8081

ENV JAVA_OPTS="-XX:MaxRAMPercentage=40"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
