# apigw · 微服务网关

Spring Cloud Gateway（WebFlux 响应式）+ Redis 动态路由配置。JDK 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.1。

同一个应用里跑两件事：

1. **转发链路**：请求进来 → 按配置的匹配条件找到路由 → 按配置的转发动作处理请求头 → 打到上游 → 响应回来处理响应头 → 交还调用方。配置在 Redis，改完经事件即时生效，不用重启（另有定时兜底刷新保证多实例最终一致）。
2. **管理接口**：`/api/gateway/routes`，维护路由及其匹配条件、转发动作。

## 起环境

```bash
docker compose up -d                # 起 Redis（路由配置）+ MySQL（访问流水，首启自动建 gw_access_log）
mvn spring-boot:run                 # 网关，8080
bash tools/start-echo-upstream.sh   # 本地回显上游，8091（另开一个终端）
```

数据源走环境变量（默认指向本地 compose 起的 MySQL）：`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`；
库维护、临时关停流水可设 `ACCESSLOG_ENABLED=false`（此时转发完全不碰库，仍需数据源可用，
若要连数据源自动配置一并去掉，用 `spring.autoconfigure.exclude` 排除 DataSource/JDBC 自动配置）。

## 管理接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/gateway/routes` | 新建路由（连同条件与动作一起落库） |
| PUT | `/api/gateway/routes/{routeNo}` | 修改路由（整树替换，必须带 `version`） |
| GET | `/api/gateway/routes/{routeNo}` | 路由详情（含全部子项，按顺序号排好） |
| GET | `/api/gateway/routes?pageNum=&pageSize=&keyword=` | 分页列表（每条带条件/动作计数） |
| DELETE | `/api/gateway/routes/{routeNo}?expectVersion=` | 删除路由（整树清掉） |
| GET | `/api/gateway/access-logs?startTime=&endTime=&routeNo=&statusCode=&requestNo=&pageNum=&pageSize=` | 访问流水翻账（条件可组合、分页） |

所有接口返回统一结构 `{ code, msg, data }`：

- `code=0` 成功；
- 通用业务失败 `code=1`；
- 路由不存在 `code=404`（删除不存在的路由不算成功）；
- 并发冲突 `code=409`（你手上的版本旧了）。

### 请求体形状

```json
{
  "routeNo": "order-route",
  "name": "订单服务路由",
  "upstream": "http://order-svc:8080",
  "enabled": 1,
  "remark": "给前端下单用",
  "version": 0,
  "conditions": [
    { "type": "PATH_PREFIX", "value": "/order/", "sortNo": 1 },
    { "type": "METHOD", "value": "GET", "sortNo": 2 },
    { "type": "HEADER", "name": "X-Caller", "value": "web", "sortNo": 3 },
    { "type": "QUERY", "name": "from", "value": "cart", "sortNo": 4 }
  ],
  "actions": [
    { "type": "REQ_ADD_HEADER", "name": "X-Gw", "value": "1", "sortNo": 1 },
    { "type": "REQ_REMOVE_HEADER", "name": "X-Internal", "sortNo": 2 },
    { "type": "RESP_ADD_HEADER", "name": "X-Trace", "value": "t-1", "sortNo": 3 },
    { "type": "RESP_REMOVE_HEADER", "name": "X-Debug", "sortNo": 4 }
  ]
}
```

- 路由编号：业务唯一，建后**不可改**（PUT 的 body 里编号与路径不一致会被拦）；停用的路由也占号，只有删除才释放编号。
- 匹配条件只认 `PATH_PREFIX` / `METHOD` / `HEADER` / `QUERY`；路径、方法两类不用填 `name`。
- 转发动作只认 `REQ_ADD_HEADER` / `REQ_REMOVE_HEADER` / `RESP_ADD_HEADER` / `RESP_REMOVE_HEADER`；删头不用填 `value`。
- 顺序号每组各自从 1 开始，必须**连续、不重**。撞号会报「匹配条件第 a 条与第 b 条的顺序号撞了，都是 n」；跳号会报缺了第几。
- 上游地址必须是合法的 `http://` / `https://` URL（协议、主机、端口都像样），空串和乱码不收。
- 修改时把条件/动作整批重排提交即可，服务端按新一批顺序号整树替换。

