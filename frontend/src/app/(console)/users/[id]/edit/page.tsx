import { UserEditor } from "@/features/users/UserEditor";

export default async function EditUserPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <div className="flex flex-col gap-5">
      <h1 className="text-2xl font-semibold">编辑用户</h1>
      <UserEditor id={id} />
    </div>
  );
}
