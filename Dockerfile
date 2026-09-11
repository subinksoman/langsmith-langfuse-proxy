# Multi-stage build for smaller image size

# Stage 1: Build the application
FROM maven:3.9.5-eclipse-temurin-17 AS builder

# Set working directory
WORKDIR /app

# Copy pom.xml and warm the dependency cache.
#
# The cache mount is what makes this cheap. Without it, editing pom.xml — a
# version bump is enough — invalidates this layer and re-downloads every
# dependency, turning a 15s rebuild into minutes. The mount keeps ~/.m2 across
# builds, so the layer re-runs but downloads nothing.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -B

# Stage 2: Create the runtime image
FROM eclipse-temurin:17-jre-alpine

# Set working directory
WORKDIR /app

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring

# Copy the built jar from builder stage
COPY --from=builder /app/target/langsmith-langfuse-proxy-*.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs && chown -R spring:spring /app

# Switch to non-root user
USER spring:spring

# Expose port
EXPOSE 3001

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:3001/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]