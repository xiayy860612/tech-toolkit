import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/setup.ts'],
    include: ['tests/unit/**/*.test.{ts,tsx}', 'tests/component/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      // Exclusions are listed and justified in AGENTS.md (src/components/ui = shadcn-generated code).
      exclude: ['src/**/*.d.ts', 'src/app/layout.tsx', 'src/components/ui/**'],
      reporter: ['text', 'lcov'],
      thresholds: { lines: 90 },
    },
  },
});
