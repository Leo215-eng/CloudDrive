# Distributed Cloud Drive

基于 Spring Boot、Spring Cloud 和 Dubbo 的分布式网盘后端，采用 Maven 多模块结构，包含认证、网关、用户、文件、分享、回收站和 AI 文档处理等服务。

## 技术栈

- Java 21、Spring Boot 3.2.2、Spring Cloud 2023.0.0
- Spring Cloud Alibaba、Nacos、Dubbo
- Sa-Token、Redis、MySQL、Elasticsearch、RocketMQ
- FastDFS / 阿里云 OSS

## 项目结构

```text
networkdisk-auth/       认证服务
networkdisk-gateway/    API 网关
networkdisk-business/   业务服务（用户、文件、分享、回收站、通知、AI 等）
networkdisk-common/     公共组件（缓存、数据源、RPC、消息队列、限流等）
```

## 本地运行

### 环境要求

- JDK 21
- Maven 3.9+
- Nacos（默认 `127.0.0.1:8848`）
- 按需启动 Redis、MySQL、Elasticsearch、RocketMQ、PostgreSQL/pgvector 和文件存储服务

### 构建

```bash
mvn clean package -DskipTests
```

### 配置

公共中间件配置位于各 `networkdisk-common` 子模块的 `src/main/resources` 目录。启动前请根据本地环境修改数据库、缓存、注册中心、消息队列和文件存储地址。

AI 与文件服务提供了配置示例：

- `networkdisk-business/networkdisk-ai/src/main/resources/application_example.yml`
- `networkdisk-business/networkdisk-files/src/main/resources/application_example.yml`

不要将真实密码、Access Key 或 API Key 提交到仓库。

### 启动服务

先启动 Nacos 和所需中间件，再按需运行各模块中的 `*Application.java`。常用入口包括：

- `NetworkdiskAuthApplication`：认证服务，默认端口 `8090`
- `NetworkdiskGatewayApplication`：网关服务，默认端口 `8081`
- `NetworkdiskFilesApplication`：文件服务，默认端口 `8082`
- `NetworkdiskAiApplication`：AI 服务，默认端口 `8087`

请求统一通过网关访问。

## License

本项目暂未声明开源许可证。
