import { saveToken } from "@/store/bearer-token-utils";
import { UserInfo } from "@/types/model/user";
import apiClient from "../../axios-client";

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  user: UserInfo;
}

export const LOGIN_API = "/api/auth/login";
export const login = async (request: LoginRequest) => {
  const credentials = `${request.username}:${request.password}`;
  const token = btoa(credentials);
  const response = await apiClient.post<LoginResponse>(LOGIN_API, null, {
    headers: {
      Authorization: `Basic ${token}`,
    },
  });

  const { user, accessToken } = response.data;
  saveToken(accessToken);
  return user;
};
