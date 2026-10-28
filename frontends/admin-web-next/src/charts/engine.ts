import { init, use } from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent, DataZoomComponent, AriaComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, DataZoomComponent, AriaComponent, CanvasRenderer]);
export { init };
