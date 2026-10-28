export const adminNavigation = [
  { label: '运营概览', to: '/admin/overview', icon: 'home', group: '工作台' },
  { label: '用户管理', to: '/admin/users', icon: 'users', group: '运营管理' },
  { label: '代理管理', to: '/admin/agents', icon: 'user', group: '运营管理' },
  { label: 'API 管理', to: '/admin/api-users', icon: 'globe', group: '运营管理' },
  { label: '消费明细', to: '/admin/consumption', icon: 'chart', group: '运营管理' },
  { label: '生成日志', to: '/admin/provider-logs', icon: 'document', group: '运营管理' },
  { label: '支付订单', to: '/admin/payments', icon: 'card', group: '资金管理' },
  { label: '资金审核', to: '/admin/recharges/review', icon: 'check', group: '资金管理' },
  { label: '线上充值', to: '/admin/recharges/online', icon: 'wallet', group: '资金管理' },
  { label: '公户转账', to: '/admin/recharges/corporate', icon: 'bank', group: '资金管理' },
  { label: '推荐关系管理', to: '/admin/promotions/attributions', icon: 'network', group: '推广管理' },
  { label: '代理收益', to: '/admin/promotions/withdrawals', icon: 'money', group: '推广管理' },
  { label: '推广规则', to: '/admin/promotions/benefit-settings', icon: 'settings', group: '推广管理' },
  { label: '推广收益对账', to: '/admin/promotions/reconciliation', icon: 'check', group: '推广管理' },
  { label: '推广收益审计', to: '/admin/promotions/benefit-audit', icon: 'document', group: '推广管理' },
  { label: '模型基础设施', to: '/admin/providers', icon: 'database', group: '系统配置' },
  { label: '模型定价', to: '/admin/pricing', icon: 'money', group: '系统配置' },
  { label: '管理员', to: '/admin/admins', icon: 'shield', group: '系统配置' },
] as const;
// These exports only support archived compatibility tests; no non-admin routes are mounted.
export const promoterNavigation = [];
export const apiNavigation = [];
