"use client";

import { useState } from "react";
import { Pencil, Trash2, UserPlus } from "lucide-react";
import { useSWRMutation, useRequest } from "@/lib/swr";
import * as userApi from "@/lib/user-api";
import { useToast } from "@/hooks/use-toast";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

interface User {
  id: number;
  username: string;
  display_name: string;
  role: string;
  is_active: boolean;
  created_at: string;
}

export function UserManagement() {
  const { toast } = useToast();
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [editDialogOpen, setEditDialogOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
  const [selectedUser, setSelectedUser] = useState<User | null>(null);

  // Create user form state
  const [createFormData, setCreateFormData] = useState({
    username: "",
    password: "",
    display_name: "",
    role: "user",
  });

  // Edit user form state
  const [editFormData, setEditFormData] = useState({
    display_name: "",
    role: "",
    is_active: true,
  });

  // Fetch users
  const { data: users = [], error, mutate } = useRequest<User[]>("/users");

  // Create user mutation
  const { trigger: createUser, isMutating: isCreating } = useSWRMutation(
    "/users",
    (_url: string, { arg }: { arg: typeof createFormData }) =>
      userApi.createUser(arg).then((res) => res.data),
    {
      onSuccess: () => {
        mutate();
        setCreateDialogOpen(false);
        setCreateFormData({ username: "", password: "", display_name: "", role: "user" });
        toast({ title: "成功", description: "用户创建成功" });
      },
      onError: (err: any) => {
        const message = err.response?.data?.detail || "创建用户失败";
        toast({ title: "错误", description: message, variant: "destructive" });
      },
    }
  );

  // Update user mutation
  const { trigger: updateUser, isMutating: isUpdating } = useSWRMutation(
    `/users/${selectedUser?.id}`,
    (_url: string, { arg }: { arg: typeof editFormData }) =>
      userApi.updateUser(selectedUser!.id, arg).then((res) => res.data),
    {
      onSuccess: () => {
        mutate();
        setEditDialogOpen(false);
        setSelectedUser(null);
        toast({ title: "成功", description: "用户更新成功" });
      },
      onError: (err: any) => {
        const message = err.response?.data?.detail || "更新用户失败";
        toast({ title: "错误", description: message, variant: "destructive" });
      },
    }
  );

  // Delete user mutation
  const { trigger: deleteUser, isMutating: isDeleting } = useSWRMutation(
    `/users/${selectedUser?.id}`,
    (_url: string) => userApi.deleteUser(selectedUser!.id),
    {
      onSuccess: () => {
        mutate();
        setDeleteDialogOpen(false);
        setSelectedUser(null);
        toast({ title: "成功", description: "用户删除成功" });
      },
      onError: (err: any) => {
        const message = err.response?.data?.detail || "删除用户失败";
        toast({ title: "错误", description: message, variant: "destructive" });
      },
    }
  );

  const handleCreateSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    createUser(createFormData);
  };

  const handleEditClick = (user: User) => {
    setSelectedUser(user);
    setEditFormData({
      display_name: user.display_name,
      role: user.role,
      is_active: user.is_active,
    });
    setEditDialogOpen(true);
  };

  const handleEditSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    updateUser(editFormData);
  };

  const handleDeleteClick = (user: User) => {
    setSelectedUser(user);
    setDeleteDialogOpen(true);
  };

  const handleDeleteConfirm = () => {
    deleteUser();
  };

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleDateString("zh-CN", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    });
  };

  if (error) {
    return (
      <div className="rounded-lg border bg-card p-6">
        <h2 className="mb-4 text-xl font-semibold">用户管理</h2>
        <p className="text-destructive">加载用户列表失败</p>
      </div>
    );
  }

  return (
    <div className="rounded-lg border bg-card p-6">
      <div className="mb-6 flex items-center justify-between">
        <h2 className="text-xl font-semibold">用户管理</h2>
        <Dialog open={createDialogOpen} onOpenChange={setCreateDialogOpen}>
          <DialogTrigger asChild>
            <Button>
              <UserPlus className="mr-2 h-4 w-4" />
              新建用户
            </Button>
          </DialogTrigger>
          <DialogContent className="sm:max-w-[425px]">
            <DialogHeader>
              <DialogTitle>新建用户</DialogTitle>
              <DialogDescription>创建一个新的用户账户</DialogDescription>
            </DialogHeader>
            <form onSubmit={handleCreateSubmit}>
              <div className="grid gap-4 py-4">
                <div className="grid gap-2">
                  <Label htmlFor="username">用户名</Label>
                  <Input
                    id="username"
                    value={createFormData.username}
                    onChange={(e) =>
                      setCreateFormData({ ...createFormData, username: e.target.value })
                    }
                    placeholder="请输入用户名"
                    required
                    minLength={3}
                    disabled={isCreating}
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="password">密码</Label>
                  <Input
                    id="password"
                    type="password"
                    value={createFormData.password}
                    onChange={(e) =>
                      setCreateFormData({ ...createFormData, password: e.target.value })
                    }
                    placeholder="请输入密码（至少8位）"
                    required
                    minLength={8}
                    disabled={isCreating}
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="display_name">显示名称</Label>
                  <Input
                    id="display_name"
                    value={createFormData.display_name}
                    onChange={(e) =>
                      setCreateFormData({ ...createFormData, display_name: e.target.value })
                    }
                    placeholder="请输入显示名称"
                    required
                    disabled={isCreating}
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="role">角色</Label>
                  <Select
                    value={createFormData.role}
                    onValueChange={(value) =>
                      setCreateFormData({ ...createFormData, role: value })
                    }
                    disabled={isCreating}
                  >
                    <SelectTrigger>
                      <SelectValue placeholder="选择角色" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="user">普通用户</SelectItem>
                      <SelectItem value="admin">管理员</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
              <DialogFooter>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setCreateDialogOpen(false)}
                  disabled={isCreating}
                >
                  取消
                </Button>
                <Button type="submit" disabled={isCreating}>
                  {isCreating ? "创建中..." : "创建"}
                </Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>用户名</TableHead>
              <TableHead>显示名称</TableHead>
              <TableHead>角色</TableHead>
              <TableHead>状态</TableHead>
              <TableHead>创建时间</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {users.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="h-24 text-center">
                  暂无用户数据
                </TableCell>
              </TableRow>
            ) : (
              users.map((user) => (
                <TableRow key={user.id}>
                  <TableCell className="font-medium">{user.username}</TableCell>
                  <TableCell>{user.display_name}</TableCell>
                  <TableCell>{user.role === "admin" ? "管理员" : "普通用户"}</TableCell>
                  <TableCell>
                    <span
                      className={`inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ${
                        user.is_active
                          ? "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
                          : "bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-200"
                      }`}
                    >
                      {user.is_active ? "启用" : "禁用"}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {formatDate(user.created_at)}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-2">
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => handleEditClick(user)}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        onClick={() => handleDeleteClick(user)}
                      >
                        <Trash2 className="h-4 w-4 text-destructive" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      {/* Edit User Dialog */}
      <Dialog open={editDialogOpen} onOpenChange={setEditDialogOpen}>
        <DialogContent className="sm:max-w-[425px]">
          <DialogHeader>
            <DialogTitle>编辑用户</DialogTitle>
            <DialogDescription>修改用户信息</DialogDescription>
          </DialogHeader>
          <form onSubmit={handleEditSubmit}>
            <div className="grid gap-4 py-4">
              <div className="grid gap-2">
                <Label htmlFor="edit_display_name">显示名称</Label>
                <Input
                  id="edit_display_name"
                  value={editFormData.display_name}
                  onChange={(e) =>
                    setEditFormData({ ...editFormData, display_name: e.target.value })
                  }
                  placeholder="请输入显示名称"
                  required
                  disabled={isUpdating}
                />
              </div>
              <div className="grid gap-2">
                <Label htmlFor="edit_role">角色</Label>
                <Select
                  value={editFormData.role}
                  onValueChange={(value) => setEditFormData({ ...editFormData, role: value })}
                  disabled={isUpdating}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="选择角色" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="user">普通用户</SelectItem>
                    <SelectItem value="admin">管理员</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  id="edit_is_active"
                  checked={editFormData.is_active}
                  onChange={(e) =>
                    setEditFormData({ ...editFormData, is_active: e.target.checked })
                  }
                  disabled={isUpdating}
                  className="h-4 w-4 rounded border-gray-300"
                />
                <Label htmlFor="edit_is_active" className="cursor-pointer">
                  启用用户
                </Label>
              </div>
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setEditDialogOpen(false)}
                disabled={isUpdating}
              >
                取消
              </Button>
              <Button type="submit" disabled={isUpdating}>
                {isUpdating ? "保存中..." : "保存"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent className="sm:max-w-[425px]">
          <DialogHeader>
            <DialogTitle>确认删除</DialogTitle>
            <DialogDescription>
              确定要删除用户 "{selectedUser?.display_name}" 吗？此操作无法撤销。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setDeleteDialogOpen(false)}
              disabled={isDeleting}
            >
              取消
            </Button>
            <Button
              type="button"
              variant="destructive"
              onClick={handleDeleteConfirm}
              disabled={isDeleting}
            >
              {isDeleting ? "删除中..." : "删除"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
