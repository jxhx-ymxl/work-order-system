# ============================================================
# 企业工单流转平台 — 前端 Dockerfile
# 多阶段：node 构建 Vue3 → nginx 托管 dist
# build context = 前端源码目录（含 package.json/src），见 docker-compose.yml
# nginx 反代配置由 compose bind mount 注入（./nginx.conf）
# ============================================================

# ---- 构建阶段 ----
FROM node:20-alpine AS build
WORKDIR /build
# 用国内 npm 镜像源（国内服务器直连 npm registry 常慢）
RUN npm config set registry https://registry.npmmirror.com
# 利用依赖层缓存
COPY package*.json ./
RUN npm ci --no-audit --no-fund --registry=https://registry.npmmirror.com || npm install --no-audit --no-fund --registry=https://registry.npmmirror.com
# 拷贝源码并构建
COPY . .
RUN npm run build

# ---- 运行阶段 ----
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
