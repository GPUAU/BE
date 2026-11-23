# 빌드는 runner 아키텍처에서 네이티브로, 실행 이미지는 노드(x86)용으로 만든다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon -q bootJar -x test && cp build/libs/*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:17-jre
ENV SPRING_PROFILES_ACTIVE=deploy \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
RUN useradd --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /app.jar ./app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
