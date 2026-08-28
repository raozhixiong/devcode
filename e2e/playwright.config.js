const { defineConfig, devices } = require('@playwright/test');
const path = require('path');

// 项目根目录（e2e/ 的上一级）
const root = path.resolve(__dirname, '..');
const jar = path.join(root, 'target', 'lobster-gateway-0.1.0-SNAPSHOT.jar');
const stateDir = path.join(root, 'target', 'test-state-pw');

/**
 * Lobster 前端 E2E 配置：
 * - webServer 用 java -jar 启动网关（端口 18790，固定）。
 * - 使用独立 state-dir，保证无用户 → 鉴权不启用 → 前端免登录可直接用。
 * - 全局超时放宽，给 Spring Boot 冷启动与 Mock 对话留余量。
 */
module.exports = defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:18790',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: `java "-Dlobster.state-dir=${stateDir}" -jar "${jar}"`,
    cwd: root,
    url: 'http://localhost:18790/',
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
