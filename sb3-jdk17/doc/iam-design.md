# IAM Design

factors:

- account, present account from specific platform, it will be used for authentication
- user, unique operator in own system, it will be received after authentication success

```puml
package Authentication {

class Account

class UsernameAccount extends Account {
  username: string
  password: string
}

class WxAccount extends Account {
  wxId: string // openId or unionId
}

class User

Account "1..*" --> "1" User

class UserInfo

User --> UserInfo: create

}

package Authorization {

class Role

class Permission {
  resource: string
  action: string
}

enum Resource {
  buildPermission(id, action): Permission
}
Resource --> Permission: create

}

User --> "0..*" Role
User --> "0..*" Permission

```

## Reference

- [Authorization --- Method Security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
