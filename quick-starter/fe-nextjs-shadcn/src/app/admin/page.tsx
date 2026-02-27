"use client";

import { Shield } from "lucide-react";
import { useState } from "react";

import { AdminRoute } from "@/components/AdminRoute";
import { AdminSidebar, type AdminNavItem } from "@/components/AdminSidebar";
import { Header } from "@/components/Header";
import { SystemSettings } from "@/components/admin/SystemSettings";
import { UserManagement } from "@/components/admin/UserManagement";

export default function AdminPage() {
  const [activeItem, setActiveItem] = useState<AdminNavItem>("users");

  const renderContent = () => {
    switch (activeItem) {
      case "users":
        return <UserManagement />;
      case "settings":
        return <SystemSettings />;
      default:
        return <UserManagement />;
    }
  };

  return (
    <AdminRoute>
      <div className="min-h-screen bg-background">
        <Header />
        <div className="flex">
          <AdminSidebar activeItem={activeItem} onItemChange={setActiveItem} />
          <main className="flex-1 p-6">
            <div className="mx-auto max-w-6xl">
              <div className="mb-6 flex items-center gap-3 md:hidden">
                <Shield className="h-6 w-6 text-muted-foreground" />
                <h1 className="text-2xl font-semibold">管理面板</h1>
              </div>
              {renderContent()}
            </div>
          </main>
        </div>
      </div>
    </AdminRoute>
  );
}
