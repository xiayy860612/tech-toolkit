"use client";

import { getUserInfo } from "@/api/auth/getUserInfo";
import { useAppDispatch } from "@/store";
import { setUserAction, useUser } from "@/store/user";
import { MenuInfo } from "@/types/model/user";
import { Button, Layout, Menu } from "antd";
import Link from "next/link";
import { useEffect, useState } from "react";
import { UserCard } from "../user-card";
import styles from "./index.module.css";

const { Header: AntdHeader } = Layout;

export default function Header() {
  const { user, menus } = useUser();
  const dispatch = useAppDispatch();
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const init = async () => {
      try {
        const user = await getUserInfo();
        dispatch(setUserAction(user));
      } catch (ex) {
        console.log(ex);
      } finally {
        setIsLoading(false);
      }
    };
    init();
  }, [dispatch]);

  const menuRender = (item: MenuInfo) => {
    return {
      key: item.name,
      label: (
        <Link href={item.link} passHref>
          {item.name}
        </Link>
      ),
    };
  };

  return (
    <AntdHeader
      className={`w-full ${styles.header}`}
      style={{ padding: "0 1rem" }}
    >
      <div className="logo">
        <Link href="/" passHref>
          <h5 className={styles.title}>Logo</h5>
        </Link>
      </div>
      <Menu
        className={styles.menu}
        theme="dark"
        mode="horizontal"
        items={menus.map((menu) => menuRender(menu))}
      />
      <div className={styles.userInfo}>
        {user ? (
          <UserCard user={user} />
        ) : (
          <Button type="primary" loading={isLoading}>
            <Link href="/login" passHref>
              Login
            </Link>
          </Button>
        )}
      </div>
    </AntdHeader>
  );
}
