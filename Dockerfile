# The JAR is built by the CI workflow's own `mvn clean package` step, on the
# GitHub Actions runner — which has working Google Cloud auth (Workload
# Identity), so it can resolve the private com.forgecrux:* / com.forge.libs:*
# dependencies from Artifact Registry. This Dockerfile does NOT run `mvn`:
# a Docker build container has no knowledge of the runner's gcloud session,
# so it could never authenticate to Artifact Registry (forge-auth-lib /
# forge-logging-lib fail to resolve). It just packages the already-built JAR.
FROM eclipse-temurin:17-jre-alpine

# Install wget for health checks (before switching to non-root user)
RUN apk add --no-cache wget

# Set working directory
WORKDIR /app

# Create a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy the JAR built by the workflow's `mvn clean package` step (must run
# before this Docker build step — see .github/workflows/deploy_prod.yml)
COPY target/fsp-code-review-svc-*.jar app.jar

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

# Health check — actuator sits under the server.servlet.context-path (/code-review)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT}/code-review/actuator/health || exit 1

# Run the application.
#
# MaxRAMPercentage: without it the JVM defaults to 25% of the container, so a 512 MiB instance ran
#   on a ~128 MB heap while the rest sat unused. 70% leaves room for metaspace, code cache, thread
#   stacks and direct buffers, which are not counted in the heap but are counted by Cloud Run.
# ExitOnOutOfMemoryError: an instance that has exhausted its heap cannot serve anything useful, and
#   left alive it lingers behind the load balancer returning 502s. Exiting lets Cloud Run replace it.
ENTRYPOINT ["sh", "-c", "java -XX:MaxRAMPercentage=70.0 -XX:+ExitOnOutOfMemoryError -jar -Dserver.port=${PORT} app.jar"]

