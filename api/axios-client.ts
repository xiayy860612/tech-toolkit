import axios, { AxiosResponse, InternalAxiosRequestConfig } from "axios";

const apiClient = axios.create({
  baseURL: process.env.API_HOST || "http://localhost:3000",
  timeout: 10000,
  headers: {
    "Content-Type": "application/json",
  },
});

const HEADER_X_AUTH_TOKEN = "x-auth-token";
const KEY_ACCESS_TOKE = "access_token";

apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem(KEY_ACCESS_TOKE);
    if (token) {
      config.headers[HEADER_X_AUTH_TOKEN] = token;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    const existed = localStorage.getItem(KEY_ACCESS_TOKE);
    const latest = response.headers[HEADER_X_AUTH_TOKEN];
    if (latest && latest !== existed) {
      localStorage.setItem(KEY_ACCESS_TOKE, latest);
    }
    return response;
  },
  (error) => {
    if (error.response?.status === 401) {
      window.location.href = "/login";
    }
    return Promise.reject(error);
  }
);

export default apiClient;
