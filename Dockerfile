FROM maven:3.9-eclipse-temurin-21 AS build
ARG GH_PACKAGES_USER
ARG GH_PACKAGES_PAT
WORKDIR /src

COPY pom.xml ./
RUN mkdir -p /root/.m2 \
    && printf '<settings><servers><server><id>github</id><username>%s</username><password>%s</password></server></servers></settings>' \
        "$GH_PACKAGES_USER" "$GH_PACKAGES_PAT" > /root/.m2/settings.xml

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/break-compliance-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
