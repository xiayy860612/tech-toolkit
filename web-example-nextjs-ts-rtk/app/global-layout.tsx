"use client";

import initMocks from "@/api/mocks";
import { store } from "@/store";
import config from "@/theme/themeConfig";
import { AntdRegistry } from "@ant-design/nextjs-registry";
import "@ant-design/v5-patch-for-react-19";
import { ConfigProvider, Layout } from "antd";
import { Provider } from "react-redux";
import { NavBar } from "./components/nav-bar";
import styles from "./global-layout.module.css";

const { Header, Content, Footer } = Layout;

if (process.env.NEXT_PUBLIC_API_MOCKING === "enabled") {
  initMocks();
}

export function ClientGlobalLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <Provider store={store}>
      <ConfigProvider theme={config}>
        <AntdRegistry>
          <Layout>
            <Header className="w-full">
              <NavBar />
            </Header>
            <Content className={styles.content}>{children}</Content>
            <Footer style={{ textAlign: "center" }}>Footer</Footer>
          </Layout>
        </AntdRegistry>
      </ConfigProvider>
    </Provider>
  );
}
