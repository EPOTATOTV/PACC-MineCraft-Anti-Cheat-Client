# PACC v5.0 Rust 检测数据管道镜像（多阶段构建）
# 零第三方依赖，离线可构建，产物为静态/动态链接二进制。
FROM rust:1.82-slim@sha256:1111c28d995d06a7863ba6cea3b3dcb87bebe65af8ec5517caaf2c8c26f38010 AS build
WORKDIR /build
# 仅复制清单，充分利用层缓存
COPY Cargo.toml Cargo.lock* ./
RUN mkdir -p src && echo 'fn main() {}' > src/main.rs \
    && cargo build --release --locked || cargo build --release || true
COPY src ./src
RUN cargo build --release

FROM debian:bookworm-slim@sha256:7c7b2c966bc9ee8cedfeef67e0e279108992c77681fa595db4a9d65c06ccc587
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /pacc
COPY --from=build /build/target/release/pacc-pipe /usr/local/bin/pacc-pipe
# 数据卷：加密持久化事件落盘目录
VOLUME ["/pacc/data"]
ENV PIPE_OUT_DIR=/pacc/data PIPE_WINDOW_SECS=60
EXPOSE 9000
# 默认 TCP 摄入模式；如需 stdin 模式改为 CMD ["pacc-pipe"]
CMD ["pacc-pipe", "--listen", "0.0.0.0:9000"]