### 分页返回

```json
{
  "code": 0,
  "data": {
    "content": [ { "routeNo": "...", "conditionCount": 2, "actionCount": 4, "...": "..." } ],
    "total": 37,
    "pageNum": 2,
    "pageSize": 20,
    "totalPages": 2
  }
}
```

- `pageNum` 从 1 开始；`pageSize` 上限 200（传 99999 也只按 200 算），防止一次拖全量。
- `keyword` 在编号和名称上做忽略大小写的模糊匹配。
- 列表每行直接带 `conditionCount` / `actionCount`，前端不用逐条再查。

## 转发链路怎么走

转发由一个高优先级 WebFilter（`com.apigw.proxy.GatewayProxyWebFilter`）总编排，管理接口 `/api/**` 直接放行，其余请求按下面的路走：

```
请求进来
 → 生成 traceId，写访问日志第 1 段（phase=IN）
 → 在内存路由快照上匹配唯一路由（匹配不到 → 404 NO_ROUTE）
 → 清洗逐跳/报文绑定头 + X-Forwarded-* + 按顺序号执行请求类动作
 → 发到上游（请求体流式透传，不缓冲）
 → 上游响应回来：状态码原样，清洗逐跳/content-length，按顺序号执行响应类动作
 → 响应体流式写回调用方，写访问日志第 2 段（phase=OUT，含状态码/耗时/命中路由/上游）
```

### 匹配细节

- 同一条路由上的条件是 **AND**，任何一条不满足就不命中。
- **路径前缀边界**（最容易踩的点）：
  - 规则 `/order/`（带尾斜杠）= 只认子树：命中 `/order/abc`、`/order/`，但**不**命中 `/order` 本身；
  - 规则 `/order`（不带尾斜杠）= 精确路径 + 子树：命中 `/order`、`/order/abc`，但**不**命中 `/other`、`/ordering`、`/order-x`、`/orders/1`（下一个字符必须是 `/`）。
  - 路径大小写敏感（常规 URL 语义）。
- 方法名大小写不敏感（`GET` 与 `get` 等价）；HEADER 头名不敏感、头值大小写敏感且精确相等；QUERY 名值都大小写敏感、值精确相等。
- **多条路由同时命中时的定序**（确定、稳定，同样的请求永远走同一条）：
  1. 路径前缀更长的优先（更具体的路径赢；没有路径条件的按 0 长度排最后）；
  2. 仍并列时条件总数更多的赢（约束更具体）；
  3. 还并列按路由编号字典序（routeNo 只含字母数字 `. _ -`）。
- 停用的、以及一条条件都没有的路由不参与匹配。

### 动作语义

- 补头是**覆盖**语义：调用方自带同名头会被配置值顶掉（HTTP 头名大小写不敏感）；删头就是删掉，上游/调用方都收不到。
- 同方向动作严格按 `sortNo` 顺序执行（先删后补与先补后删结果相反）。
- 方向严格隔离：`REQ_*` 只作用于发往上游的请求头，`RESP_*` 只作用于回给调用方的响应头。
- 真正落到报文上（不是记日志）：上游收到的请求头、调用方收到的响应头都按动作改写过。
- 上游返回的 `content-length`、`transfer-encoding` 与「上游↔网关」这段具体报文绑定，**不原样照抄**：网关在提交前剔除，由 Netty 按网关实际写出的字节重算/走分块，避免长度与内容对不上。

### 错误答复（四类，状态码 + `X-Gateway-Error` 头 + JSON 体 `{error,message,traceId}`）

| 场景 | HTTP | X-Gateway-Error | 含义 |
| --- | --- | --- | --- |
| 一条路由都没匹配上 | 404 | `NO_ROUTE` | 网关没找到路，前端据此与「后端业务报错」区分 |
| 上游连不上（拒接/不可达/TLS 失败） | 502 | `UPSTREAM_UNAVAILABLE` | 上游没在或地址错 |
| 上游半天不吭声（连接/读取超时） | 504 | `UPSTREAM_TIMEOUT` | 上游在但太慢/卡死，调用方不用干等 |
| 路由配置此刻读不出来（Redis 故障且无旧快照） | 503 | `CONFIG_UNAVAILABLE` | 网关侧配置故障 |

