# Team server: docker build -t openrocket-mcp . && docker run -p 8765:8765 -v "$PWD/rockets:/workspace" openrocket-mcp
# The access token is printed at start-up and kept in /workspace/.openrocket-mcp-token (or set OPENROCKET_MCP_TOKEN).
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon -q installDist

FROM eclipse-temurin:17-jre
# git: compare_designs can diff against earlier revisions when the workspace is a git checkout.
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && git config --system --add safe.directory '*'
COPY --from=build /src/build/install/openrocket-mcp /opt/openrocket-mcp
VOLUME /workspace
EXPOSE 8765
ENTRYPOINT ["/opt/openrocket-mcp/bin/openrocket-mcp", "--http", "--host", "0.0.0.0", "--workspace", "/workspace"]
