import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";

export default function NotFound() {
  return (
    <main className="grid min-h-svh place-items-center px-4 py-6">
      <Card className="w-full max-w-md">
        <CardContent className="py-10 text-center">
          <h1 className="mb-4 text-lg font-bold">页面不存在</h1>
          <Button asChild className="h-11">
            <Link href="/users">回到用户管理</Link>
          </Button>
        </CardContent>
      </Card>
    </main>
  );
}
