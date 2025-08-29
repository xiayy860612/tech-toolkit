export interface PageData<T> {
  index: number;
  pageSize: number;
  items: T[];
  total: number;
  pageAmount: number;
}
