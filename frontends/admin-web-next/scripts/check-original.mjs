import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, relative } from 'node:path';

const project = fileURLToPath(new URL('../', import.meta.url));
const root = resolve(project, '../..');
const baseline = resolve(project, '.original-baseline.json');
const allowed = relative(root, project).replaceAll('\\', '/') + '/';
const fromStdin = process.argv.includes('--files-from-stdin');
const listing = fromStdin ? readFileSync(0, 'utf8') : execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
const tracked = listing.split(fromStdin ? /\r?\n/ : '\0').filter(path => path && !path.startsWith(allowed));
const snapshot = Object.fromEntries(tracked.map(path => {
  const file = resolve(root, path);
  return [path, existsSync(file) ? createHash('sha256').update(readFileSync(file)).digest('hex') : null];
}));

if (process.argv.includes('--record')) {
  if (existsSync(baseline)) throw new Error('原项目快照已存在，拒绝覆盖。');
  writeFileSync(baseline, JSON.stringify(snapshot, null, 2) + '\n');
  console.log(`已记录 ${tracked.length} 个原项目文件的 SHA-256。`);
} else {
  if (!existsSync(baseline)) throw new Error('缺少原项目快照，请在修改前运行 npm run check:original -- --record。');
  const previous = JSON.parse(readFileSync(baseline, 'utf8'));
  const changes = [...new Set([...Object.keys(previous), ...tracked])].filter(path => previous[path] !== snapshot[path]);
  if (changes.length) {
    console.error('重构目录之外的文件发生变化：\n' + changes.join('\n'));
    process.exitCode = 1;
  } else console.log(`原项目校验通过：${tracked.length} 个文件未发生变化。`);
}
