'use strict';

// Fluid 固定拼接不蒜子的脚本文件名，替换地址以复用现有统计标签。
hexo.extend.filter.register('after_render:html', html => html.replaceAll(
  'src="https://busuanzi.ibruce.info/busuanzi/2.3/busuanzi.pure.mini.js"',
  'src="https://events.vercount.one/js"'
));
