export type CommerceTool = 'product-suite' | 'detail-page' | 'image-replica' | 'product-retouch' | 'model-wear' | 'apparel-set';

export const commerceTools: Record<CommerceTool, {
  title: string; templates: string[]; productLabel: string; referenceLabel?: string;
  requirements: string; action: string; copy: boolean;
}> = {
  'product-suite': { title: '商品套图', templates: ['general', 'apparel', 'beauty', 'streetwear'], productLabel: '商品图片', requirements: '商品卖点', action: '生成套图', copy: true },
  'detail-page': { title: 'A+/详情页', templates: ['detail-page'], productLabel: '商品图片', requirements: '商品卖点', action: '生成详情页', copy: true },
  'image-replica': { title: '图片复刻', templates: ['image-replica'], productLabel: '商品图片', referenceLabel: '参考效果图', requirements: '复刻要求', action: '生成图片复刻', copy: false },
  'product-retouch': { title: '产品精修', templates: ['product-retouch'], productLabel: '商品图片', requirements: '精修要求', action: '生成产品精修', copy: false },
  'model-wear': { title: '模特穿戴', templates: ['model-wear'], productLabel: '服装图片', referenceLabel: '模特参考图', requirements: '穿戴要求', action: '生成模特穿戴', copy: false },
  'apparel-set': { title: '服装组图', templates: ['apparel-set'], productLabel: '服装图片', requirements: '服装卖点', action: '生成服装组图', copy: true },
};
