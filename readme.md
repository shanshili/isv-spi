# isv-spi

## 概要
- 实现云市场对服务商的事件（创建、续费、过期、释放）处理，并提供计算巢实例与云市场实例的关联检查接口。
- 目的：演示 token 验证、实例生命周期管理、持久化以及与外部系统（通过生成状态文件并触发 nginx reload）联动的实现思路。

## 主要组件

- 控制器：`com.isv.spi.controllers.IsvController`
  - 提供 `/isv?action=... `与 `/isv/check` 接口。
- 持久化：`com.isv.spi.services.StorageService`
  - 内存 + 序列化文件持久化（`STORAGE_DIR = opt/isv-spi-data/`）。
  - orderBizId 为主键，并维护 aliUid -> orderBizId 列表索引。
- 模型：`com.isv.spi.models.UserInfo`
  - 包含 orderBizId、aliUid、instanceId、expiredOn、status、computeNestInstanceId 等字段。
- Token 规则：基于请求参数排序拼接 + SECRET_KEY，然后 MD5（小写 hex）。

## 构建与运行
构建：
```sh
mvn clean package
# 部署生成的 WAR 到兼容 Servlet 4.0 的容器
```

## API 概览（示例）
所有 `/isv?action=...` 接口均为 POST。token 必填（参见 Token 规则）。

**1) 创建实例**

- URL: `POST /isv?action=createInstance`
- 必选参数: orderBizId, aliUid, token
- 返回示例:
  ```json
  {"instanceId":"<orderBizId>","aliUid":"<aliUid>","password":"tskyide"}
  ```
- curl 示例：
  ```sh
  curl -X POST 'https://your-host/isv?action=createInstance' \
    -d 'orderBizId=ORD123&aliUid=10001&token=GENERATED_TOKEN'
  ```

**2) 续费实例**

- URL: `POST /isv?action=renewInstance`
- 必选参数: instanceId, orderId, expiredOn, token
- expiredOn 格式示例: `2025-12-23 22:30:30`
- curl 示例：
  ```sh
  curl -X POST 'https://your-host/isv?action=renewInstance' \
    -d 'instanceId=ORD123&orderId=order123&expiredOn=2025-12-23 22:30:30&token=GENERATED_TOKEN'
  ```

**3) 标记过期**

- URL: `POST /isv?action=expiredInstance`
- 必选参数: instanceId, token
- curl 示例：
  ```sh
  curl -X POST 'https://your-host/isv?action=expiredInstance' \
    -d 'instanceId=ORD123&token=GENERATED_TOKEN'
  ```

**4) 释放实例**

- URL: `POST /isv?action=releaseInstance`
- 必选参数: instanceId, isRefund, token
- isRefund：true/false
- curl 示例：
  ```sh
  curl -X POST 'https://your-host/isv?action=releaseInstance' \
    -d 'instanceId=ORD123&isRefund=true&token=GENERATED_TOKEN'
  ```

**5) 计算巢检查（检查是否存在有效云市场实例）**

- URL: `POST /isv/check`
- 参数（表单或 JSON）：aliuid, instanceid, apikey
- 返回：`true` 或 `false`
- curl 示例（JSON）：
  ```sh
  curl -X POST 'https://your-host/isv/check' \
    -H "Content-Type: application/json" \
    -d '{"aliuid":"10001","instanceid":"compute-abc","apikey":"xxx"}'
  ```

## Token 生成规则
- 排除 token 参数后，按参数名字典序拼接为 `key=value&...`，尾部追加 `key=SECRET_KEY`，对拼接字符串计算 MD5（小写 hex）。
- 可用仓库中的 [`MD5.py`](MD5.py) 生成 token 并查看用于签名的 base_string。

## 用户生命周期（核心）
- 状态集合：ACTIVE, EXPIRED, RELEASED（删除时移除记录）
- 典型流程：
  - 创建 (createInstance) -> ACTIVE（写入 StorageService）
  - 续费 (renewInstance) -> ACTIVE（更新 expiredOn）
  - 过期 (expiredInstance) -> EXPIRED（生成无效状态文件）
  - 释放 (releaseInstance) -> 删除记录（删除状态文件并触发延迟 reload）
- 有效性判断（UserInfo.isValid()）：status==ACTIVE 且未过期（expiredOn 后）
- 存储：以 orderBizId 为主键，维持 aliUid -> orderBizId 索引（参见 [`StorageService`](src/main/java/com/isv/spi/services/StorageService.java)）

## 状态文件与外部联动
- 默认路径：`/etc/nginx/conf.d/instance_status/<computeNestInstanceId>.conf`
- 文件内容示例：`<instanceId> true;` 或 `<instanceId> false;`
- 为避免频繁 reload，变更会使用本地缓存判断是否真的变化并调度延迟（示例中 5 秒），然后调用外部 reload 脚本（示例：`/usr/local/bin/nginx-reload-signal`）。

## 运行/安全建议
- SECRET_KEY 不要硬编码到源码（当前样例为演示），建议从环境变量或配置中心读取。
- STORAGE_DIR（当前为 `opt/isv-spi-data/`）应映射到持久卷并保证写权限。
- 状态文件写入与 reload 需要适当权限或改为更可靠的同步机制（MQ、API 回调等）。

