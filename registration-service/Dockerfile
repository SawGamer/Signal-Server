# Runtime image for the self-hosted registration (phone verification) service.
#
# Build the jar first, from the repository root:
#   ./mvnw -f registration-service/pom.xml -Djgitver.skip=true package
# then:
#   docker build -t selfhost/registration-service registration-service
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 --no-create-home registration
COPY target/registration-service.jar /opt/registration-service/registration-service.jar

USER registration
EXPOSE 9443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/registration-service/registration-service.jar"]
