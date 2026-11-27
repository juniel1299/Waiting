# 백엔드 운영 이미지 (prod 프로필). Render Free 512MB 기준: 힙 60%, SerialGC, 작은 스레드 스택·메타스페이스 상한.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# 의존성 jar 를 이 층에서 받아 둔다(build 파일이 바뀔 때만 다시 받음). 이전의 `dependencies` 는 POM 만 읽어 jar 는
# 소스가 바뀔 때마다 bootJar 단계에서 새로 받았고, 2026-11-27 Maven Central 429 로 배포가 실패했다
RUN ./gradlew --no-daemon downloadDependencies
COPY src src
# 위 층에 받아 둔 것만 쓴다: 빠진 의존성이 있으면 네트워크로 몰래 받지 않고 여기서 바로 실패해 드러난다
RUN ./gradlew --no-daemon --offline bootJar -x test

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app/build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:MaxMetaspaceSize=160m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
