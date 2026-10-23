import { redirect } from "next/navigation";
import { DEFAULT_NEXT_PATH } from "@/lib/session/next-path";

/** 默认页面：用户管理。 */
export default function ShellIndexPage(): never {
  redirect(DEFAULT_NEXT_PATH);
}
