# FastDFS 本地基础设施（阶段 A）

此目录配合根目录的 `docker-compose.fastdfs.yml` 使用，提供 `1 Tracker + 2 个同组 Storage` 的本地副本拓扑。每个 Storage 的镜像都运行 FastDFS Nginx；独立的 `fastdfs-access-nginx` 通过 Docker 内网反向代理两台 Storage，作为唯一 HTTP 访问入口。

## 访问边界

- 文件服务客户端：`127.0.0.1:22122`（仅后端 FastDFS Client 使用）。
- 浏览器或预览地址：`http://localhost:8888/group1/<remote-path>`。
- Docker 内部节点：`fastdfs-tracker:22122`、`fastdfs-storage-1`、`fastdfs-storage-2`；这些名称和地址不得写入前端配置、分享链接或接口响应。

这样可以避免把 Storage 节点地址暴露给浏览器。访问层使用 Nginx 被动健康切换：某个上游发生连接错误、超时或 5xx 响应时，当前请求会尝试另一台 Storage；`max_fails=2` 与 `fail_timeout=10s` 用于暂时剔除连续失败节点。业务服务仍应先完成登录、权限、分享有效期等校验；本地 Nginx 仅用于当前阶段的预览/下载链路验证，不能替代业务授权。

## 配置约定

镜像 `ygqygq2/fastdfs-nginx:latest` 通过环境变量生成完整 FastDFS 与 Nginx 配置：

- `TRACKER_SERVER=fastdfs-tracker:22122`
- `GROUP_NAME=group1`
- Tracker 与 Storage 的持久化目录均为容器内 `/var/fdfs`

`tracker.conf.example`、`storage.conf.example`、`nginx.conf.example` 是配置契约和排障参考，不直接挂载。该镜像在 `CUSTOM_CONFIG=true` 时要求挂载一整套配置目录；只覆盖单个文件容易遗漏 `client.conf`、`mod_fastdfs.conf` 等关联配置，导致容器启动后注册或 HTTP 访问异常。需要自定义时，应在单独变更中完整复制镜像默认配置、修改后再将 `CUSTOM_CONFIG` 显式设为 `true`。

## 启动与诊断

在仓库根目录执行：

```powershell
docker compose -f docker-compose.fastdfs.yml up -d
docker compose -f docker-compose.fastdfs.yml ps
docker compose -f docker-compose.fastdfs.yml logs --tail 100 fastdfs-tracker fastdfs-storage-1 fastdfs-storage-2
```

容器显示 `healthy` 仅说明进程健康，不等于文件已完成双副本同步。查看 Tracker 注册的两个 Storage：

```powershell
docker exec networkdisk-fastdfs-storage-1 fdfs_monitor /etc/fdfs/client.conf
```

如镜像版本中的 `client.conf` 路径不同，先执行 `docker exec networkdisk-fastdfs-storage-1 find /etc/fdfs -name client.conf`，再将上面命令中的路径替换为实际值。不要把容器内的 IP 地址写入应用配置。

## 副本延迟验证（待独立验收 Agent 执行）

1. 在 Storage-1 容器内使用 `fdfs_upload_file` 上传一个带 SHA-256 记录的测试文件，保存返回的 `group1/M00/...` 路径。
2. 使用 `fdfs_monitor` 确认两个 Storage 都处于 `ACTIVE`；以轮询方式检查两个持久卷中对应文件并比对 SHA-256。
3. 记录“上传完成到第二副本出现”的时长。该时长是本机环境观测值，不作为生产 SLA。

## 单节点故障演练与恢复

停止 Storage-2（保留数据卷）：

```powershell
docker compose -f docker-compose.fastdfs.yml stop fastdfs-storage-2
docker compose -f docker-compose.fastdfs.yml ps
```

此时验证 Storage-1 的 HTTP 地址仍能读取已上传文件，并记录 Tracker 对 Storage-2 的离线状态。恢复：

```powershell
docker compose -f docker-compose.fastdfs.yml start fastdfs-storage-2
docker compose -f docker-compose.fastdfs.yml logs --tail 100 fastdfs-storage-2
```

停止 Storage-1 后，访问层会将请求切到 Storage-2；再次访问相同 `8888` URL 并保存 HTTP 状态、响应内容校验和作为证据。访问层自身仍是单容器，停止 `fastdfs-access-nginx` 会导致 `8888` 暂时不可用；本阶段不能表述为高可用 HTTP 网关。

## 回滚

停止并移除容器和网络，但**保留命名卷**：

```powershell
docker compose -f docker-compose.fastdfs.yml down
```

排障期间不要执行 `docker compose -f docker-compose.fastdfs.yml down -v`，它会删除测试数据卷。确需清空环境时，先由负责人确认无保留价值后再删除三个 `networkdisk-fastdfs-*-data` 命名卷。
