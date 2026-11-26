/* Run against a fresh local stack. Install Playwright and Chromium first. */
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const base = process.env.P33_BROWSER_URL || 'http://localhost:8085';
const removeFonts = request => request.abort();

async function signIn(browser, role, mobile = false) {
  const context = await browser.newContext(mobile ? { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } : {});
  const page = await context.newPage();
  page.setDefaultTimeout(15_000);
  page.on('pageerror', error => console.error('Page error:', error.message));
  await page.route('https://fonts.googleapis.com/**', removeFonts);
  await page.route('https://fonts.gstatic.com/**', removeFonts);
  await page.goto(base + '/login');
  const demoButton = page.getByRole('button', { name: new RegExp('^Demo ' + role + '$', 'i') });
  if (await demoButton.isVisible()) {
    await demoButton.click();
  } else {
    await page.getByRole('textbox', { name: 'Email address' }).fill(role + '@school.local');
    await page.locator('[name=password]').fill(process.env[role.toUpperCase() + '_PASSWORD']);
    await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  }
  await page.waitForURL('**/dashboard');
  await page.getByRole('heading', { name: new RegExp('Welcome, ' + role, 'i') }).waitFor();
  return page;
}

async function artifact(page, button) {
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: button, exact: true }).click()]);
  assert.equal(await download.failure(), null);
  return download.path();
}

