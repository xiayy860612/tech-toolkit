import { FlatCompat } from "@eslint/eslintrc";

const compat = new FlatCompat({ baseDirectory: import.meta.dirname });

const config = [
  ...compat.extends("next/core-web-vitals", "next/typescript"),
  {
    // 生成代码与 shadcn/ui 供应商代码不参与 lint
    ignores: [
      ".next/**",
      "coverage/**",
      "playwright-report/**",
      "test-results/**",
      "next-env.d.ts",
      "src/lib/uam/types.generated.ts",
      "src/components/ui/**",
    ],
  },
];

export default config;
