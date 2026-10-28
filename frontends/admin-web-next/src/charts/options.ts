import type { EChartsCoreOption } from 'echarts/core';
export const chartColors = ['#4f46e5', '#158577', '#b08030', '#8867b3', '#c65c70', '#6a8db6'];
/** Conversion is exclusively for plotting; ledger displays and mutations retain decimal strings/BigInt. */
export function plottedAmount(value: string | null | undefined): number | null {
  if (value == null || !/^-?\d+(\.\d+)?$/.test(value)) return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}
export function lineOption(dates: string[], series: { name: string; values: (number | null)[] }[]): EChartsCoreOption {
  return {
    color: chartColors, animationDuration: 220,
    tooltip: { trigger: 'axis', confine: true, renderMode: 'richText', valueFormatter: (value: unknown) => value == null ? '金额未知' : `${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 })} 元` },
    legend: { bottom: 0, itemWidth: 14, itemHeight: 7, textStyle: { color: '#657187', fontSize: 11 } },
    grid: { top: 18, right: 20, bottom: 48, left: 18, containLabel: true },
    xAxis: { type: 'category', data: dates, boundaryGap: false, axisLine: { lineStyle: { color: '#e3e7ee' } }, axisTick: { show: false }, axisLabel: { color: '#657187', fontSize: 11, formatter: (date: string) => date.slice(5) } },
    yAxis: { type: 'value', min: series.some(item => item.values.some(value => value != null && value < 0)) ? undefined : 0, axisLabel: { color: '#657187', fontSize: 11 }, splitLine: { lineStyle: { color: '#edf0f4', type: 'dashed' } } },
    series: series.map((item, index) => ({ name: item.name, type: 'line', data: item.values,
      connectNulls: false, showSymbol: dates.length <= 7, symbolSize: 5, smooth: false,
      lineStyle: { width: 2 }, areaStyle: index === 0 ? { opacity: 0.05 } : undefined,
      emphasis: { focus: 'series' }, sampling: 'lttb' })),
  };
}
export function donutOption(rows: { name: string; amount: string | null | undefined }[], valid: boolean): EChartsCoreOption {
  return {
    color: chartColors, tooltip: { trigger: 'item', confine: true, renderMode: 'richText', formatter: '{b}\n{c} 元 · {d}%' },
    series: [{ type: 'pie', radius: ['62%', '82%'], center: ['50%', '50%'], minAngle: 1, stillShowZeroSum: false,
      label: { show: false }, labelLine: { show: false }, itemStyle: { borderColor: '#fff', borderWidth: 3 },
      data: valid ? rows.flatMap(row => { const value = plottedAmount(row.amount); return value == null ? [] : [{ name: row.name, value }]; }) : [],
      emphasis: { scaleSize: 4 }, emptyCircleStyle: { color: '#edf0f5' } }],
  };
}
