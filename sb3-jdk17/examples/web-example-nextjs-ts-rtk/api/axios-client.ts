import axios, {
  AxiosError,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from "axios";
import accessTokenInterceptors from "./interceptors/access-token-interceptor";

const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_HOST || "http://localhost:3000",
  timeout: 60000,
  headers: {
    "Content-Type": "application/json",
  },
});

const [requestInterceptor] = accessTokenInterceptors;

apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    return requestInterceptor(config);
  },
  (error) => Promise.reject(error)
);

apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    return response;
  },
  (error: AxiosError) => {
    return Promise.reject(error);
  }
);

export default apiClient;
