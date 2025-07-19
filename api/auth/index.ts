import { createApi } from "@reduxjs/toolkit/query/react";
import baseQuery from "../base-query";
import { UserInfo } from "../user";

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  user: UserInfo;
}

export const LOGIN_API = "/api/auth/login";

export const authApi = createApi({
  reducerPath: "authApi",
  baseQuery: baseQuery(),
  endpoints: (builder) => ({
    login: builder.mutation<LoginResponse, LoginRequest>({
      query: (credentials) => ({
        url: LOGIN_API,
        method: "POST",
        body: credentials,
      }),
    }),
  }),
});

export const { useLoginMutation } = authApi;
