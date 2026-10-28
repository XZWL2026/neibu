export const adminUsers = [
  { id: "USR-1388", name: "Nova", phone: "13800138000", account: "个人", points: "1,280.00", rechargeAmount: "2,000.00", consumptionAmount: "720.00", availableAmount: "1,280.00", status: "启用", last: "今天 15:22" },
  { id: "USR-2046", name: "星河工作室", phone: "18612348614", account: "企业", points: "8,420.50", rechargeAmount: "12,000.00", consumptionAmount: "3,579.50", availableAmount: "8,420.50", status: "启用", last: "今天 14:08" },
  { id: "USR-0872", name: "墨墨墨Ink", phone: "15900003021", account: "个人", points: "318.00", rechargeAmount: "500.00", consumptionAmount: "182.00", availableAmount: "318.00", status: "停用", last: "8月27日" }
] as const;

export const adminKeys = [
  { id: "KEY-1388-M", owner: "Nova", kind: "媒体 Key", balance: "1,280.00", secret: "sk-demo-nova-1388", status: "启用", last: "今天 15:21" },
  { id: "KEY-2046-A", owner: "星河工作室", kind: "API Key", balance: "8,420.50", secret: "sk-demo-xinghe-2046", status: "启用", last: "今天 14:06" },
  { id: "KEY-0872-M", owner: "墨墨墨Ink", kind: "媒体 Key", balance: "318.00", secret: "sk-demo-momo-0872", status: "停用", last: "8月27日" }
] as const;

export const adminPayments = [
  { id: "PAY-0829-331", user: "Nova", amount: "¥ 500.00", kind: "积分充值", status: "已支付", channel: "回调确认", created: "今天 12:08" },
  { id: "PAY-0829-308", user: "星河工作室", amount: "¥ 2,000.00", kind: "企业充值", status: "待支付", channel: "轮询中", created: "今天 11:42" },
  { id: "PAY-0828-972", user: "墨墨墨Ink", amount: "¥ 300.00", kind: "积分充值", status: "退款处理中", channel: "人工关注", created: "昨天 18:11" }
] as const;

export const adminManualRecharges = [
  { id: "MAN-0829-01", user: "Nova", amount: "¥ 500.00", type: "个人充值", status: "待审核", submitted: "今天 12:08", reviewer: "待分配", remark: "用户转账凭证已上传" },
  { id: "MAN-0828-07", user: "星河工作室", amount: "¥ 2,000.00", type: "企业充值", status: "已通过", submitted: "昨天 09:42", reviewer: "finance.ops", remark: "" }
] as const;

export const adminOnlineRecharges = [
  { id: "PAY-0829-331", user: "Nova", amount: "¥ 500.00", channel: "收钱吧", status: "已支付", paidAt: "今天 12:08", refund: "无" },
  { id: "PAY-0829-308", user: "星河工作室", amount: "¥ 2,000.00", channel: "收钱吧", status: "待支付", paidAt: "—", refund: "无" },
  { id: "PAY-0828-972", user: "墨墨墨Ink", amount: "¥ 300.00", channel: "收钱吧", status: "退款处理中", paidAt: "昨天 18:11", refund: "人工关注" }
] as const;

export const adminMedia = [
  { id: "VID-0829-063", kind: "视频", actor: "USR-1388", owner: "Nova", model: "Seedance 2.5", status: "生成中", detail: "等待上游结果", created: "今天 15:22" },
  { id: "IMG-0829-104", kind: "图片", actor: "USR-2046", owner: "星河工作室", model: "GPT Image 2", status: "已完成", detail: "4 / 4 输出", created: "今天 15:10" },
  { id: "VID-0828-018", kind: "视频", actor: "USR-0872", owner: "墨墨墨Ink", model: "Kling 2.1", status: "失败", detail: "本地排障示例", created: "昨天 21:16" }
] as const;

export const adminMediaDetails = {
  "VID-0829-063": { prompt: "一只橘猫在城市天台迎着晚风奔跑", volcanoTaskId: "volc-task-0829-063", nativeResolution: "1280 × 720", volcanoDuration: "42s", volcanoTokens: "12,480", tosVideo: "tos://bucket-xzwl/video-0829-063.mp4", tencentTaskId: "tx-task-0829-063", templateId: "tpl-enhance-720p", templateResolution: "1280 × 720", tencentDuration: "18s", cosVideo: "cos://bucket-xzwl/video-0829-063.mp4", enhancementVideo: "cos://bucket-xzwl/video-0829-063-enhanced.mp4" }
} as const;

