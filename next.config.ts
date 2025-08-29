import type { NextConfig } from "next";

// https://github.com/mswjs/msw/issues/1801
function resolveMswAlias(isServer: boolean, config: any) {
  if (isServer) {
    // next server build => ignore msw/browser
    if (Array.isArray(config.resolve.alias)) {
      // in Next the type is always object, so this branch isn't necessary. But to keep TS happy, avoid @ts-ignore and prevent possible future breaking changes it's good to have it
      config.resolve.alias.push({ name: "msw/browser", alias: false });
    } else {
      config.resolve.alias["msw/browser"] = false;
    }
  } else {
    // browser => ignore msw/node
    if (Array.isArray(config.resolve.alias)) {
      config.resolve.alias.push({ name: "msw/node", alias: false });
    } else {
      config.resolve.alias["msw/node"] = false;
    }
  }
  return config;
}

const nextConfig: NextConfig = {
  /* config options here */
  // output: "export",
  // basePath: process.env.PAGES_BASE_PATH,
  webpack: (config, context) => {
    return resolveMswAlias(context?.isServer, config);
  },
  serverExternalPackages: [
    // fix: Can't resolve 'oracledb'
    "knex",
  ],
  images: {
    // TODO: use own host instead in future
    domains: ["images.unsplash.com"], // Add the external image domain
  },
  // rewrites used for service host in different location
  // rewrites: async () => {
  //   return [
  //     {
  //       source: "/api/:path*",
  //       destination: `http://localhost:8080/api/:path*`,
  //     },
  //   ];
  // },
};

export default nextConfig;
