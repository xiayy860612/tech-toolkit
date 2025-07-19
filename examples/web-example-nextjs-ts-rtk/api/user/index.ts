import { createApi } from "@reduxjs/toolkit/query/react";
import baseQuery from "../base-query";

export interface UserInfo {
  name: string;
  avatar: string;
}

export interface GetUserInfoResponse {
  user: UserInfo;
}

export const GET_USER_INFO_URL = "/api/auth/me";

export const userApi = createApi({
  reducerPath: "userApi",
  baseQuery: baseQuery(),
  endpoints: (builder) => ({
    getUserInfo: builder.query<GetUserInfoResponse, void>({
      query: () => ({
        url: GET_USER_INFO_URL,
        method: "GET",
      }),
    }),
  }),
});

export const { useGetUserInfoQuery } = userApi;