export const adminConsumption = [
  { id: "CON-0829-01", user: "Nova", key: "KEY-1388-M", usageType: "视频", changeType: "扣减", amount: "¥ 486.40", pointsChange: "-486.40", beforeBalance: "1,766.40", afterBalance: "1,280.00", pointsBefore: "1,766.40", pointsAfter: "1,280.00", model: "Seedance 2.5", resolution: "720p", duration: "8s", taskStatus: "生成中", taskRef: "VID-0829-063", created: "今天 15:22" },
  { id: "CON-0829-02", user: "星河工作室", key: "KEY-2046-A", usageType: "图片", changeType: "扣减", amount: "¥ 96.00", pointsChange: "-96.00", beforeBalance: "8,516.50", afterBalance: "8,420.50", pointsBefore: "8,516.50", pointsAfter: "8,420.50", model: "GPT Image 2", resolution: "2K · 4张", duration: "—", taskStatus: "已完成", taskRef: undefined, created: "今天 15:10" }
] as const;

export const adminPricing = [
  { account: "个人", model: "GPT Image 2", resolution: "2K", unit: "每张", price: "12.0000" },
  { account: "企业", model: "GPT Image 2", resolution: "2K", unit: "每张", price: "9.6000" },
  { account: "个人", model: "Seedance 2.5", resolution: "720p", unit: "每秒", price: "9.0000" },
  { account: "企业", model: "Seedance 2.5", resolution: "720p", unit: "每秒", price: "7.2000" }
] as const;

export const pricingGroups = [
  { id: "PG-INTERNAL", name: "内部标准价目组", type: "内部", rechargeTypes: "个人充值 · 企业充值", versions: "图片 / 视频 / 增强 12 项" },
  { id: "PG-EXTERNAL", name: "外部 API 价目组", type: "外部", rechargeTypes: "外部 API 充值", versions: "图片 / 视频 8 项" }
] as const;

export const administrators = [
  { username: "primary.admin", role: "ADMIN", status: "启用", last: "今天 14:58" },
  { username: "content.ops", role: "NORMAL_ADMIN", status: "启用", last: "今天 13:41" },
  { username: "finance.ops", role: "NORMAL_ADMIN", status: "停用", last: "8月26日" }
] as const;

export const adminAgents = [
  { id: "AGT-2046", name: "星河推广中心", phone: "18612348614", status: "启用", levelOne: "2", levelTwo: "2", recharge: "¥ 16,000.00", consumption: "¥ 12,846.40", commission: "¥ 2,272.60" },
  { id: "AGT-1388", name: "Nova 代理", phone: "13800138000", status: "启用", levelOne: "1", levelTwo: "0", recharge: "¥ 5,200.00", consumption: "¥ 3,880.00", commission: "¥ 612.00" }
] as const;

export const loginIdentities = [
  { username: "primary.admin", role: "ADMIN", status: "启用" },
  { username: "content.ops", role: "NORMAL_ADMIN", status: "启用" },
  { username: "promoter.demo", role: "PROMOTER", status: "启用" },
  { username: "api.demo", role: "API_USER", status: "启用" }
] as const;

export const promoterProfile = {
  name: "星河推广中心",
  code: "PRM-2046",
  levelOneCount: "2",
  levelTwoCount: "2",
  totalRecharge: "¥ 16,000.00",
  frozenCommission: "¥ 1,286.40",
  totalConsumption: "¥ 12,846.40",
  releasedCommission: "¥ 986.20",
  totalCommission: "¥ 2,272.60",
  withdrawnCommission: "¥ 300.00",
  pendingCommission: "¥ 1,286.40"
} as const;

