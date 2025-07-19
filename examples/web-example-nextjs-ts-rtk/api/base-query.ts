import apiClient from "@/api/axios-client";
import { BaseQueryFn } from "@reduxjs/toolkit/query";
import { AxiosError, AxiosRequestConfig } from "axios";

const baseUrl = process.env.NEXT_PUBLIC_API_HOST || "http://localhost:3000";

type RequestType = {
  url: string;
  method?: AxiosRequestConfig["method"];
  data?: AxiosRequestConfig["data"];
  params?: AxiosRequestConfig["params"];
  headers?: AxiosRequestConfig["headers"];
};

const baseQuery =
  (): BaseQueryFn<RequestType, unknown, ErrorResponse> =>
  async ({ url, method, data, params, headers }) => {
    try {
      const result = await apiClient.request({
        url: baseUrl + url,
        method,
        data,
        params,
        headers,
      });
      return { data: result.data };
    } catch (error) {
      const axiosError = error as AxiosError<ErrorResponse>;
      const errorInfo = axiosError.response?.data || {
        code: axiosError.code,
        message: axiosError.message,
      };
      return {
        error: {
          status: axiosError.response?.status || 500,
          ...errorInfo,
        },
      };
    }
  };

export default baseQuery;
