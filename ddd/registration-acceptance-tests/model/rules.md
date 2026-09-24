# 业务规则表：报名上下文

| 编号 | 规则 | 来源 | 产生的事件或拒绝原因 | 状态 |
|---|---|---|---|---|
| R1 | 名额未满时报名直接确认 | 时间线 2 | RegistrationConfirmed | 已确认 |
| R2 | 名额已满时进入候补，按报名先后得到顺位 | 时间线 3 | CandidateWaitlisted | 已确认 |
| R3 | 同一参会人在同一场次只能占一个位置（已确认或候补） | 热点 H1 | 拒绝：DUPLICATE | 已确认 |
| R4 | 开场前 24 小时内不能取消 | 热点 H2 | 拒绝：TOO_LATE_TO_CANCEL | 已确认 |
| R5 | 已确认者取消后，候补第一位直接递补 | 时间线 4、6 | RegistrationCancelled、CandidatePromoted | 待验证（H3） |
| R6 | 候补者取消只离开队列，不触发递补 | 时间线 5 | WaitlistLeft | 已确认 |
| R7 | 报名截止后不接受新报名 | 时间线 7 | 拒绝：SESSION_CLOSED | 已确认 |
| R8 | 收费场次有人拿到名额（确认或递补）时生成应收 | 时间线 8 | 策略输出 CreateCharge | 已确认 |
