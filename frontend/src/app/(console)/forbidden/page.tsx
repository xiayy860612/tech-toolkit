import { ShieldAlert } from "lucide-react";
import { Card } from "@/components/ui/card";

export default function ForbiddenPage() {
  return (
    <div className="mx-auto max-w-md pt-16">
      <Card className="flex flex-col items-center gap-3 p-10 text-center">
        <ShieldAlert className="size-10 text-warning" aria-hidden="true" />
        <h1 className="text-xl font-semibold">无权访问</h1>
        <p className="text-sm text-text-muted">
          当前账号不是租户管理员，无法使用管理功能。如需访问，请联系租户管理员。
        </p>
      </Card>
    </div>
  );
}
