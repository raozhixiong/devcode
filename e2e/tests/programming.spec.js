const { test, expect } = require('@playwright/test');

/**
 * 场景 A — 开发者交付闭环（Developer Delivery Loop）
 * 串起：connect → /new 会话 → 参考库(上下文) → 钩子(自动化) → 技能/插件 → 编码对话(流式) → 产物 → 审批 → /share → 审计。
 * 断言基于真实 DOM；Mock LLM 下助手回复为「Mock 模式」，不会触发工具卡片（真实 LLM 才会）。
 */
function dialogFiller(page) {
  const queue = [];
  page.on('dialog', async (dialog) => { await dialog.accept(queue.shift() ?? ''); });
  return (...answers) => queue.push(...answers);
}

test.describe('Lobster 编程场景 E2E（场景 A：开发者交付闭环）', () => {

  test('P1 建立 WebSocket 连接（显示"已连接"）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/, { timeout: 20_000 });
    await expect(page.locator('#connText')).toHaveText('已连接', { timeout: 20_000 });
  });

  test('P2 新建编码会话（/new）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    const fill = dialogFiller(page);
    fill('feat-login');
    await page.click('#newSession');
    await expect(page.locator('#sessions .session', { hasText: 'feat-login' })).toBeVisible({ timeout: 10_000 });
  });

  test('P3 挂载团队参考库（上下文注入 FR-I3）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="refs"]');
    await page.locator('#refList .item').first().waitFor({ state: 'attached', timeout: 5000 }).catch(() => {});
    const before = await page.locator('#refList .item').count();
    const fill = dialogFiller(page);
    fill('团队编码规范', 'https://kb.corp/style');
    await page.click('[data-act="ref-add"]');
    await expect(page.locator('#refList .item')).toHaveCount(before + 1, { timeout: 10_000 });
  });

  test('P4 安装会话钩子（自动化 FR-I1）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="hooks"]');
    await page.locator('#hookList .item').first().waitFor({ state: 'attached', timeout: 5000 }).catch(() => {});
    const before = await page.locator('#hookList .item').count();
    const fill = dialogFiller(page);
    fill('session.created', 'echo init-env');
    await page.click('[data-act="hook-add"]');
    await expect(page.locator('#hookList .item')).toHaveCount(before + 1, { timeout: 10_000 });
  });

  test('P5 技能面板渲染并启用技能（FR-I5）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="skills"]');
    await expect(page.locator('#tab-skills')).not.toHaveClass(/hidden/);
    const n = await page.locator('#skillList .item').count();
    if (n > 0) {
      await page.locator('#skillList .item').first().click();
      await expect(page.locator('#tab-skills')).not.toHaveClass(/hidden/);
    }
  });

  test('P6 设置面板渲染配置与插件（FR-H-3/H-4）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="settings"]');
    await expect(page.locator('#tab-settings')).not.toHaveClass(/hidden/);
    const pn = await page.locator('#pluginList .item').count();
    if (pn > 0) {
      await page.locator('#pluginList .item').first().click();
      await expect(page.locator('#tab-settings')).not.toHaveClass(/hidden/);
    }
  });

  test('P7 发送编码任务后流式助手回复（FR-A4）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.fill('#input', '实现用户登录接口并写单元测试');
    await page.click('#send');
    await expect(page.locator('#messages .msg.user').last()).toContainText('用户登录');
    await expect(page.locator('#messages .msg.assistant').first()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('#messages .msg.assistant').first()).toContainText('Mock 模式');
    await expect(page.locator('#send')).toBeEnabled({ timeout: 20_000 });
  });

  test('P8 产物面板渲染（FR-I6）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="arts"]');
    await expect(page.locator('#tab-arts')).not.toHaveClass(/hidden/);
  });

  test('P9 审批中心面板渲染（FR-A5-5）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="approvals"]');
    await expect(page.locator('#tab-approvals')).not.toHaveClass(/hidden/);
  });

  test('P10 /share 生成只读分享链接（FR-I7）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.fill('#input', '生成一份编码摘要');
    await page.click('#send');
    await expect(page.locator('.msg.assistant').first()).toBeAttached({ timeout: 10_000 });
    await page.fill('#input', '/share');
    await page.click('#send');
    await expect(page.locator('#shareModal')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#shareUrl')).toContainText('/share/', { timeout: 10_000 });
    await page.click('#shareClose');
    await expect(page.locator('#shareModal')).toBeHidden();
  });

  test('P11 审计台账渲染（FR-G-5）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="audit"]');
    await expect(page.locator('#tab-audit')).not.toHaveClass(/hidden/);
  });

  test('P12 端到端：开发者交付闭环（A2→A3→A4→A7→A8→A10）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    const fill = dialogFiller(page);
    // 新会话
    fill('feat-e2e');
    await page.click('#newSession');
    await expect(page.locator('#sessions .session', { hasText: 'feat-e2e' })).toBeVisible({ timeout: 10_000 });
    // 参考库
    await page.click('.tab[data-tab="refs"]');
    await page.locator('#refList .item').first().waitFor({ state: 'attached', timeout: 5000 }).catch(() => {});
    const rb = await page.locator('#refList .item').count();
    fill('规范', 'https://kb.corp/e2e');
    await page.click('[data-act="ref-add"]');
    await expect(page.locator('#refList .item')).toHaveCount(rb + 1, { timeout: 10_000 });
    // 钩子
    await page.click('.tab[data-tab="hooks"]');
    await page.locator('#hookList .item').first().waitFor({ state: 'attached', timeout: 5000 }).catch(() => {});
    const hb = await page.locator('#hookList .item').count();
    fill('session.created', 'echo hi');
    await page.click('[data-act="hook-add"]');
    await expect(page.locator('#hookList .item')).toHaveCount(hb + 1, { timeout: 10_000 });
    // 编码任务
    await page.fill('#input', '实现登录');
    await page.click('#send');
    await expect(page.locator('#messages .msg.assistant').first()).toContainText('Mock 模式', { timeout: 20_000 });
    // 产物
    await page.click('.tab[data-tab="arts"]');
    await expect(page.locator('#tab-arts')).not.toHaveClass(/hidden/);
    // 分享
    await page.fill('#input', '/share');
    await page.click('#send');
    await expect(page.locator('#shareModal')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#shareUrl')).toContainText('/share/');
  });
});
