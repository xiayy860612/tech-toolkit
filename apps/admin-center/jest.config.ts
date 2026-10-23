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

export default createJestConfig(config);
