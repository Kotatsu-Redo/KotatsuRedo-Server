# Build stage. Dependency resolution is its own layer so a source-only change does not re-download
# the world on every build.
FROM eclipse-temurin:25-jdk@sha256:dcf835e52330939b6c9f90ecab8aafcbcaa8fbf48423db44de884cf978c10144 AS build
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
FROM eclipse-temurin:25-jre@sha256:15090d159279e5c158473eccb48cd87f57b3e3a47511a797eb5a7a7ea6f86b0f
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
