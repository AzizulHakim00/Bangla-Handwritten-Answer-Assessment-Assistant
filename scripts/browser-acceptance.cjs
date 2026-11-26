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

async function testRubricEligibility(teacher, key) {
  const token = await (await teacher.request.get(base + '/api/csrf')).json();
  async function save(path, fields) {
    const workspace = await (await teacher.request.get(base + '/api/workspace')).json();
    const response = await teacher.request.post(base + path, {
      headers: { [token.headerName]: token.token }, data: { ...fields, version: workspace.version }
    });
    assert.ok(response.ok(), await response.text());
    return response.json();
  }
  const details = { name: 'Mixed maxima ' + key, subject: 'Bangla', reference: 'MIX-' + key,
    date: '2026-11-25', questions: 2, items: [{ description: 'First question', maximum: 10 }, { description: 'Second question', maximum: 10 }] };
  const exams = await save('/api/exams', details);
  const exam = exams.exams.find(item => item.reference === 'MIX-' + key).id;
  await save('/api/rubrics', { exam, question: 0, criteria: [{ text: 'Default criterion', marks: 10 }] });
  details.items[1].maximum = 5;
  await save('/api/exams/' + exam, details);
  await teacher.goto(base + '/upload?exam=' + exam);
  const options = teacher.locator('select[name=rubricScope] option');
  await options.filter({ hasText: 'Question 1 only' }).waitFor({ state: 'attached' });
  assert.equal(await options.filter({ hasText: 'Question 2 only' }).count(), 0);
  assert.equal(await options.filter({ hasText: 'Whole exam' }).count(), 0);
  await save('/api/rubrics', { exam, question: 2, criteria: [{ text: 'Second criterion', marks: 5 }] });
  await teacher.reload();
  await options.filter({ hasText: 'Whole exam' }).waitFor({ state: 'attached' });
  await options.filter({ hasText: 'Question 2 only' }).waitFor({ state: 'attached' });
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
    assert.ok(await marker.locator('.workbench').evaluate(el =>
      el.querySelector('.document').getBoundingClientRect().right <= el.querySelector('.transcription').getBoundingClientRect().left));
    await marker.waitForFunction(() => document.querySelector('#review-image')?.naturalWidth > 0);
    await marker.locator('#review-image').evaluate(image => window.acceptanceOriginalPage = image);
    await marker.getByRole('tab', { name: 'Rubric & Score' }).click();
    await marker.getByRole('tab', { name: 'OCR Text' }).click();
    assert.ok(await marker.locator('#review-image').evaluate(image => image === window.acceptanceOriginalPage));
    await marker.getByRole('button', { name: 'Expand answer' }).click();
    assert.equal(await marker.locator('.document').evaluate(el => getComputedStyle(el).position), 'fixed');
    await marker.getByRole('button', { name: 'Expand answer' }).click();
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
    await marker.getByRole('button', { name: 'Save Question' }).click();
    await marker.locator('#review-feedback').getByText('Positive marks need an exact excerpt', { exact: false }).waitFor();
    await marker.getByLabel('Supporting excerpt').fill('সালোকসংশ্লেষণ');
    await marker.getByLabel('Remarks').fill('Definition provided with partial explanation.');
    const teacherWorkspace = await (await teacher.request.get(base + '/api/workspace')).json();
    const exam = teacherWorkspace.exams.find(record => record.name === examName);
    const csrf = await (await teacher.request.get(base + '/api/csrf')).json();
    const republished = await teacher.request.post(base + '/api/rubrics', { headers: { [csrf.headerName]: csrf.token },
      data: { version: teacherWorkspace.version, exam: exam.id, question: 1,
        criteria: [{ text: 'Future uploads only', marks: 10 }] } });
    assert.ok(republished.ok(), await republished.text());
    await marker.getByRole('button', { name: 'Save Question' }).click();
    await marker.locator('#review-feedback').getByText(/Your draft is still here/).waitFor();
    assert.equal(await marker.getByLabel('Updated photosynthesis definition · Max 10').inputValue(), '6.25');
    assert.equal(await marker.getByLabel('Supporting excerpt').inputValue(), 'সালোকসংশ্লেষণ');
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
    assert.ok(await mobile.locator('.workbench').evaluate(el =>
      el.querySelector('.document').getBoundingClientRect().bottom <= el.querySelector('.transcription').getBoundingClientRect().top));
    assert.ok(await mobile.locator('.review-main').evaluate(el =>
      el.querySelector('.transcription').getBoundingClientRect().bottom <= el.querySelector('.decision').getBoundingClientRect().top));
    await testRubricEligibility(teacher, key);
    console.log('Passed Chromium desktop and mobile workflow, isolated OCR, decimal marking, and signed bundle verification.');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