- 上游自己的 4xx/5xx 是业务结果，状态码与响应体**原样透传**，网关不改写。
- 错误体只有网关的固定文案 + traceId，绝不外抛内部堆栈或上游原始错误页。
- 超时参数可调：`apigw.proxy.connect-timeout`（默认 3s）、`apigw.proxy.response-timeout`（默认 10s）。

### 热刷新（不重启生效）

- 管理接口增/删/改成功后发布进程内 `RoutesChangedEvent`，本实例的路由快照立即重载——新配路由**马上能走通**。
- 另有 10s 定时兜底刷新（`apigw.proxy.route-refresh-interval`），多实例部署时别的实例改了配置，靠它在一个周期内收敛。
- Redis 一时抖动：已有快照时沿用上一份继续转发，只在从没加载成功过时回 503。

### 访问审计（查账）

两层，互不替代：

1. **文本审计日志**（专用 logger `access-log`），同一次请求记两段，靠同一个 `traceId` 拼回，不会串到别人：
   - `phase=IN  traceId=... method=... path=... route=- upstream=-`
   - `phase=OUT traceId=... method=... path=... route=... upstream=... status=... outcome=... elapsed=...ms`
2. **访问流水落库**（`gw_access_log`，一笔请求一行），供事后按条件翻账，见下节。

- 命中路由、上游地址、耗时、最终状态码、结果（FORWARDED/NO_ROUTE/UPSTREAM_*/CONFIG_UNAVAILABLE）都在 OUT 段；没匹配上的请求也记。
- 文本日志走独立守护线程 + 有界队列，反应式链路里只做一次微秒级入队；队列满宁可丢日志并计数告警，也不反压转发。
- 调用方在每个响应（含错误）上都能拿到 `X-Gateway-Trace-Id`，直接和日志/流水对账。

## 访问流水（落库 + 翻账）

库表 `gw_access_log`（线上已建好，权威 DDL 见 `src/main/resources/db/access_log.mysql.sql`，
本地 `docker compose up` 起的 MySQL 首次启动自动建）。一行九列：

| 列 | 口径 |
| --- | --- |
| `request_no` | 请求编号。调用方带了合法 `X-Request-Id` 就沿用（跨服务同号串联），没带/非法（非 `[A-Za-z0-9._-]{1,64}`）网关生成 32 位十六进制 UUID；响应经 `X-Gateway-Trace-Id` 带回 |
| `route_no` | 命中的路由编号；没匹配上/配置不可用为空 |
| `app_no` | 调进来的应用编号。鉴权上线前取 `X-App-No` 头（可配置），认不出来为空；鉴权落地后改从鉴权上下文取，切换点只在过滤器一处 |
| `client_ip` | 客户端来源地址。**与鉴权、限流共用 `ClientIpResolver` 一个口径**：`X-Forwarded-For` 最左合法 IP → `X-Real-IP` → TCP 对端地址；跳过空/`unknown`/非 IP 形状的值，绝不 DNS 反查。安全前提：网关只部署在会清洗 XFF 的受控 LB 之后 |
| `method` / `path` | 请求方法 / 请求路径（不含查询串），超长按列宽截断 |
| `status_code` | 返回给调用方的真实 HTTP 码（上游 4xx/5xx 原样透传，也照记）；**拿不到状态码（上游连不上/超时、响应未提交、连接中断）统一记 `0`**——失败请求一样留痕，用 `statusCode=0` 专查这类事故 |
| `elapsed_ms` | 请求进来到响应结束的毫秒数 |
| `occur_time` | 发生时间（请求进来的时刻，毫秒精度），不是落库时刻 |

### 写链路怎么不拖慢转发

```
请求线程（反应式 event loop）：只做 [非阻塞 offer 进有界队列]，微秒级，绝不等库
后台单守护线程 access-log-db-writer：凑批 → 一条多行 INSERT 落库 → 逐条兜底
```

