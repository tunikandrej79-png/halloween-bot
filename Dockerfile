FROM openjdk:17-jdk-slim
COPY target/HaloweenBot-01-1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
