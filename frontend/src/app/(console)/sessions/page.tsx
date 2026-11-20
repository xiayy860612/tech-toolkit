import { SessionsTable } from "@/features/sessions/SessionsTable";

export default function SessionsPage() {
  return (
    <div className="flex flex-col gap-5">
      <h1 className="text-2xl font-semibold">会话管理</h1>
      <SessionsTable />
    </div>
  );
}