- **攒批边界**（`apigw.accesslog.*` 可调）：
  - 条数：攒够 `batch-size`（默认 200，硬上限 1000）立刻落；
  - 时间：第一条进来后最多等 `linger`（默认 1s）必落一次，低峰不压数据；
  - 退出：正常停机先封口队列，worker 立即停止等待把队列里没落完的逐批冲库，
    宽限 `shutdown-wait`（默认 5s），到点放弃——不丢数据也不无限拖住停机。
- **背压口径**：入队队列有界（`queue-capacity` 默认 10000）。满了直接丢这一条并计数告警，
  不阻塞、不抛异常——丢流水是可接受的降级，拖垮转发不是。
- **不写半条记录**：每批是**一条多行 `INSERT`（单语句 = InnoDB 单隐式事务，整批全成全败）**，
  网关主路径没有事务上下文也不依赖它；单条列在记录构造时就校验/截断，不会出现半行。
  整批失败（多为库抖动）先转**逐条单语句**兜底，把好的救回来；还写不进的只计数 + error 日志，
  异常在后台线程内全部收口，永远不冒到转发链路上。
- IN/OUT 两段信息怎么不串：一行流水在请求结束（`doFinally`）时由**本次请求栈上的局部量**
  （编号/来源/应用/方法/路径/发生时间）和**只挂在本次 exchange 上的结果引用**
  （路由/状态码/耗时）拼成一个不可变 `AccessLogRecord` 再入队，物理上不可能把 A 的路径配到 B 的状态码。
- 总开关 `apigw.accesslog.enabled=false`：转发链路零碰库（装配空写入器），库维护时用。

### 翻账接口

`GET /api/gateway/access-logs`，条件可任意组合：

| 参数 | 说明 |
| --- | --- |
| `startTime` / `endTime` | 发生时间段，ISO-8601 `yyyy-MM-ddTHH:mm:ss`；start 必填、end 默认当前、区间为 **[start, end)**；跨度封顶 31 天 |
| `routeNo` | 路由编号精确匹配 |
| `statusCode` | 状态码精确匹配（`0` 专查拿不到码的失败） |
| `requestNo` | 请求编号/调用方追踪号精确对单 |
| `pageNum` / `pageSize` | 页码从 1 开始；每页默认 20、**上限 200**（传超按 200 算） |

返回仍是统一 Result 包裹的 PageResult：`content / total / pageNum / pageSize / totalPages`。
`total` 与当页用**同一套 WHERE**（一条 COUNT、一条分页），数字严格对得上；
排序固定 `occur_time DESC, id DESC`（同毫秒内 id 兜底稳定次序，翻页不重不漏）。
JDBC 是阻塞调用，查询统一切到 `boundedElastic` 阻塞工作池，不占反应式事件循环。

```bash
curl 'http://localhost:8080/api/gateway/access-logs?startTime=2026-09-26T00:00:00&endTime=2026-09-27T00:00:00&routeNo=order&statusCode=502&pageNum=1&pageSize=20'
```

### 索引（为什么这么建）

```sql
KEY idx_occur_time_id (occur_time, id)            -- 无附加条件的时间段翻账：范围扫描 + 倒序免 filesort
KEY idx_route_occur  (route_no, occur_time)       -- 时间 + 路由
KEY idx_status_occur (status_code, occur_time)    -- 时间 + 状态码（含 status=0 事故筛查）
KEY idx_request_no   (request_no)                 -- 按追踪号反查整条跨服务链路
```

- 时间翻账是绝对主场景（startTime 必填），时间列在每条索引里都参与，最左前缀直接收窄；
- 等值列在前、范围/排序列在后，是组合筛选的标准建法；`(occur_time, id)` 让倒序翻页走索引序；
- `request_no` 只建普通索引**不建唯一**：调用方可能重号/重试，网关不能因约束冲突把流水写崩；
- 深分页防护：时间窗 31 天封顶 + pageSize 200 封顶，避免一条无界 `OFFSET` 扫垮库；再大的量请走离线数仓。

## 配置怎么存

```
Redis key   apigw:routes          类型 Hash
            field = routeNo
            value = 该路由连同全部条件、动作的一整份 JSON
```

**为什么「一条路由 + 它的全部子项」塞在一个 field 里**：保存是一次 `HSET`、删除是一次 `HDEL`，Redis 单命令原子，所以「全落库或全不落」不需要手工回滚，也不可能读出主记录在、子项不在的残缺路由；删除时一次 `HDEL` 整树清掉，没有无主子记录可留。

