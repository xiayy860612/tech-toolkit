import { Card, CardHeader, CardTitle } from "@/components/ui/card";
import { ChangePasswordForm } from "@/features/auth/ChangePasswordForm";

export default function ChangePasswordPage() {
  return (
    <div className="mx-auto max-w-lg">
      <Card>
        <CardHeader>
          <CardTitle>修改密码</CardTitle>
        </CardHeader>
        <ChangePasswordForm />
      </Card>
    </div>
  );
}
