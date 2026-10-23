import { Fragment } from "react";
import Link from "next/link";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";

export interface Crumb {
  label: string;
  /** 缺省表示当前页（最后一项）。 */
  href?: string;
}

export function Breadcrumbs({ items }: { items: Crumb[] }) {
  return (
    <Breadcrumb aria-label="面包屑" className="mb-2">
      <BreadcrumbList>
        {items.map((item, index) => {
          const last = index === items.length - 1;
          return (
            <Fragment key={`${index}-${item.label}`}>
              <BreadcrumbItem>
                {last || !item.href ? (
                  <BreadcrumbPage className="break-all">{item.label}</BreadcrumbPage>
                ) : (
                  <BreadcrumbLink asChild>
                    <Link href={item.href} className="inline-flex min-h-11 items-center">
                      {item.label}
                    </Link>
                  </BreadcrumbLink>
                )}
              </BreadcrumbItem>
              {!last && <BreadcrumbSeparator />}
            </Fragment>
          );
        })}
      </BreadcrumbList>
    </Breadcrumb>
  );
}
