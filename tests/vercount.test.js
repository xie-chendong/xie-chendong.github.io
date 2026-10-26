'use strict';

const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');

const pages = [
  'public/index.html',
  'public/2026/08/22/Spring AOP 实现 MyBatis 执行性能追踪/index.html'
];

for (const page of pages) {
  const html = readFileSync(page, 'utf8');
  assert.equal((html.match(/src="https:\/\/events\.vercount\.one\/js"/g) || []).length, 1, `${page}: load Vercount once`);
  assert.ok(!html.includes('src="https://busuanzi.ibruce.info/'), `${page}: remove the original provider`);
  assert.ok(html.includes('id="busuanzi_value_site_pv"'), `${page}: preserve site PV`);
  assert.ok(html.includes('id="busuanzi_value_site_uv"'), `${page}: preserve site UV`);
}

assert.ok(readFileSync(pages[1], 'utf8').includes('id="busuanzi_value_page_pv"'), 'preserve article PV');
console.log('PASS: Vercount replaces the original script and preserves all three counters');
