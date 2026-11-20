import { UserForm } from "@/features/users/UserForm";

export default function NewUserPage() {
  return (
    <div className="flex flex-col gap-5">
      <h1 className="text-2xl font-semibold">新建用户</h1>
      <UserForm mode="create" />
    </div>
  );
}
