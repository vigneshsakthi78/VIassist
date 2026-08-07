# Build
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Run
FROM eclipse-temurin:17-jre
WORKDIR /app
ENV PORT=8080
EXPOSE 8080
COPY --from=build /app/target/langchain4j-rag-backend.jar app.jar
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT}"]
