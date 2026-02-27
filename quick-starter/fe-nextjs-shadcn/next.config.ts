import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",

  // 启用压缩优化
  compress: true,

  // 生产环境优化 - 禁用 source maps 减小体积
  productionBrowserSourceMaps: false,

  async rewrites() {
    const isDev = process.env.NODE_ENV === "development";
    if (isDev) {
      return [
        {
          source: "/api/:path*",
          destination: "http://127.0.0.1:8000/api/:path*",
        },
      ];
    }
    return [];
  },
};

export default nextConfig;
