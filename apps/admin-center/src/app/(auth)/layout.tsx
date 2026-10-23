import type { ReactNode } from "react";

/** 极简外壳（登录页）：没有导航，内容居中。 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main className="grid min-h-svh place-items-center px-4 py-6">
      <div className="w-full max-w-md">{children}</div>
    </main>
  );
}
