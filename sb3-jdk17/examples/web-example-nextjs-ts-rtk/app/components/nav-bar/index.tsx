"use client";

import { useGetUserInfoQuery } from "@/api/user";
import { useAppDispatch, useAppSelector } from "@/store";
import { selectUser, setUserAction } from "@/store/user";
import { Button, Menu } from "antd";
import { ItemType } from "antd/es/menu/interface";
import Link from "next/link";
import { useEffect } from "react";
import { UserCard } from "../user-card";
import styles from "./styles.module.css";

interface MenuItem {
  name: string;
  link?: string;
  subItems?: MenuItem[];
}

export function NavBar() {
  const { user } = useAppSelector(selectUser);
  const { data, isSuccess, isLoading } = useGetUserInfoQuery();
  const dispatch = useAppDispatch();

  const menuItems: MenuItem[] = [
    {
      name: "Demo Page",
      link: "/demo-page",
    },
  ];

  useEffect(() => {
    if (isSuccess) {
      dispatch(setUserAction(data.user));
    }
  }, [isSuccess, data, dispatch]);

  const menuItemRender = (item: MenuItem): ItemType => {
    if (item.link) {
      return {
        key: item.name,
        label: (
          <Link href={item.link} passHref>
            {item.name}
          </Link>
        ),
      };
    }

    return {
      key: item.name,
      label: item.name,
      children: item.subItems?.map((subItem) => menuItemRender(subItem)),
    };
  };

  return (
    <div className={styles.header}>
      <div className="logo">
        <Link href="/" passHref>
          <h5 className={styles.title}>Logo</h5>
        </Link>
      </div>
      <Menu
        className={styles.menu}
        theme="dark"
        mode="horizontal"
        items={menuItems.map((item) => menuItemRender(item))}
      />
      <div className={styles.userInfo}>
        {user ? (
          <UserCard userInfo={user} />
        ) : (
          <Button type="primary" loading={isLoading}>
            <Link href="/login" passHref>
              Login
            </Link>
          </Button>
        )}
      </div>
    </div>
  );
}
