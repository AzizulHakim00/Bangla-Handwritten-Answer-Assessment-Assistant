FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-ben tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
RUN groupadd -g 10001 assessment && useradd -M -u 10001 -g 10001 -s /usr/sbin/nologin assessment && mkdir -p /data/scripts && chown 10001:10001 /data/scripts
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
