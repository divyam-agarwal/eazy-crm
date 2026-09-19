import { existsSync, readFileSync } from 'node:fs';

// Spec §6.4: CI retries once; a test that passed only on retry is reported, not hidden.
const file = process.argv[2] ?? 'test-results/e2e-results.json';
console.log('## E2E flaky tests');
if (!existsSync(file)) {
  console.log('No Playwright JSON report was produced; see the step log.');
  process.exit(0);
}
const report = JSON.parse(readFileSync(file, 'utf8'));
const flaky = [];
const walk = (suite, trail) => {
  for (const spec of suite.specs ?? []) {
    for (const test of spec.tests ?? []) {
      if (test.status === 'flaky') flaky.push(`${test.projectName}: ${[...trail, spec.title].filter(Boolean).join(' › ')}`);
    }
  }
  for (const child of suite.suites ?? []) walk(child, [...trail, child.title]);
};
for (const suite of report.suites ?? []) walk(suite, [suite.title]);
console.log(flaky.length ? flaky.map((f) => `- ${f}`).join('\n') : 'None: every test passed on its first attempt.');

// Testing-6: Playwright exits 0 when a project's testDir matches NOTHING while the other passes,
// so "green" can mean "half the suite never ran". Count what actually executed, per project.
const EXPECTED = { 'e2e-main': 9, 'e2e-refresh': 2 }; // update alongside the specs
const ran = {};
const countWalk = (suite, projects) => {
  for (const spec of suite.specs ?? []) for (const t of spec.tests ?? []) projects[t.projectName] = (projects[t.projectName] ?? 0) + 1;
  for (const child of suite.suites ?? []) countWalk(child, projects);
};
for (const suite of report.suites ?? []) countWalk(suite, ran);
const short = Object.entries(EXPECTED).filter(([name, n]) => (ran[name] ?? 0) < n);
if (short.length) {
  console.log(`\n**Missing tests:** ${short.map(([n, e]) => `${n} ran ${ran[n] ?? 0}, expected ${e}`).join('; ')}`);
  process.exit(1);
}
