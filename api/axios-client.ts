import axios, {
  AxiosError,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from "axios";
import { ErrorResponse } from ".";
import requestTokenInterceptor from "./interceptors/request-token-interceptor";

const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_HOST || "http://localhost:3000",
  timeout: 60000,
  headers: {
    "Content-Type": "application/json",
  },
});

apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    return requestTokenInterceptor(config);
  },
  (error) => Promise.reject(error)
);

apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    return response;
  },
  (error: AxiosError<ErrorResponse>) => {
    console.error(error);
    const errorResponse: ErrorResponse = error.response?.data ?? {
      status: error.status,
      message: error.message,
    };
    return Promise.reject(errorResponse);
  }
);

export default apiClient;
