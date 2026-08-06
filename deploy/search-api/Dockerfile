FROM nearby-model-base:e5-small

WORKDIR /app
COPY services/search-api/build/libs/search-api-*.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=50"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
