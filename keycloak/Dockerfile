# 生产镜像：先 build 固化构建期选项，再以 --optimized 启动（启动更快、配置不可在运行期漂移）
# KEYCLOAK_BASE_IMAGE 可指向国内镜像仓库中的同版本镜像（见 DEPLOYMENT.md「腾讯云」一节）
ARG KEYCLOAK_BASE_IMAGE=quay.io/keycloak/keycloak:26.4

FROM ${KEYCLOAK_BASE_IMAGE} AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK_BASE_IMAGE}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
