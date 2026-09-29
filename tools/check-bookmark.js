/*
 * 构建期校验：书签脚本语法 + 编码后 URL 大小
 * 用途：javascript: 书签对长度敏感（浏览器有上限），且语法错误只在用户点击时才暴露。
 * 用法：node tools/check-bookmark.js
 */
const fs = require('fs')
const path = require('path')
const vm = require('vm')

const SRC = path.join(__dirname, '..', 'frontend', 'src', 'assets', 'arp-bookmark.js')
const script = fs.readFileSync(SRC, 'utf8')

let failed = false

// 1) 语法校验

try {
  new vm.Script(script)
  console.log('[OK] 语法检查通过')
} catch (e) {
  console.error('[FAIL] 语法错误:', e.message)
  failed = true
}

// 2) 危险字面量：会破坏 HTML/书签解析

const dangers = [
  ['</script>', '会被 HTML 解析器截断'],
  ['<!--', 'HTML 注释起止符可能干扰解析'],
]

for (const [needle, why] of dangers) {
  if (script.includes(needle)) {
    console.error(`[FAIL] 脚本含危险字面量 ${needle} —— ${why}`)
    failed = true
  }
}

// 3) 编码后的 URL 长度

const encoded = encodeURIComponent(script)
const href = 'javascript:' + encoded
const LIMIT = 40000

console.log(`[INFO] 源码 ${script.length} 字节 → 编码后 href ${href.length} 字节`)
if (href.length > LIMIT) {
  console.error(`[FAIL] href 超过 ${LIMIT} 字节，部分浏览器可能截断`)
  failed = true
} else {
  console.log(`[OK] href 长度在安全范围（< ${LIMIT}）`)
}

process.exit(failed ? 1 : 0)
