# Build
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Run (Render free tier ~512MB — keep heap modest; embeddings are remote via Gemini)
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV PORT=8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+UseContainerSupport"
EXPOSE 8080
COPY --from=build /app/target/langchain4j-rag-backend.jar app.jar
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT}"]
