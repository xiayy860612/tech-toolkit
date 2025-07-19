# Spring boot 3 toolkit

## Tech Stack

- Java 17
- Spring Boot 3.x
- Spring Security
- Maven
- google-java-formatter

## Provided Features

- Integrate Swagger
- OAuth2 JWT bearer token authentication
- Global Error Handler

## Local Develop

1. install `sb3-jdk17-security-tk-parent` and `s2u2m-resource-service-starter`

```shell
$ mvn clean install
```

2. copy `service-example-h2` project and rename to new service name
3. run new service and make sure no error raised
4. start to implement new features based on it.

## Deployment



```bash
# push artifacts to repository
$ mvn clean install deploy:deploy
```
