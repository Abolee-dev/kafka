FROM openjdk:11
WORKDIR /app
COPY target/kafka-streams-index-router-1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
