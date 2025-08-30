"use client";

import initMocks from "@/api/mocks";
import { store } from "@/store";
import "@ant-design/v5-patch-for-react-19";
import { Layout } from "antd";
import { Provider } from "react-redux";
import Header from "./Header";
import styles from "./index.module.css";

const { Content, Footer } = Layout;

if (process.env.NEXT_PUBLIC_API_MOCKING === "enabled") {
  initMocks();
}

export default function ClientGlobalLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <Provider store={store}>
      <Layout>
        <Header />
        <Content className={styles.content}>{children}</Content>
        <Footer style={{ textAlign: "center" }}>Footer</Footer>
      </Layout>
    </Provider>
  );
}
