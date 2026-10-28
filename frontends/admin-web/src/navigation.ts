export const adminNavigation = [
  { label: "运营概览", to: "/admin/overview", icon: "⌂" },
  { label: "用户管理", to: "/admin/users", icon: "♙" },
  { label: "代理管理", to: "/admin/agents", icon: "◎" },
  { label: "API 管理", to: "/admin/api-users", icon: "⌘" },
  { label: "消费明细", to: "/admin/consumption", icon: "≋" },
  { label: "生成日志", to: "/admin/provider-logs", icon: "▤" },
  { label: "支付订单", to: "/admin/payments", icon: "▤" },
  { label: "资金审核", to: "/admin/recharges/review", icon: "✓" },
  { label: "推荐关系管理", to: "/admin/promotions/attributions", icon: "⌁" },
  { label: "代理收益", to: "/admin/promotions/withdrawals", icon: "¥" },
  { label: "推广规则", to: "/admin/promotions/benefit-settings", icon: "%" },
  { label: "模型基础设施", to: "/admin/providers", icon: "◇" },
  { label: "模型定价", to: "/admin/pricing", icon: "¥" },
  { label: "管理员", to: "/admin/admins", icon: "◇" }
] as const;

export const promoterNavigation = [
  { label: "推广概览", to: "/promoter/overview", icon: "⌂" },
  { label: "推广用户", to: "/promoter/users", icon: "♙" },
  { label: "消费明细", to: "/promoter/consumption", icon: "▤" },
  { label: "佣金", to: "/promoter/settlements", icon: "¥" },
  { label: "账户设置", to: "/promoter/account", icon: "◇" }
] as const;

const apiRoot = "/" + "api";
export const apiNavigation = [
  { label: "API 仪表盘", to: `${apiRoot}/dashboard`, icon: "⌂" },
  { label: "充值记录", to: `${apiRoot}/billing`, icon: "¥" },
  { label: "公户转账", to: `${apiRoot}/transfer`, icon: "◇" },
  { label: "API 文档", to: `${apiRoot}/docs`, icon: "▻" },
  { label: "账户设置", to: `${apiRoot}/account`, icon: "◇" }
] as const;
