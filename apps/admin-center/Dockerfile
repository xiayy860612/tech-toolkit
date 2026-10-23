# 构建：Next.js standalone 输出，运行镜像只带产物
FROM node:22-alpine AS deps
WORKDIR /app
COPY apps/admin-center/package.json apps/admin-center/package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY apps/admin-center ./
# 构建期不需要运行期环境变量：缺密钥的快速失败发生在服务启动时（src/instrumentation.ts）
RUN npm run build

FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production
RUN addgroup -S app && adduser -S app -G app -u 10001
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
USER app
EXPOSE 3000
ENV PORT=3000 HOSTNAME=0.0.0.0
CMD ["node", "server.js"]
