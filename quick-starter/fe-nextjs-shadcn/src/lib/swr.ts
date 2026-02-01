import useSWR, { SWRConfiguration, SWRResponse } from "swr";
import { apiClient, ApiError, getApiError } from "./api";

// SWR 默认配置
const defaultConfig: SWRConfiguration = {
  revalidateOnFocus: false,
  revalidateOnReconnect: true,
  dedupingInterval: 5000,
  shouldRetryOnError: false,
};

// Fetcher 函数：使用 axios 实例
async function fetcher<T>(url: string): Promise<T> {
  const response = await apiClient.get<T>(url);
  return response.data;
}

// 带参数的 fetcher
async function fetcherWithParams<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await apiClient.get<T>(url, { params });
  return response.data;
}

// 通用 SWR Hook
export function useRequest<T>(
  url: string | null,
  config?: SWRConfiguration
): SWRResponse<T, ApiError> {
  const swrResponse = useSWR<T>(url, fetcher, {
    ...defaultConfig,
    ...config,
  });

  // 转换错误类型
  const error = swrResponse.error ? getApiError(swrResponse.error) : undefined;

  return {
    ...swrResponse,
    error,
  };
}

// 带参数的 SWR Hook
export function useRequestWithParams<T>(
  url: string | null,
  params?: Record<string, unknown>,
  config?: SWRConfiguration
): SWRResponse<T, ApiError> {
  const key = params ? [url, params] : url;

  const swrResponse = useSWR<T>(
    key,
    () => (url ? fetcherWithParams<T>(url, params) : Promise.reject("No URL provided")),
    {
      ...defaultConfig,
      ...config,
    }
  );

  // 转换错误类型
  const error = swrResponse.error ? getApiError(swrResponse.error) : undefined;

  return {
    ...swrResponse,
    error,
  };
}

// 条件请求 Hook
export function useConditionalRequest<T>(
  condition: boolean,
  url: string,
  config?: SWRConfiguration
): SWRResponse<T, ApiError> {
  return useRequest<T>(condition ? url : null, config);
}

// 导出 SWR Mutation Hook
import useSWRMutation from "swr/mutation";
export { useSWRMutation };

// Mutation fetcher
export async function mutationFetcher<T>(url: string, { arg }: { arg: T }) {
  const response = await apiClient.post(url, arg);
  return response.data;
}

// PUT mutation fetcher
export async function mutationPutFetcher<T>(url: string, { arg }: { arg: T }) {
  const response = await apiClient.put(url, arg);
  return response.data;
}

// DELETE mutation fetcher
export async function mutationDeleteFetcher(url: string) {
  const response = await apiClient.delete(url);
  return response.data;
}