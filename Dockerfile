FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Clockify SDK is vendored under repo/ — no GitHub Packages PAT needed.
# Bring the vendored maven repo + pom in first so Maven can resolve
# dependencies before src/ changes invalidate the cache.
COPY pom.xml ./
COPY repo ./repo

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/break-compliance-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
