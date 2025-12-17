FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests
CMD ["java", "-jar", "target/HalloweenBot-01-1.0-SNAPSHOT.jar"]
