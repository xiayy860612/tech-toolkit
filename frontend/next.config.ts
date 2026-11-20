import type { NextConfig } from "next";

// 不配置 rewrites：/api/v1/* 由 BFF 路由处理器代理（转发时需附加内部 JWT）。
const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
};

export default nextConfig;
