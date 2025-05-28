# Java project with Gradle, gRPC, and Protobuf

# Variables
GRADLE=./gradlew
MAIN_CLASS=Main.Main
JAR_FILE=build/libs/$(shell basename $(PWD))-all.jar

# Targets

.PHONY: all proto build run clean jar

# Default: Build everything
all: build

# Generate gRPC and Protobuf sources
proto:
	$(GRADLE) generateProto

# Build the project (compile Java + generate sources)
build:
	$(GRADLE) build

# Build the shadow JAR
jar:
	$(GRADLE) shadowJar

# Run the application from the shadow JAR
run: jar
	java -jar $(JAR_FILE)

# Clean build artifacts
clean:
	$(GRADLE) clean

# Run tests
test:
	$(GRADLE) test

# Format code (optional, if using spotless or similar)
format:
	$(GRADLE) spotlessApply
