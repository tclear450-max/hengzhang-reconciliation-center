# API 与数据接入

默认地址：`http://127.0.0.1:18180/api`。请求与响应为 UTF-8 JSON，CSV 文件也要求 UTF-8。

`id` 和以分计的 `BIGINT` 数值在 JSON 中以字符串返回。传入金额使用“元”的十进制字符串，最多 12 位整数和 2 位小数；不接受科学记数法、千位分隔符或静默四舍五入。

## 登录、Cookie 和 CSRF

所有读接口需要登录（`/session` 例外），写接口还需 CSRF 令牌。先读取 `/session`，保存响应 Cookie，然后使用响应的 `csrfToken` 作为 `X-XSRF-TOKEN` 请求头。

```powershell
$base = 'http://127.0.0.1:18180/api'
$bootstrap = Invoke-RestMethod "$base/session" -SessionVariable cookieJar
$headers = @{ 'X-XSRF-TOKEN' = $bootstrap.csrfToken }
$body = @{ username='admin'; password='demo123456' } | ConvertTo-Json
Invoke-RestMethod "$base/login" -Method Post -WebSession $cookieJar -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/projects" -WebSession $cookieJar
```

浏览器前端已处理完整会话流程。退出调用 `POST /logout`。核销复核需另一个会话登录 `reviewer`。

## 接口列表

| 方法 | 路径 | 权限 / 用途 |
| --- | --- | --- |
| GET | `/session` | 公开：会话状态、CSRF token |
| POST | `/login` | 公开但需 CSRF：JSON 账号密码 |
| POST | `/logout` | 已登录：退出 |
| GET | `/dashboard` | 总览、趋势、分布、最近任务 |
| GET / POST | `/channels` | 查询 / 操作员创建资金账户 |
| GET / POST | `/projects` | 查询 / 操作员创建项目 |
| PUT | `/projects/{id}` | 操作员保存配置；有历史后锁定核对规则 |
| GET | `/imports` | 最近 500 份账单批次 |
| POST | `/imports` | 操作员：multipart CSV 导入 |
| POST | `/imports/json` | 操作员：JSON 标准化流水导入 |
| GET | `/template.csv` | 下载只有表头的标准模板 |
| GET | `/source-records?channelId=&source=&date=` | 按账户、来源、日期查询最近 500 条流水 |
| GET / POST | `/runs` | 最近 500 个历史版本 / 操作员执行对账 |
| GET | `/runs/{id}` | 任务、规则快照、至多 10,000 个核对明细 |
| GET | `/differences?status=` | 当前有效任务的差异，至多 500 条 |
| GET | `/differences/{id}` | 差异与所有核销申请 |
| POST | `/differences/{id}/investigate` | 操作员开始排查 |
| POST | `/differences/{id}/resolve` | 操作员提交核销申请 |
| POST | `/differences/{id}/review` | 复核员通过 / 驳回 |
| GET | `/vouchers` | 最近 500 份凭证 |
| GET | `/audit` | 最近 500 条审计 |
| GET | `/export/differences.csv` | 导出最多 10,000 条有效差异，含 UTF-8 BOM |

错误格式：`{"message":"具体原因"}`。未登录为 `401`，权限或 CSRF 不满足为 `403`，格式错误为 `400`，业务规则/幂等冲突为 `409`，文件超限为 `413`。

## 标准 CSV

```csv
external_id,order_no,business_type,amount,status,trade_date
WX-20261015-001,HZ-001,PAYMENT,100.00,SUCCESS,2026-10-15
WX-20261015-002,HZ-001,REFUND,-20.00,SUCCESS,2026-10-15
WX-20261015-FEE,FEE-20261015,FEE,-0.60,SUCCESS,2026-10-15
```

| 字段 | 规则 |
| --- | --- |
| `external_id` | 来源系统流水号，同账户、同数据源下全局唯一；1–100 字符 |
| `order_no` | 关联业务订单号；1–100 字符；与业务类型共同构成交易匹配键 |
| `business_type` | `PAYMENT` / `REFUND` / `FEE` / `TRANSFER` |
| `amount` | 元，带符号；收款非负，退款/手续费非正，转账按资金方向正负 |
| `status` | `SUCCESS` / `PENDING` / `FAILED` |
| `trade_date` | `yyyy-MM-dd`，整批必须与请求的账单日期相同 |

六个表头必须全部存在且不能重复，顺序可调整，不接受额外列。支持 BOM、引号和逗号字段。仅含完整表头代表该数据源当日明确无交易，不等同于缺失账单。

multipart 参数：`channelId`、`source`、`businessDate` 和文件字段 `file`。`source` 为 `PLATFORM / CLEARING / SETTLEMENT`。单批至多 10,000 行、5 MB。

## JSON 接收

```json
{
  "channelId": 1,
  "source": "PLATFORM",
  "businessDate": "2026-10-15",
  "rows": [
    {
      "externalId": "PAY-20261015-001",
      "orderNo": "HZ-001",
      "businessType": "PAYMENT",
      "amount": "100.00",
      "status": "SUCCESS",
      "tradeDate": "2026-10-15"
    }
  ]
}
```

`rows: []` 同样可登记明确的空账单。JSON 接口与 CSV 共用校验、事务和幂等规则。示例中的账户 ID 需要替换为实际新建账户；已有演示数据的旧日期不可回填新流水。

响应：`{"id":"22","inserted":1,"skipped":0,"duplicate":false}`。规范化内容相同返回原批次；流水号相同内容相同则跳过，内容不一致会拒绝整个批次。

## 创建项目与执行

`POST /projects`：

```json
{
  "name": "会员订单交易核对",
  "kind": "TRANSACTION",
  "channelId": 1,
  "leftSource": "PLATFORM",
  "rightSource": "CLEARING",
  "matchMode": "ONE_TO_ONE",
  "tolerance": "0.00",
  "settlementDays": 0,
  "startDate": "2026-10-15",
  "enabled": true,
  "autoRun": false,
  "scheduleTime": "09:00"
}
```

`kind` 可选 `TRANSACTION / FUND / BALANCE`。交易的 `matchMode` 可选 `ONE_TO_ONE / GROUPED`。资金按费用项分组，余额固定比较 PLATFORM 与 SETTLEMENT；只有资金支持 `settlementDays`（0–30 自然日）。

`POST /runs`：

```json
{"projectId":1,"businessDate":"2026-10-15","rerun":false,"leftAdjustment":"0.00","rightAdjustment":"0.00"}
```

只在余额模型中允许非零调整。调整分别加到双方日末余额。首次需执行起始日，之后逐日执行；重复请求返回同一有效任务。只有最后日且未进入差错处理时可 `rerun:true`。

## 差错核销与复核

操作员 `POST /differences/1/resolve`：

```json
{"amount":"10.00","method":"CONFIRM_LOSS","note":"已核实差异为渠道扣款，按确认损失处理","evidence":"银行回单 WX-20261015-009"}
```

`method`：`MANUAL_CONFIRM` / `CONFIRM_LOSS` / `CONFIRM_INCOME` / `CORRECTED_EXTERNALLY`。金额需大于零且不超过剩余未核销金额；纯状态差异允许 0。提交后为 `PENDING_REVIEW`，此时尚未增加已核销金额。

复核员 `POST /differences/1/review`：

```json
{"approve":true,"note":"已核对原始回单，金额与处理依据一致"}
```

`approve:false` 驳回并回到排查状态；通过后登记核销凭证，部分核销回到 `OPEN`，足额核销为 `RESOLVED`。提交人不能自审；重复复核被拒绝。
