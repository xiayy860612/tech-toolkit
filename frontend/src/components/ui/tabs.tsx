"use client";

import { Tabs as TabsPrimitive } from "radix-ui";
import type { ComponentProps } from "react";
import { cn } from "@/lib/utils";

export const Tabs = TabsPrimitive.Root;

export function TabsList({ className, ...props }: ComponentProps<typeof TabsPrimitive.List>) {
  return (
    <TabsPrimitive.List className={cn("flex gap-6 border-b border-border", className)} {...props} />
  );
}

export function TabsTrigger({ className, ...props }: ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      className={cn(
        "-mb-px min-h-11 border-b-2 border-transparent px-1 text-sm text-text-muted hover:text-text data-[state=active]:border-accent data-[state=active]:font-medium data-[state=active]:text-accent",
        className,
      )}
      {...props}
    />
  );
}
