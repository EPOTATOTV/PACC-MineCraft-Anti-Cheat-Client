# PACC Go 边缘网关镜像（多阶段构建，零第三方依赖）
FROM golang:1.22-alpine@sha256:1699c10032ca2582ec89a24a1312d986a3f094aed3d5c1147b19880afe40e052 AS build
WORKDIR /src
COPY go.mod ./
# 复制空占位满足 go build 缓存（可选优化）
COPY main.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/gateway .

FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
RUN apk add --no-cache ca-certificates wget && addgroup -S pacc && adduser -S pacc -G pacc
COPY --from=build /out/gateway /usr/local/bin/gateway
USER pacc
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/gateway"]
