import * as echarts from 'echarts/core';
import { GaugeChart } from 'echarts/charts';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([
    GaugeChart,
    CanvasRenderer
]);

window.echarts = echarts;