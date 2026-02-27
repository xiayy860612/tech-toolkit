/** User management API functions. */

import { apiClient } from "./api";

/** Get all users. */
export function getUsers() {
  return apiClient.get("/users");
}

/** Create a new user. */
export function createUser(data: {
  username: string;
  password: string;
  display_name: string;
  role: string;
}) {
  return apiClient.post("/users", data);
}

/** Update an existing user. */
export function updateUser(
  id: number,
  data: {
    display_name?: string;
    role?: string;
    is_active?: boolean;
  }
) {
  return apiClient.put(`/users/${id}`, data);
}

/** Delete a user. */
export function deleteUser(id: number) {
  return apiClient.delete(`/users/${id}`);
}
