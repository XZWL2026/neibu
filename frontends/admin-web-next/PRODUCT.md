# YOMIO 管理后台

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
管理员和主管理员。原仓库同时包含代理端和 API 控制台，用户已明确本次仅重构管理员端。

## Product Purpose
运营人员管理用户、代理、API 客户、消费任务、支付充值、推广收益、模型基础设施、定价和管理员账号。

## Capabilities and Constraints
保留原项目管理员功能、接口协议、权限和深链接。用户已授权由实现者决定并完整重构管理员界面；导航、顶栏与内容区须保持业务可达与操作清晰。新项目位于 frontends/admin-web-next；不得修改原项目及共享代码。使用组件库和图标库、ECharts，优化缓存与加载性能。金融金额继续以十进制字符串和 BigInt 计算，图表数值不得用于交易。

## Brand Commitments
保留 YOMIO 品牌与现有业务术语。采用深色导航与浅色工作区、紧凑的日常运营界面，避免装饰效果压过数据；实际视觉规范记录于 DESIGN.md。

## Evidence on Hand
frontends/admin-web 提供业务页面、生成接口合约和回归测试。原业务为只读证据；后端接口存在支付订单仅提供分页和截止时间的限制，不得宣称已有服务端组合过滤。

## Product Principles
- 功能完整比视觉演示重要。
- 服务端是身份、权限和账务的唯一权威。
- 敏感 API 查询仅内存缓存；退出与换号立刻清理。
- 清晰呈现加载、未知数据、空状态和失败恢复。
