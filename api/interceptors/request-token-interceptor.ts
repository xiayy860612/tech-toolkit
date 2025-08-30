import { getToken } from "@/store/bearer-token-utils";

const requestTokenInterceptor = (config: any) => {
  const token = getToken();
  if (!("Authorization" in config.headers) && token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
};

export default requestTokenInterceptor;
