"use client";

import { withAuthGuard } from "@/components/auth-guard";

function DemoPage() {
  return <>Demo Page</>;
}

export default withAuthGuard(DemoPage);
