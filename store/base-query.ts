import { ErrorCode, ErrorResponse } from "@/types/api/response";
import { BaseQueryFn } from "@reduxjs/toolkit/query";
import { AxiosError, AxiosRequestConfig } from "axios";
import apiClient from "./axios-client";

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
      const errorResponse = axiosError.response?.data || {
        code: axiosError.code || ErrorCode.UNKNOWN,
        message: axiosError.message,
      };
      return {
        error: errorResponse,
      };
    }
  };

export default baseQuery;
