# Build stage
FROM gradle:9.7-jdk25 AS build

# Set the working directory
WORKDIR /app

# Copy gradle files
COPY build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle

# Copy source code
COPY src ./src
COPY buildSrc ./buildSrc

# Build the application and abs model
RUN gradle bootJar compileAbs --no-daemon && \
    rm -f /app/build/libs/*-plain.jar

# Use an official OpenJDK runtime as a parent image
FROM openjdk:25-ea-oraclelinux8

# Set the working directory in the container
WORKDIR /app

# Copy the executable jar file from build stage
COPY --from=build /app/build/libs/bedreflyt-api-*.jar /app/bedreflyt-api.jar

# Copy the external jar file to the container
COPY --from=build /app/build/abs/bedreflyt.jar /app/bedreflyt.jar

# Copy the smol file
COPY src/main/resources/SMOL /app/SMOL

# Expose the port that the application will run on
EXPOSE 8090

# Run the jar file
ENTRYPOINT ["java", "-jar", "/app/bedreflyt-api.jar"]
