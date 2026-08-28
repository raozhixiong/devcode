const { test, expect } = require('@playwright/test');

/**
 * 场景 B — 运维自动化治理（Ops Automation & Governance）
 * 串起：connect → 接入企业集成(key) → 装钩子(agent.run.ended 推送到集成) → 插件市场刷新+安装 →
 * 配置中心 → 审批中心 → 审计台账(留痕) → /new 巡检会话 → 聊天 → /share。
 * 审批中心在 Mock LLM 下通常无 pending 项，故仅断言面板已渲染（治理闭环可操作）；
 * 若列表出现 pending 项，则点击通过/拒绝验证 approval.resolve 链路。
 */
function dialogFiller(page) {
  const queue = [];
  page.on('dialog', async (dialog) => { await dialog.accept(queue.shift() ?? ''); });
  return (...answers) => queue.push(...answers);
}

test.describe('Lobster 流程场景 E2E（场景 B：运维自动化治理）', () => {

  test('F1 建立 WebSocket 连接', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/, { timeout: 20_000 });
    await expect(page.locator('#connText')).toHaveText('已连接', { timeout: 20_000 });
  });

  test('F2 接入企业集成（FR-B3/I4）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="ints"]');
    await page.locator('#intList .item').first().waitFor({ state: 'attached', timeout: 5000 }).catch(() => {});
    const before = await page.locator('#intList .item').count();
    const fill = dialogFiller(page);
    fill('team-im', 'key', 'x-corp-123');
    await page.click('[data-act="int-add"]');
    await expect(page.locator('#intList .item')).toHaveCount(before + 1, { timeout: 10_000 });
  });

  test('F3 安装钩子把运行结果推送出去（FR-I1）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="hooks"]');
    await page.locator('#hookList .item').first().waitFor({ state: 'attached', timeout: 5000 }).catch(() => {});
    const before = await page.locator('#hookList .item').count();
    const fill = dialogFiller(page);
    fill('agent.run.ended', 'echo notify-im');
    await page.click('[data-act="hook-add"]');
    await expect(page.locator('#hookList .item')).toHaveCount(before + 1, { timeout: 10_000 });
  });

  test('F4 插件市场刷新并安装扩展（FR-I5）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="market"]');
    await page.click('[data-act="market-refresh"]');
    await expect(page.locator('#marketList .item').first()).toBeVisible({ timeout: 10_000 });
    await page.locator('#marketList .item button').first().click();
    await page.click('.tab[data-tab="settings"]');
    await expect(page.locator('#pluginList .item').first()).toBeVisible({ timeout: 10_000 });
  });

  test('F5 配置中心渲染（FR-H-3）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="settings"]');
    await expect(page.locator('#tab-settings')).not.toHaveClass(/hidden/);
    const cn = await page.locator('#cfgList .item').count();
    if (cn > 0) {
      const inp = page.locator('#cfgList .item input.cfg-val').first();
      await inp.fill('e2e-value');
      await inp.blur();
    }
  });

  test('F6 审批中心渲染（FR-A5-5）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="approvals"]');
    await expect(page.locator('#tab-approvals')).not.toHaveClass(/hidden/);
    // 若列表存在 pending 项，验证通过/拒绝按钮已接线
    const pending = page.locator('#approvalList .item:has(button)');
    if (await pending.count() > 0) {
      await pending.first().locator('button').first().click();
      await expect(page.locator('#tab-approvals')).not.toHaveClass(/hidden/);
    }
  });

  test('F7 审计台账渲染（FR-G-5）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.click('.tab[data-tab="audit"]');
    await expect(page.locator('#tab-audit')).not.toHaveClass(/hidden/);
  });

  test('F8 新建会话并跑巡检（FR-B1/C3）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    const fill = dialogFiller(page);
    fill('nightly-probe');
    await page.click('#newSession');
    await expect(page.locator('#sessions .session', { hasText: 'nightly-probe' })).toBeVisible({ timeout: 10_000 });
    await page.fill('#input', '每天 9 点巡检服务状态');
    await page.click('#send');
    await expect(page.locator('#messages .msg.assistant').first()).toContainText('Mock 模式', { timeout: 20_000 });
  });

  test('F9 /share 分享会话（FR-I7）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    await page.fill('#input', '生成一份巡检摘要');
    await page.click('#send');
    await expect(page.locator('.msg.assistant').first()).toBeAttached({ timeout: 10_000 });
    await page.fill('#input', '/share');
    await page.click('#send');
    await expect(page.locator('#shareModal')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#shareUrl')).toContainText('/share/');
    await page.click('#shareClose');
    await expect(page.locator('#shareModal')).toBeHidden();
  });

  test('F10 端到端：运维治理闭环（B2→B3→B4→B6→B7→B8→B9）', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#conn')).toHaveClass(/on/);
    const fill = dialogFiller(page);
    // 集成
    await page.click('.tab[data-tab="ints"]');
    await page.locator('#intList .item').first().waitFor({ state: 'attached', timeout: 5000 }).catch(() => {});
    const ib = await page.locator('#intList .item').count();
    fill('team-im', 'key', 'x-corp-123');
    await page.click('[data-act="int-add"]');
    await expect(page.locator('#intList .item')).toHaveCount(ib + 1, { timeout: 10_000 });
    // 钩子
    await page.click('.tab[data-tab="hooks"]');
    await page.locator('#hookList .item').first().waitFor({ state: 'attached', timeout: 5000 }).catch(() => {});
    const hb = await page.locator('#hookList .item').count();
    fill('agent.run.ended', 'echo notify-im');
    await page.click('[data-act="hook-add"]');
    await expect(page.locator('#hookList .item')).toHaveCount(hb + 1, { timeout: 10_000 });
    // 插件市场安装
    await page.click('.tab[data-tab="market"]');
    await page.click('[data-act="market-refresh"]');
    await expect(page.locator('#marketList .item').first()).toBeVisible({ timeout: 10_000 });
    await page.locator('#marketList .item button').first().click();
    // 审批面板
    await page.click('.tab[data-tab="approvals"]');
    await expect(page.locator('#tab-approvals')).not.toHaveClass(/hidden/);
    // 审计面板
    await page.click('.tab[data-tab="audit"]');
    await expect(page.locator('#tab-audit')).not.toHaveClass(/hidden/);
    // 新建会话跑巡检
    fill('ops-e2e');
    await page.click('#newSession');
    await expect(page.locator('#sessions .session', { hasText: 'ops-e2e' })).toBeVisible({ timeout: 10_000 });
    await page.fill('#input', '巡检服务健康度');
    await page.click('#send');
    await expect(page.locator('#messages .msg.assistant').first()).toContainText('Mock 模式', { timeout: 20_000 });
    // 分享
    await page.fill('#input', '/share');
    await page.click('#send');
    await expect(page.locator('#shareModal')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('#shareUrl')).toContainText('/share/');
  });
});
