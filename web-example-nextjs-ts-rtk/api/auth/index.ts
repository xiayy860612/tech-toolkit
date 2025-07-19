import { createApi } from "@reduxjs/toolkit/query/react";
import baseQuery from "../base-query";
import { saveAccessToken } from "../interceptors/access-token-interceptor";
import { UserInfo } from "../user";

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  user: UserInfo;
}

export const LOGIN_API = "/api/auth/basic/login";

export const authApi = createApi({
  reducerPath: "authApi",
  baseQuery: baseQuery(),
  endpoints: (builder) => ({
    login: builder.mutation<LoginResponse, LoginRequest>({
      query: (request) => {
        const credentials = `${request.username}:${request.password}`;
        const token = btoa(credentials);
        return {
          url: LOGIN_API,
          method: "POST",
          headers: {
            Authorization: `Basic ${token}`,
          },
        };
      },
      transformResponse: (response: LoginResponse) => {
        saveAccessToken(response.accessToken);
        return response;
      },
    }),
  }),
});

export const { useLoginMutation } = authApi;
