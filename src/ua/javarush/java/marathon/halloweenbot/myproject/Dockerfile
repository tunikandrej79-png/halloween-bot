FROM eclipse-temurin:17-jdk
COPY . /app
WORKDIR /app
RUN mvn clean package -DskipTests
CMD ["java", "-jar", "target/HalloweenBot-01-1.0-SNAPSHOT.jar"]
