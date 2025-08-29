import { LOGIN_ROUTE_PATH } from "@/store/auth";
import axios, {
  AxiosError,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from "axios";
import { requestInterceptor } from "./interceptors/access-token-interceptor";
import { responseConverterInterceptor } from "./interceptors/response-converter-interceptor";

const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_HOST || "http://localhost:3000",
  timeout: 60000,
});

apiClient.interceptors.request.use(
  async (config: InternalAxiosRequestConfig) => {
    if (!config.headers["Content-Type"]) {
      config.headers["Content-Type"] = "application/json";
    }
    return await requestInterceptor(config);
  },
  (error) => Promise.reject(error)
);

apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    return responseConverterInterceptor(response);
  },
  (error: AxiosError) => {
    if (error.code === "401" || error.status === 401) {
      window.location.href = LOGIN_ROUTE_PATH;
      return Promise.resolve();
    }
    return Promise.reject(error);
  }
);

export default apiClient;
