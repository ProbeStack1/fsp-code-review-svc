# Use Maven with OpenJDK 17 as base image for building
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

# Set working directory
WORKDIR /app

# Copy pom.xml first (for better Docker layer caching)
COPY pom.xml ./

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Runtime stage - use JRE only for smaller image
FROM eclipse-temurin:17-jre-alpine

# Install wget for health checks (before switching to non-root user)
RUN apk add --no-cache wget

# Set working directory
WORKDIR /app

# Create a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy the built JAR from builder stage (as root, before switching user)
COPY --from=builder /app/target/fsp-compliance-svc-*.jar app.jar

# Change ownership to spring user
RUN chown spring:spring app.jar

# Create writable log directory in /tmp (works with read-only root filesystem)
RUN mkdir -p /tmp/logs

# Switch to non-root user
USER spring:spring

# Cloud Run will pass the PORT environment variable
ENV PORT=8080

# Expose the port
EXPOSE ${PORT}

# Health check (using wget which is available in alpine)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT}/code-review/v1/actuator/health || exit 1

# Run the application.
#
# MaxRAMPercentage: without it the JVM defaults to 25% of the container, so a 512 MiB instance ran
#   on a ~128 MB heap while the rest sat unused. 70% leaves room for metaspace, code cache, thread
#   stacks and direct buffers, which are not counted in the heap but are counted by Cloud Run.
# ExitOnOutOfMemoryError: an instance that has exhausted its heap cannot serve anything useful, and
#   left alive it lingers behind the load balancer returning 502s. Exiting lets Cloud Run replace it.
#
# Deliberately no HeapDumpOnOutOfMemoryError: /tmp is a tmpfs here, so the dump would be written
# into the very memory that just ran out.
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError -jar -Dserver.port=${PORT} app.jar"]
