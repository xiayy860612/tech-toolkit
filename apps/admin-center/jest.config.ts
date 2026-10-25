import type { Config } from "jest";
import nextJest from "next/jest.js";

const createJestConfig = nextJest({ dir: "./" });

const config: Config = {
  testEnvironment: "jsdom",
  setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
  moduleNameMapper: {
    "^@/(.*)$": "<rootDir>/src/$1",
    // server-only 在浏览器包之外才可用；单元测试里替换成空模块
    "^server-only$": "<rootDir>/tests/support/server-only.ts",
  },
  testMatch: [
    "<rootDir>/tests/unit/**/*.test.{ts,tsx}",
    "<rootDir>/tests/contract/**/*.test.{ts,tsx}",
  ],
  collectCoverageFrom: [
    "src/**/*.{ts,tsx}",
    // 覆盖率排除路径：shadcn/ui 供应商代码、生成代码、类型声明
    "!src/components/ui/**",
    "!src/**/*.generated.ts",
    "!src/**/*.d.ts",
  ],
  coverageThreshold: { global: { lines: 90 } },
};

// next/jest 总是把 /node_modules/ 整体排除在转换之外；msw 依赖链里有纯 ESM 包（rettime、until-async 等），
// 在 CommonJS 的 Jest 里必须转换，所以在 next/jest 生成配置之后再覆盖 transformIgnorePatterns。
const ESM_PACKAGES = ["msw", "@mswjs", "@open-draft", "rettime", "until-async", "headers-polyfill", "outvariant", "strict-event-emitter"];

const jestConfig = async () => {
  const resolved = await createJestConfig(config)();
  return {
    ...resolved,
    transformIgnorePatterns: [
      `/node_modules/(?!(${ESM_PACKAGES.join("|")})/)`,
      "^.+\\.module\\.(css|sass|scss)$",
    ],
  };
};

export default jestConfig;
