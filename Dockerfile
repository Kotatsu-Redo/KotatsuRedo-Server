# Build stage. Dependency resolution is its own layer so a source-only change does not re-download
# the world on every build.
FROM eclipse-temurin:17-jdk@sha256:36d9a76dc231587873b103c68a789b85d91b41d314dda69730d6bc43a777f2a9 AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies || true

COPY src src
# The terms, privacy notice and content policy. processResources pulls them out of legal/ and into
# the jar, so leaving them out of the build context does not fail the build - it produces a server
# that answers /privacy with "this document is missing from this deployment", which is what it did.
COPY legal legal
RUN ./gradlew --no-daemon --quiet installDist

# Runtime stage. JRE only, non-root, no build tooling.
FROM eclipse-temurin:17-jre@sha256:c6f2875c05ea10f16398bdc5f73405c384991506f2f1ead8bcc6582ae8adea79
WORKDIR /app

RUN useradd --system --uid 10001 --create-home kotatsu
COPY --from=build --chown=kotatsu:kotatsu /app/build/install/kotatsuredo-server ./

USER kotatsu

# Overridden by the runtime environment; EXPOSE is metadata, the healthcheck below is not.
ENV PORT=8787
EXPOSE 8787

# Container-aware heap sizing; the JVM otherwise assumes it owns the host.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseContainerSupport"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
	CMD ["/bin/sh", "-c", "wget -qO- http://127.0.0.1:${PORT}/v1/health >/dev/null || exit 1"]

ENTRYPOINT ["./bin/kotatsuredo-server"]