async function main() {
  const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  try {
    const key = Date.now().toString();
    const examName = 'Bangla assessment updated ' + key;
    const teacher = await signIn(browser, 'teacher');
    await teacher.getByRole('link', { name: 'Create Exam' }).click();
    await teacher.getByLabel('Exam name').fill('Bangla assessment ' + key);
    await teacher.getByLabel('Subject').fill('Bangla');
    await teacher.getByLabel('Exam reference').fill('P33-' + key);
    await teacher.getByLabel('Exam date').fill('2026-11-25');
    await teacher.getByLabel('Question 1 description').fill('সালোকসংশ্লেষণ ব্যাখ্যা করো।');
    await teacher.locator('#exam-form').getByRole('button', { name: 'Create Exam', exact: true }).click();
    await teacher.getByRole('heading', { name: 'Bangla assessment ' + key }).waitFor();
    const examCard = teacher.locator('.exam-card').filter({ hasText: 'Bangla assessment ' + key });
    await examCard.getByRole('button', { name: 'Edit Exam' }).click();
    await teacher.getByLabel('Exam name').fill(examName);
    await teacher.getByRole('button', { name: 'Save Exam Update' }).click();
    await teacher.getByRole('heading', { name: examName }).waitFor();
    await teacher.goto(base + '/rubrics');
    await teacher.locator('#rubric-form select[name=exam]').selectOption({ label: examName });
    await teacher.getByLabel('Criterion 1').fill('Definition of photosynthesis');
    await teacher.getByLabel('Evidence hints').fill('সালোকসংশ্লেষণ');
    await teacher.getByRole('button', { name: 'Publish Signed Version' }).click();
    await teacher.getByRole('heading', { name: 'Published Versions' }).waitFor();
    await teacher.getByLabel('Criterion 1').fill('Updated photosynthesis definition');
    const [updated] = await Promise.all([
      teacher.waitForResponse(response => response.url().endsWith('/api/rubrics') && response.request().method() === 'POST'),
      teacher.getByRole('button', { name: 'Save Rubric Update' }).click()
    ]);
    assert.ok(updated.ok(), 'Rubric update failed: ' + updated.status());
    await teacher.getByText('Rubric update saved and signed.').waitFor();
    await teacher.reload();
    await teacher.getByLabel('Criterion 1').waitFor();
    assert.equal(await teacher.getByLabel('Criterion 1').inputValue(), 'Updated photosynthesis definition');
    const raster = await teacher.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = 850;
      canvas.height = 220;
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = 'white';
      ctx.fillRect(0, 0, 850, 220);
      ctx.fillStyle = 'black';
      ctx.font = 'bold 50px sans-serif';
      ctx.fillText('POWERED BY LIGHT', 40, 110);
      return canvas.toDataURL('image/png').split(',')[1];
    });
    await teacher.goto(base + '/upload');
    await teacher.locator('#upload-exam').selectOption({ label: examName + ' · P33-' + key });
    await teacher.locator('select[name=rubricScope]').selectOption('all');
    await teacher.locator('#files').setInputFiles({ name: 'synthetic.png', mimeType: 'image/png', buffer: Buffer.from(raster, 'base64') });
    await teacher.getByLabel('Candidate code for file 1').fill('BD-' + key);
    await teacher.getByRole('button', { name: 'Upload Scripts' }).click();
    await teacher.waitForURL(url => url.pathname === '/scripts' && url.searchParams.has('exam'));
    const row = teacher.getByRole('row').filter({ hasText: 'BD-' + key });
    await row.waitFor();
    const review = await row.locator('.script-link').getAttribute('href');
    const scriptId = new URL(review, base).searchParams.get('script');

    console.log('Teacher created the exam, published a signed rubric and uploaded an assigned script.');
    const marker = await signIn(browser, 'marker');
    await marker.goto(base + review);
    await marker.getByRole('heading', { name: 'Review & Assess Answer' }).waitFor();
    await marker.getByRole('button', { name: 'Add Answer Region' }).click();
    await marker.waitForFunction(() => document.querySelector('#modal')?.dataset.loadedPage === '1');
    await marker.getByRole('button', { name: 'Save Region' }).click();
    await marker.getByRole('button', { name: 'Region 1 · Page 1' }).waitFor();
    await marker.getByRole('button', { name: 'Auto OCR & Assess' }).click();
    await marker.getByRole('button', { name: 'Refresh Job' }).waitFor();
    for (let attempt = 0; attempt < 12; attempt++) {
      await marker.getByRole('button', { name: 'Refresh Job' }).click();
      if (await marker.getByLabel('Unverified OCR draft').isVisible()) break;
      await new Promise(resolve => setTimeout(resolve, 700));
    }
    const draft = await marker.getByLabel('Unverified OCR draft').inputValue();
    assert.match(draft, /POWERED BY LIGHT/i);
    const text = 'সালোকসংশ্লেষণ হলো খাদ্য তৈরির প্রক্রিয়া।';
    await marker.getByLabel('Verified transcription').fill(text);
    assert.equal(await marker.getByLabel('Verified transcription').inputValue(), text);
    await marker.getByRole('button', { name: 'Save Question' }).click();
    await marker.getByText('Question draft saved.').waitFor();
    const [suggestionResponse] = await Promise.all([
      marker.waitForResponse(response => response.url().endsWith('/suggestion')),
      marker.getByRole('button', { name: 'Generate Suggestions' }).click()
    ]);
    assert.ok(suggestionResponse.ok(), 'Suggestion request failed: ' + suggestionResponse.status());
    const visibleAnswer = (await suggestionResponse.json()).scripts.find(script => script.id === scriptId).answers[0];
    assert.equal(visibleAnswer.ocr, text, 'Verified text persisted at the server');
    assert.equal(visibleAnswer.criteria[0].hint, 'সালোকসংশ্লেষণ', 'Rubric hint persisted at the server');
    assert.equal(visibleAnswer.regions?.length, 1, 'Evidence region persisted at the server');
    const current = visibleAnswer.suggestion;
    assert.equal(current.status, 'ADVISORY', JSON.stringify(current));
    assert.equal(current.criteria[0].score, 10);
    await marker.locator('.suggestion-list').getByText(/10 suggested marks/).waitFor();
    await marker.getByLabel('Updated photosynthesis definition · Max 10').fill('6.25');
    await marker.getByLabel('Supporting excerpt').fill('সালোকসংশ্লেষণ');
    await marker.getByLabel('Remarks').fill('Definition provided with partial explanation.');
    await marker.getByRole('button', { name: 'Submit All Questions' }).click();
    await marker.getByText('Submitted for moderation.', { exact: true }).waitFor();

    console.log('Marker received isolated OCR, verified Bangla text and submitted a fractional mark.');
    const moderator = await signIn(browser, 'moderator');
    await moderator.getByRole('navigation', { name: 'Main navigation' }).getByText('Moderation').click();
    const submission = moderator.locator('.moderation-row').filter({ hasText: examName });
    await submission.getByRole('button', { name: 'Approve Final Marks' }).click();
    await moderator.getByText('Final marks approved.', { exact: true }).waitFor();
    await moderator.getByRole('navigation', { name: 'Main navigation' }).getByText('Reports').click();
    const report = await artifact(moderator, 'Signed Bundle');
    await moderator.goto(base + '/verify');
    await moderator.getByLabel('Signed ZIP bundle').setInputFiles(report);
    await moderator.getByRole('button', { name: 'Verify Bundle' }).click();
    await moderator.getByText('Valid signature and current revision.').waitFor();

    const mobile = await signIn(browser, 'marker', true);
    await mobile.goto(base + review);
    await mobile.getByRole('heading', { name: 'Review & Assess Answer' }).waitFor();
    const widths = await mobile.evaluate(() => ({
      page: document.documentElement.clientWidth,
      rendered: document.documentElement.scrollWidth
    }));
    assert.ok(widths.rendered <= widths.page + 1, 'Mobile review has horizontal page overflow');
    assert.equal(await mobile.getByRole('button', { name: 'Submit All Questions' }).count(), 0);
    console.log('Passed Chromium desktop and mobile workflow, isolated OCR, decimal marking, and signed bundle verification.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
