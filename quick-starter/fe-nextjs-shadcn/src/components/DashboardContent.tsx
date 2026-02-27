"use client";

import { useRouter } from "next/navigation";

import { Header } from "@/components/Header";

export function DashboardContent() {
  const router = useRouter();
  return (
    <div className="min-h-screen bg-background">
      <Header />
      <main className="p-6">
        <div className="mx-auto max-w-4xl">
          <div className="rounded-lg border bg-card p-6">
            <p className="text-muted-foreground">
              Welcome to the Demo dashboard!
            </p>
          </div>
        </div>
      </main>
    </div>
  );
}