export const promoterUsers = [
  { id: "U-8A****21", name: "林晓", level: "一级", account: "个人用户", phone: "186****0821", registeredAt: "2026-08-18 10:26", status: "启用", recharge: "¥ 6,000.00", consumption: "¥ 5,280.00" },
  { id: "U-6D****46", name: "光合设计", level: "一级", account: "企业用户", phone: "189****6646", registeredAt: "2026-08-22 14:08", status: "启用", recharge: "¥ 5,200.00", consumption: "¥ 4,320.40" },
  { id: "U-3C****09", name: "周予安", level: "二级", account: "个人用户", phone: "137****3209", registeredAt: "2026-08-24 09:42", status: "启用", recharge: "¥ 2,800.00", consumption: "¥ 1,946.00" },
  { id: "U-7F****88", name: "陈默", level: "二级", account: "个人用户", phone: "158****7788", registeredAt: "2026-08-28 17:35", status: "停用", recharge: "¥ 2,000.00", consumption: "¥ 1,300.00" }
] as const;

export const promoterConsumption = [
  { id: "CON-0829-01", nickname: "林晓", phone: "186****0821", changeType: "扣减", usageType: "图片", amount: "¥ 320.00", beforeBalance: "¥ 2,440.00", afterBalance: "¥ 2,120.00", releasedCommission: "¥ 32.00", created: "2026-08-29 15:08" },
  { id: "CON-0829-02", nickname: "光合设计", phone: "189****6646", changeType: "扣减", usageType: "视频", amount: "¥ 486.40", beforeBalance: "¥ 3,126.80", afterBalance: "¥ 2,640.40", releasedCommission: "¥ 48.64", created: "2026-08-29 14:32" },
  { id: "CON-0828-03", nickname: "周予安", phone: "137****3209", changeType: "扣减", usageType: "图片", amount: "¥ 188.00", beforeBalance: "¥ 1,742.00", afterBalance: "¥ 1,554.00", releasedCommission: "¥ 18.80", created: "2026-08-28 19:20" }
] as const;

export const promoterRecharges = [
  { id: "REC-0829-01", nickname: "林晓", phone: "186****0821", amount: "¥ 2,000.00", frozenCommission: "¥ 200.00", created: "2026-08-29 11:16" },
  { id: "REC-0826-02", nickname: "光合设计", phone: "189****6646", amount: "¥ 5,200.00", frozenCommission: "¥ 520.00", created: "2026-08-26 09:40" },
  { id: "REC-0725-03", nickname: "周予安", phone: "137****3209", amount: "¥ 2,800.00", frozenCommission: "¥ 280.00", created: "2026-07-25 18:06" }
] as const;

export const promoterSettlements = [
  { id: "WD-20260829", period: "2026-08", commission: "¥ 300.00", status: "已到账", appliedAt: "2026-08-29 16:20" },
  { id: "WD-20260730", period: "2026-07", commission: "¥ 200.00", status: "已拒绝", appliedAt: "2026-07-30 10:05" }
] as const;

export const apiProfile = {
  account: "api.demo",
  site: "星河 API 控制台",
  balance: "¥ 3,680.00",
  keyLabel: "sk-live-••••••••••••4Q7N",
  callCount: "18,426",
  successRate: "99.2%",
  totalRecharge: "¥ 12,000.00",
  totalConsumption: "¥ 8,420.00",
  todayConsumption: "¥ 1,260.00"
} as const;

export const apiConsumption = [
  { id: "API-0829-01", model: "image-2", calls: "1,280", amount: "¥ 640.00", beforeBalance: "¥ 4,320.00", afterBalance: "¥ 3,680.00", pointsBefore: "4,320", pointsAfter: "3,680", created: "今天 15:16" },
  { id: "API-0829-02", model: "video-2.5", calls: "420", amount: "¥ 1,260.00", beforeBalance: "¥ 4,940.00", afterBalance: "¥ 3,680.00", pointsBefore: "4,940", pointsAfter: "3,680", created: "今天 14:48" },
  { id: "API-0828-03", model: "image-2", calls: "860", amount: "¥ 430.00", beforeBalance: "¥ 5,370.00", afterBalance: "¥ 4,940.00", pointsBefore: "5,370", pointsAfter: "4,940", created: "昨天 20:04" }
] as const;

export const apiBilling = [
  { id: "BILL-0829", kind: "API 调用扣费", amount: "¥ 640.00", status: "已完成", created: "今天 15:16" },
  { id: "BILL-0828", kind: "余额充值", amount: "¥ 2,000.00", status: "已完成", created: "昨天 10:12" }
] as const;
