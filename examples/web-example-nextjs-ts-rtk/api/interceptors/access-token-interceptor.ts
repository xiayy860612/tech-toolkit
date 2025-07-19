const ACCESS_TOKEN_KEY = "access_token";

const requestInterceptor = (config: any) => {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
};

export const saveAccessToken = (token: string) => {
  localStorage.setItem(ACCESS_TOKEN_KEY, token);
};

export const clearAccessToken = () => {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
};

const accessTokenInterceptors = [requestInterceptor];
export default accessTokenInterceptors;
