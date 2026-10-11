# PACC 管理后台前端镜像（构建 + Nginx 静态托管，并反代 /api 与 /ws）
# 构建上下文为仓库根目录：docker build -f ptv-frontend/Dockerfile .
# 固定基础镜像 digest：tag 可被重新推送指向别的镜像，digest 才是不可变引用（Scorecard PinnedDependenciesID）
FROM node:20-alpine@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293 AS build
WORKDIR /workspace
# 国内 npm 镜像加速（构建可覆盖：--build-arg NPM_REGISTRY=...）
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry ${NPM_REGISTRY}
COPY ptv-frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY ptv-frontend ./
RUN npm run build

FROM nginx:1.25-alpine@sha256:516475cc129da42866742567714ddc681e5eed7b9ee0b9e9c015e464b4221a00
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY ptv-frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
