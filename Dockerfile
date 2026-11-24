# The Bloqqi web editor in a container. Build from a clone with the compiler
# submodule (git clone --recursive ...):
#
#   docker build -t bloqqi-editor .
#   docker run --rm -p 127.0.0.1:8080:8080 -v "$PWD/workspace:/workspace" --user "$(id -u):$(id -g)" bloqqi-editor
#
# then open http://localhost:8080/ on the host. The editor reads and writes the
# .dia files in the folder mounted at /workspace; an empty one first gets the
# compiler's examples (see README.md, "Run with Docker").

# ---- Server and compiler (Java 21, Ant) ----
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS server

# Apache Ant, pinned and verified (as the build verifies the jars it downloads).
ARG ANT_VERSION=1.10.15
ARG ANT_SHA512=d78427aff207592c024ff1552dc04f7b57065a195c42d398fcffe7a0145e8d00cd46786f5aa52e77ab0fdf81334f065eb8011eecd2b48f7228e97ff4cb20d16c
RUN curl -fsSL -o /tmp/ant.tar.gz \
        "https://archive.apache.org/dist/ant/binaries/apache-ant-${ANT_VERSION}-bin.tar.gz" \
    && echo "${ANT_SHA512}  /tmp/ant.tar.gz" | sha512sum -c - \
    && tar -xzf /tmp/ant.tar.gz -C /opt \
    && rm /tmp/ant.tar.gz
ENV PATH="/opt/apache-ant-${ANT_VERSION}/bin:${PATH}"

WORKDIR /src
# The pinned jars (from Maven Central, SHA-1 verified) in their own layer: it
# depends only on build.xml, so changes to the sources don't download them again.
COPY build.xml ./
RUN ant deps
COPY bloqqi-compiler/ bloqqi-compiler/
COPY server/ server/
# Builds the compiler (its "gen" target only) and the server.
RUN test -f bloqqi-compiler/build.xml \
        || (echo "bloqqi-compiler/ is empty: clone with --recursive, or run git submodule update --init" && exit 1) \
    && ant server

# ---- Client (React, Vite) ----
FROM node:22.23.4-bookworm-slim AS client
WORKDIR /src/client
COPY client/package.json client/package-lock.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

# ---- Runtime ----
FROM eclipse-temurin:21.0.12.1_1-jre-noble
WORKDIR /app
COPY --from=server /src/build/compiler/classes classes/compiler/
COPY --from=server /src/build/server/classes classes/server/
COPY --from=server /src/lib/ lib/
COPY --from=server /src/bloqqi-compiler/tools/beaver-rt.jar /src/bloqqi-compiler/tools/gson-2.6.2.jar lib/
COPY --from=client /src/client/dist static/
# Copied into an empty workspace on start (Main --examples).
COPY --from=server /src/bloqqi-compiler/examples/ examples/
# Test-only jars are not needed.
RUN rm -f lib/junit-*.jar lib/hamcrest-*.jar

# The .dia files are in a folder mounted here.
VOLUME /workspace
EXPOSE 8080
# Not root by default; "docker run --user" gives the owner of the mounted folder.
USER 1000:1000
CMD ["java", "-cp", "/app/classes/server:/app/classes/compiler:/app/lib/*", \
     "org.bloqqi.web.Main", "--port", "8080", "--workspace", "/workspace", "--static", "/app/static", \
     "--examples", "/app/examples"]
