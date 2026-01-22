"use client";

import { ProtectedRoute } from "@/components/ProtectedRoute";
import { Header } from "@/components/Header";

export default function DashboardPage() {
  return (
    <ProtectedRoute>
      <div className="min-h-screen bg-background">
        <Header />
        <main className="p-6">
          <div className="mx-auto max-w-4xl">
            <h2 className="mb-4 text-2xl font-semibold">Dashboard</h2>
            <div className="rounded-lg border bg-card p-6">
              <p className="text-muted-foreground">
                Welcome to the Kids Homework Review dashboard!
              </p>
            </div>
          </div>
        </main>
      </div>
    </ProtectedRoute>
  );
}
