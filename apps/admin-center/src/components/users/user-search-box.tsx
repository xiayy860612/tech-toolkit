import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

/**
 * 搜索框：普通 GET 表单（不依赖 JS）。提交后回到 `/users?q=…`，不带 `page`，即从第 1 页看结果。
 * 长度上限与 UAM 契约一致（64）。
 */
export function UserSearchBox({ defaultValue = "" }: { defaultValue?: string }) {
  return (
    <form role="search" action="/users" method="get" className="mb-4 flex max-w-xl gap-2">
      <Input
        type="search"
        name="q"
        defaultValue={defaultValue}
        maxLength={64}
        aria-label="搜索用户名或账号名"
        placeholder="搜索用户名或账号名"
        autoComplete="off"
        className="h-11 flex-1"
      />
      <Button type="submit" variant="outline" className="h-11 px-5">
        搜索
      </Button>
    </form>
  );
}
