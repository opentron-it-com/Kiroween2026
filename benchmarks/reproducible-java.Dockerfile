FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY java/opentron-java/ ./java/opentron-java/
RUN mvn -B -ntp -f java/opentron-java/pom.xml -pl backend -am -DskipTests package

FROM eclipse-temurin:21.0.7_6-jre-jammy

WORKDIR /app
COPY --from=builder /workspace/java/opentron-java/backend/target/opentron-java-backend-0.1.0-exec.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "--enable-preview", "--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=2", "-Xms256m", "-Xmx1536m", "-jar", "/app/app.jar"]
