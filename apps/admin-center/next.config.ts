import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 容器里用 standalone 输出运行（见 Dockerfile）
  output: "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
};

export default nextConfig;
