import { FileSearch, Layers, ShieldCheck } from "lucide-react";
import type { ReactNode } from "react";

const POINTS = [
  { icon: ShieldCheck, title: "多租户隔离", text: "数据按租户严格隔离，互不可见" },
  { icon: Layers, title: "多登录方式 Account", text: "一个用户可拥有多个登录账号，互相独立" },
  { icon: FileSearch, title: "操作审计", text: "登录与管理操作全程留痕，可追溯" },
];

/** 左品牌区 + 右表单区（取自设计稿 Login），供登录页与强制改密页复用。 */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="grid min-h-screen lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]">
      <aside className="hidden flex-col justify-between bg-sidebar p-12 text-white lg:flex">
        <div className="text-xl font-semibold">租户管理中心</div>
        <ul className="flex flex-col gap-8">
          {POINTS.map(({ icon: Icon, title, text }) => (
            <li key={title} className="flex gap-4">
              <Icon className="mt-1 size-6 shrink-0 text-accent" aria-hidden="true" />
              <div>
                <p className="text-base font-medium">{title}</p>
                <p className="text-sm text-sidebar-text">{text}</p>
              </div>
            </li>
          ))}
        </ul>
        <p className="text-xs text-sidebar-muted">Tenant Admin Center</p>
      </aside>
      <main className="flex items-center justify-center bg-surface px-6 py-12">
        <div className="w-full max-w-sm">{children}</div>
      </main>
    </div>
  );
}
