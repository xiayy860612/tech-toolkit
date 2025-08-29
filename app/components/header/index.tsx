"use client";

import { useSession } from "@/store/session";
import { Button, Grid, Typography } from "@mui/material";
import Link from "next/link";
import { useRouter } from "next/navigation";
import AccountMenu from "../account-menu";
import styles from "./index.module.scss";

export default function Header() {
  const router = useRouter();
  const { user, menus } = useSession();
  // const dispatch = useAppDispatch();

  const handleLogin = async () => {
    router.push("/login");
  };

  const handleLogout = async () => {
    // const response = await getLogoutUrl();
    // window.location.href = response.data.url;
  };

  // useEffect(() => {
  //   const idToken = getIdToken();
  //   if (!idToken) {
  //     return;
  //   }

  //   const { user } = getUserInfoFromIdToken(idToken);
  //   dispatch(setUserAction(user));
  // }, [dispatch]);

  return (
    <Grid container spacing={2}>
      <Grid size={2} className={styles.title}>
        <Typography variant="h6" textAlign="center">
          Title
        </Typography>
      </Grid>
      <Grid className={styles.menu} gap={2} size="grow">
        {menus.map((menu) => {
          return (
            <Link key={`menu-${menu.name.toLowerCase()}`} href={menu.link}>
              {menu.name}
            </Link>
          );
        })}
      </Grid>
      <Grid
        container
        size={2}
        sx={{
          justifyContent: "center",
        }}
      >
        {user ? (
          <AccountMenu user={user} onLogout={handleLogout} />
        ) : (
          <Button variant="outlined" onClick={handleLogin}>
            Login
          </Button>
        )}
      </Grid>
    </Grid>
  );
}
