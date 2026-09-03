FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# fontconfig (pulls in libfreetype6) — PlantUML/Java2D font manager needs it even for
# text-only parsing/validation (SFDM-4087), not just image rendering; without it the JVM
# throws UnsatisfiedLinkError: libfontmanager.so: libfreetype.so.6: cannot open shared object file
RUN apt-get update && apt-get install -y curl fontconfig && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
