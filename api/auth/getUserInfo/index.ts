import apiClient from "@/api/axios-client";
import { UserInfo } from "@/types/model/user";

export interface GetUserInfoResponse {
  user: UserInfo;
}

export const GET_USER_INFO_URL = "/api/auth/me";

export const getUserInfo = async () => {
  const response = await apiClient.get<GetUserInfoResponse>(GET_USER_INFO_URL);
  return response.data.user;
};