## 并发怎么控

两层，都在 Redis 上：

1. **建路由占号用 `HSETNX`**：「查编号是否存在」和「写入」合成一个原子动作。两个人同时建同一个编号，只有一个成功，另一个收「编号已被占用（停用的路由也占号）」。
2. **改/删同一条用「短租约锁 + version 乐观锁」**：
   - 锁 key `apigw:lock:route:{routeNo}`，`SET NX` 带 5 秒 TTL，值是唯一 token，释放走 Lua 比对 token 后删除（不会误删别人的锁）；它把「读当前版本 → 写回」串成临界区。
   - 每条路由带 `version`：**修改必须显式带上读取时拿到的版本**（首版传 0），服务端比对一致才写、然后 version+1；不一致返回 `code=409`「你这份配置已经旧了（当前版本 n，你手上是 m），请重新拉取后再提交」。删除带 `expectVersion` 时有同样保护。
   - 不允许不带版本就改，否则等于把乐观锁绕过去、静默覆盖。

## 测试

```bash
docker compose up -d     # 提供真实 Redis
mvn test
```

- `GatewayRouteTest`：聚合不变量（编号不可改、上游地址、顺序号撞/跳并报位置、类型白名单、必填项），无需 Redis。
- `GatewayRouteControllerWebTest`：HTTP 切片（真实 Controller + AppService + 聚合，mock 掉 Redis），覆盖统一返回、报错文案、分页数字与子项计数。
- `RouteStoreTest` / `GatewayRouteControllerIT`：连真实 Redis，覆盖 HSETNX 原子占号、并发建同号、乐观锁 409、整树替换与级联删除。本机探测不到 `localhost:6379` 时自动跳过（可用 `-Dredis.host/-Dredis.port` 指向别处）。
- `PathPrefixMatcherTest` / `RouteMatcherTest`：路径前缀边界（尾斜杠/段边界/大小写）、四类条件 AND、多命中稳定定序。
- `HeaderActionApplierTest`：补头覆盖同名值、删头彻底、顺序号先后、请求/响应方向隔离。
- `UpstreamFailureKindTest`：连不上=502、超时=504、能穿透异常包装层、异常链成环不挂死。
- `RouteCatalogTest`：快照缓存、变更事件即时生效、Redis 故障沿用旧快照、并发冷加载不打雷群。
- `GatewayProxyFilterTest`：真实 Netty 服务端 + 真实 WebClient 上游 + JDK HTTP 上游的端到端（无 Redis），覆盖方法/路径/查询/请求体转发、请求与响应头增删改、404/502/504 三态、报文绑定头不照抄、热刷新、traceId。
- `AccessLogRecorderTest`：进/出两段 traceId 串联、不串请求、异步不阻塞。
- `ClientIpResolverTest` / `RequestNoResolverTest`：来源地址取值顺序（XFF 最左合法→X-Real-IP→TCP 对端）、追踪号沿用/生成/非法值口径。
- `AccessLogBatchWriterTest`：攒批条数边界、linger 时间边界、退出冲库不丢、队列满非阻塞丢弃、整批失败逐条兜底、sink 持续故障 worker 不死不外冒。
- `JdbcAccessLogRepositoryTest`（H2 MySQL 模式）：多行整批落库、时间/路由/状态码组合筛选、分页四元组与排序、`status=0` 可筛、时间窗与 pageSize 封顶。
- `AccessLogControllerTest`：真实容器 + H2 的翻账接口端到端，覆盖组合条件、分页数字、非法入参按业务失败返回。
- `GatewayProxyIT`：真实容器 + 真实 Redis + 真实上游，建完路由立刻能转发、删完立刻失效；探不到 Redis 时自动跳过。

## 已知边界（留给后续题目）

- 管理接口未鉴权；接入鉴权与身份透传是后续 F2 的题。
- 多实例间的配置即时一致目前靠 10s 定时轮询兜底（本实例内是事件即时）；要做到跨实例秒级一致可接 Redis Pub/Sub。
- 动作目前只支持请求/响应头的补与删；路径改写、查询串改写、体改写等留给后续。
