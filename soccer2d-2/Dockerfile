# Server uchun: docker build -t soccer-server . && docker run -p 7777:7777/udp soccer-server
FROM gradle:8.5-jdk17 AS build
WORKDIR /src
COPY . .
ENV SKIP_ANDROID=1
RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/server/build/install/server /app
EXPOSE 7777/udp
ENTRYPOINT ["/app/bin/server"]
