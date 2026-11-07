import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  output: 'standalone',
  poweredByHeader: false,
  reactStrictMode: true,
  // Import only the Radix / icon modules that are used (keeps the first-load JS budget, spec FR-029)
  experimental: { optimizePackageImports: ['radix-ui', 'lucide-react'] },
};

export default nextConfig;
