import { getIdToken as getBearerToken } from "@/store/auth";

export const requestInterceptor = async (config: any) => {
  const token = await getBearerToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
};
