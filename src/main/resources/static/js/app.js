'use strict';
const $=s=>document.querySelector(s), esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const paths={home:'M3 10 12 3l9 7v11h-6v-7H9v7H3Z',file:'M6 3h8l4 4v14H6Z M14 3v5h5 M9 12h6 M9 16h6',layers:'m12 3 10 5-10 5L2 8Z m-10 10 10 5 10-5 M2 13l10 5 10-5',check:'m5 12 4 4L19 6',plus:'M12 5v14 M5 12h14',upload:'M12 16V3 m-5 5 5-5 5 5 M4 15v6h16v-6',arrow:'M5 12h14 m-6-6 6 6-6 6',back:'M19 12H5 m6-6-6 6 6 6',chevron:'m9 5 7 7-7 7',bell:'M5 17h14l-2-4V9a5 5 0 0 0-10 0v4Z M10 21h4',chart:'M4 3v18h17 M8 16v-5 M12 16V7 M16 16v-8 M20 16v-3',rubric:'M8 3h8v4H8Z M6 5H4v16h16V5h-2 M8 12h8 M8 16h5',user:'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M4 21v-3a8 6 0 0 1 16 0v3',mail:'M3 5h18v14H3Z m0 0 9 8 9-8',lock:'M5 10h14v11H5Z M8 10V6a4 4 0 0 1 8 0v4',eye:'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12 M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',save:'M4 3h14l3 3v15H3V3Z M7 3v7h10V3 M7 21v-7h10v7',edit:'m4 16 11-11 4 4L8 20H4Z M13 7l4 4',trash:'M4 6h16 M9 6V3h6v3 M6 6l1 15h10l1-15 M10 10v7 M14 10v7',download:'M12 3v13 m-5-5 5 5 5-5 M4 16v5h16v-5',shield:'m12 2 9 4v6c0 6-9 10-9 10S3 18 3 12V6Z m-4 10 3 3 5-6',zoom:'M10 17a7 7 0 1 1 0-14 7 7 0 0 1 0 14 m5-2 6 6 M7 10h6',expand:'M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5'};
const icon=(n,cls='')=>`<svg class="icon ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${paths[n]||paths.file}"/></svg>`;
const logo=`<svg class="logo" viewBox="0 0 60 52" aria-hidden="true"><path fill="currentColor" d="M1 15 30 4l29 11-29 11Z"/><path d="M9 22v18c9-1 15 2 21 7 6-5 12-8 21-7V22M4 18v15" fill="none" stroke="currentColor" stroke-width="4"/><path d="m17 26 13 5 13-5v9c-5 0-9 2-13 5-4-3-8-5-13-5Z" fill="currentColor"/></svg>`;
let state={exams:[],scripts:[],criteria:[],version:0};
const routeParams=new URLSearchParams(location.search);
const release='2026.11.26-r3.5';
const authenticatorEnabled=document.querySelector('meta[name=authenticator-enabled]')?.content==='true';
const localDemoEnabled=document.querySelector('meta[name=local-demo-enabled]')?.content==='true';
const knownPageCounts=new Map();
const pageCount=record=>record.pages||knownPageCounts.get(record.id)||(record.mime==='application/pdf'?500:1);
const canRebind=record=>record.status==='To Review'&&!(record.answers||[record]).some(item=>
  item.reviewed||item.markRevision||String(item.ocr||'').trim()||
  item.score!=null&&(record.rubricScope!=null||Number(item.score)!==0));
let currentScript=routeParams.get('script'),tab='OCR Text',question=1,viewerPage=null;
const scopeFromUrl=()=>routeParams.has('rubric')?Number(routeParams.get('rubric')):null;
function rememberRubric(){
  history.replaceState(null,'','/rubrics?'+new URLSearchParams({exam:rubricExam,rubric:rubricQuestion}));
  try{sessionStorage.setItem('rubric:'+state.tenant+':'+state.user,JSON.stringify([rubricExam,rubricQuestion]))}catch{}
}
const toast=t=>{$('#toast').textContent=t;$('#toast').classList.add('visible');setTimeout(()=>$('#toast').classList.remove('visible'),3500)};
const csrfField=()=>`<input type="hidden" name="_csrf" value="${esc(document.querySelector('meta[name=csrf-token]')?.content||'')}">`;
const btn=(text,action,ic='',secondary=false)=>`<button class="button ${secondary?'secondary':''}" data-action="${action}">${ic?icon(ic):''}${text}</button>`;
const link=(text,url,ic='',secondary=false)=>`<a class="button ${secondary?'secondary':''}" href="${url}">${ic?icon(ic):''}${text}</a>`;
const sumMarks=values=>values.reduce((sum,value)=>sum+Math.round(Number(value)*100),0)/100;
const total=()=>sumMarks(state.criteria.map(c=>c.marks));
const script=()=>currentScript?state.scripts.find(s=>s.id===currentScript):state.scripts[0];
const answer=()=>script()?.answers?.[question-1]||script();
const teacher=()=>state.role==='TEACHER';
const moderator=()=>state.role==='MODERATOR';
const editable=()=>Boolean(script())&&!['Approved','Pending Moderation'].includes(script().status);
const writable=()=>state.role==='MARKER'&&editable();
const canSegment=()=>editable()&&(teacher()||writable());
const roleNav=()=>[
  ['Dashboard','/dashboard','home'],
  ...(teacher()?[['Exams','/exams','file'],['Rubrics','/rubrics','rubric']]:[]),
  ['Scripts','/scripts','file'],
  ...(moderator()?[['Moderation','/moderation','shield']]:[]),
  ...(teacher()||moderator()?[['Reports','/reports','chart']]:[]),
  ['Verify','/verify','shield']
];
const badge=s=>`<span class="badge ${s==='Approved'?'green':s==='To Review'?'orange':'purple'}">${esc(s)}</span>`;
const examName=id=>esc(state.exams.find(e=>e.id===id)?.name||'Unknown exam');
function shell(content,page){return `<header><div class="topbar"><a class="brand" href="/dashboard">${logo}<span>Answer Assessment Assistant</span></a><div class="account"><button class="icon-button notification" data-action="notifications" aria-label="Notifications">${icon('bell')}<i></i></button><a class="avatar" href="/profile" aria-label="Profile">T</a><a href="/profile" class="teacher">${esc(state.role||"Teacher")}⌄</a></div></div><nav aria-label="Main navigation">${roleNav().map(([n,u,i])=>`<a class="${page===n?'active':''}" href="${u}">${icon(i)}${n}</a>`).join('')}</nav></header><main>${content}</main><footer>Answer Assessment Assistant <span>Build ${release}</span></footer><div class="bottom-nav">${[['Home','/dashboard','home'],['Scripts','/scripts','file'],[moderator()?'Moderation':teacher()?'Exams':'Verify',moderator()?'/moderation':teacher()?'/exams':'/verify',moderator()?'shield':'file'],['Profile','/profile','user']].map(([n,u,i])=>`<a href="${u}">${icon(i)}${n}</a>`).join('')}</div>`}
const heading=(title,desc,action='')=>`<div class="page-heading"><div><h1>${title}</h1><p>${desc}</p></div>${action}</div>`;
function rubricLabel(record){
  const scope=record.rubricScope;
  const versions=[...new Set((record.answers||[]).map(item=>item.rubricVersion).filter(value=>value?.startsWith('RUB-')))];
  const label=scope===0?'Default rubric':scope>0?'Question '+scope+' rubric':'Whole exam';
  return label+(versions.length===1?' · '+versions[0].slice(-8):versions.length>1?' · '+versions.length+' versions':' · Legacy');
}
function table(rows){
  return rows.length?`<div class="table-wrap scripts-table"><table><thead><tr><th>Script / candidate</th><th>Exam / rubric</th><th>Status</th><th>Actions</th></tr></thead><tbody>${rows.map(s=>`<tr>
    <td data-label="Script"><a class="script-link" href="/review?script=${s.id}" title="${esc(s.id)}">${esc(s.candidateToken||s.id)}</a></td>
    <td data-label="Exam / rubric" lang="bn">${examName(s.exam)}<div class="small muted">${esc(rubricLabel(s))}</div></td>
    <td data-label="Status">${badge(s.status)}<div class="small muted">${esc(s.updated?.slice(0,10)||'')}</div></td>
    <td data-label="Actions"><div class="script-actions">${link('Open','/review?script='+s.id,'',true)}${teacher()?`<button class="button secondary" data-assignment="${s.id}">Assign</button>${canRebind(s)?`<button class="button secondary" data-binding="${s.id}">Change Exam / Rubric</button>`:''}`:''}</div></td></tr>`).join('')}</tbody></table></div>`:'<p class="empty">No scripts match this selection.</p>';
}
function dashboard(){
  const action = teacher()?link('Create Exam','/exams?create=1','plus'):moderator()?link('Moderation Queue','/moderation','shield'):link('Assigned Scripts','/scripts','file');
  const pending = state.scripts.filter(s=>s.status==='Pending Moderation').length;
  const stats = [
    ['layers',state.scripts.length,teacher()?'Uploaded scripts':'Assigned scripts','blue'],
    ['shield',pending,'Pending moderation','lavender'],
    ['check',state.scripts.filter(s=>s.approvedSnapshot||s.status==='Approved').length,'Approved','mint']
  ];
  return shell(`${heading('Welcome, '+esc(state.role||'Teacher'),teacher()?'Prepare exams, publish rubrics and assign answer scripts.':moderator()?'Review submitted marks and manage appeals.':'Review assigned answers against the original handwriting.',action)}
    <div class="stats">${stats.map(([i,n,t,c])=>`<a href="/scripts" class="stat ${c}"><span class="stat-icon">${icon(i)}</span><div><strong>${n}</strong><span>${t}</span></div></a>`).join('')}</div>
    <section class="panel recent"><div class="section-heading"><h2>Recent Answer Scripts</h2><a href="/scripts">View All ${icon('arrow')}</a></div>${table([...state.scripts].sort((a,b)=>(b.updated||'').localeCompare(a.updated||'')).slice(0,5))}</section>
    ${teacher()?`<div class="form-actions">${link('Publish Rubric','/rubrics','rubric',true)}${link('Upload Scripts','/upload','upload')}</div>`:''}`, 'Dashboard');
}
function workflow(){
  const actions=moderator()?link('Moderation Queue','/moderation','shield',true)+link('Export Mark Sheet','/reports','download',true):teacher()?link('Export Mark Sheet','/reports','download',true):'';
  return actions?`<div class="form-actions">${actions}</div>`:'';
}
let rubricExam=routeParams.get('exam')||'',rubricQuestion=scopeFromUrl()??1;
function rubrics(){
  if(!rubricExam){
    try{
      [rubricExam,rubricQuestion]=JSON.parse(sessionStorage.getItem('rubric:'+state.tenant+':'+state.user))||['',1];
    }catch{}
  }
  const exam=state.exams.find(e=>e.id===rubricExam)||state.exams[0];
  if(!exam)return shell(heading('Rubrics','Create an exam to define its marking criteria.',link('Create Exam','/exams?create=1','plus')),'Rubrics');
  rubricExam=exam.id;
  if(!Number.isInteger(rubricQuestion)||rubricQuestion<0||rubricQuestion>exam.questions)rubricQuestion=1;
  const active=exam.rubricPointers?.[rubricQuestion];
  const inherited=rubricQuestion>0&&!active&&exam.rubricPointers?.[0];
  state.criteria=(rubricQuestion?exam.questionRubrics?.[rubricQuestion]:null)||exam.criteria||[];
  const max=exam.items?.[rubricQuestion-1]?.maximum;
  const editableCriteria=state.criteria.length?state.criteria:[{text:'',marks:max&&max<=100?max:1}];
  const versions=(state.rubricHistory||[]).filter(r=>r.exam===exam.id&&(r.question===rubricQuestion||r.question===0));
  return shell(`${heading('Publish Rubric','Set criterion marks and evidence hints for each question.')}
    <section class="panel rubric-panel"><form id="rubric-form">
      <div class="form-grid">
        <label>Exam<select name="exam">${state.exams.map(e=>`<option value="${e.id}" ${e.id===rubricExam?'selected':''}>${esc(e.name)}</option>`).join('')}</select></label>
        <label>Question<select name="rubricQuestion"><option value="0" ${rubricQuestion===0?'selected':''}>Default for all questions</option>${Array.from({length:exam.questions},(_,i)=>`<option value="${i+1}" ${rubricQuestion===i+1?'selected':''}>Question ${i+1}</option>`).join('')}</select></label>
      </div>
      ${max!==undefined?`<p class="notice">${esc(exam.items[rubricQuestion-1].description)} · Question maximum ${max}</p>`:''}
      <p class="notice" role="status">${active?'Saved rubric · '+esc(active):inherited?'Using the exam default. Save to create a question-specific rubric.':'This rubric has no published version.'}</p>
      <div class="section-heading"><h2>Scoring Criteria</h2><span class="muted">Total <strong id="rubric-total">${sumMarks(editableCriteria.map(c=>c.marks))}</strong> marks</span></div>
      <div id="criteria">${editableCriteria.map(criterion).join('')}</div>
      <button type="button" class="text-button" data-action="add-criterion">${icon('plus')} Add Criterion</button>
      <div class="form-actions"><button class="button" type="submit">${icon('shield')} ${active?'Save Rubric Update':'Publish Signed Version'}</button>
        ${(active||inherited)?link('Upload for This Rubric','/upload?exam='+exam.id+'&rubric='+rubricQuestion,'upload',true):''}</div>
    </form></section>
    ${versions.length?`<section class="panel"><h2>Published Versions</h2><ul class="version-list">${versions.slice().reverse().map(r=>`<li><a href="/api/rubrics/${r.id}/signed">${esc(r.id)}</a> · ${esc(r.publishedAt?.replace('T',' ').slice(0,16)||'')}${exam.rubricPointers?.[r.question]===r.id?' · Current':''}</li>`).join('')}</ul></section>`:''}`,'Rubrics');
}
function criterion(c,i){
  return `<div class="criterion">
    <label>Criterion ${i+1}<input name="criterion" value="${esc(c.text)}" maxlength="300" required></label>
    <label>Maximum<input name="marks" type="number" min="0.01" max="100" step="0.01" value="${c.marks}" required></label>
    <label>Evidence hints<input name="hint" value="${esc(c.hint||'')}" maxlength="300" placeholder="Exact phrase or alternatives separated by |"></label>
    <button type="button" class="icon-button danger" data-action="delete-criterion" aria-label="Remove criterion">${icon('trash')}</button>
  </div>`;
}
async function loadLegacyPageCount(){
  const record=script();
  if(!record||record.pages||record.mime!=='application/pdf')return;
  const response=await fetch('/api/scripts/'+record.id+'/document-info',{cache:'no-store'});
  if(!response.ok)throw Error('Could not read the page count from the original PDF.');
  knownPageCounts.set(record.id,(await response.json()).pages);
  const draft=writable()?reviewDraft():null;
  render();
  if(draft&&writable())restoreDraft(draft);
}
function reviewControls(parent, regions){
  if(writable()){
    const running=activeStatus(jobFor(parent)?.state);
    return `<div class="review-controls">
      <button class="button" data-action="assess-script" ${running?'disabled':''}>${icon('layers')} Auto OCR &amp; Assess</button>
      ${regions.length?btn('Run Region OCR','run-ocr','file',true):''}${btn('Add Answer Region','select-region','expand',true)}
      ${btn('Save Question','save-draft','save',true)}${btn('Submit All Questions','submit-review','arrow',true)}
    </div>`;
  }
  if(!editable()){
    return `<p class="notice">${parent.status==='Approved'?'The marks have been approved.':'The marks are awaiting moderation.'}${moderator()&&parent.status==='Pending Moderation'?' <a href="/moderation">Open moderation</a>':''}</p>`;
  }
  if(!teacher())return '<p class="notice">The assigned marker can run OCR and submit marks for moderation.</p>';
  const demoAssignment=parent.marker==='marker@school.local'||(!Object.hasOwn(parent,'marker')&&!Object.hasOwn(parent,'tenant'));
  const switchToMarker=localDemoEnabled&&demoAssignment;
  return `<div class="notice review-access"><p><strong>Teacher view.</strong> The assigned marker runs OCR, edits the transcription, and submits marks.${parent.marker?' Assigned to '+esc(parent.marker)+'.':''}</p>
    <div class="review-controls">
      ${switchToMarker?`<form id="demo-login-form" action="/login" method="post">
        ${csrfField()}<input type="hidden" name="reviewScript" value="${esc(parent.id)}">
        <button class="button" type="submit" name="demoRole" value="MARKER">${icon('arrow')} Open as Demo Marker</button>
      </form>`:''}
      <button class="button secondary" data-assignment="${esc(parent.id)}">Assign Script</button>
      ${canRebind(parent)?`<button class="button secondary" data-binding="${esc(parent.id)}">Change Exam / Rubric</button>`:''}
      ${canSegment()?btn('Add Answer Region','select-region','expand',true):''}
    </div></div>`;
}
function review(){
  const parent=script();
  const item=answer();
  if(!parent)return shell(heading(currentScript?'Script unavailable':'No answer scripts yet',currentScript?'Check the script reference and your assignment.':'Your available answer scripts will appear here.',teacher()?link('Upload Scripts','/upload'+(routeParams.has('exam')?'?exam='+encodeURIComponent(routeParams.get('exam')):''),'upload'):link('View Scripts','/scripts','file')),'Scripts');
  const s={...parent,...item};
  const regions=item.regions||(item.region?[item.region]:[]);
  const shownPage=viewerPage??item.ocrJob?.sourceSpans?.[0]?.source.page??s.page??1;
  const pages=pageCount(parent);
  state.criteria=item.criteria||[];
  const score=item.score??'';
  return shell(`<div class="review-heading"><a class="icon-button" href="/scripts" aria-label="Back to scripts">${icon('back')}</a><h1>Review &amp; Assess Answer</h1>
    <div>${esc(parent.candidateToken||parent.id)} <span class="divider">|</span> ${parent.rubricScope===0?'Default rubric · Entire script':`Question <strong>${item.examQuestion||question}${parent.rubricScope>0?'':' / '+(parent.answers?.length||1)}</strong>`} · ${examName(parent.exam)}</div>
    <button class="icon-button" data-action="prev-question" aria-label="Previous question" ${question<=1?'disabled':''}>${icon('back')}</button>
    <button class="icon-button" data-action="next-question" aria-label="Next question" ${question>=(parent.answers?.length||1)?'disabled':''}>${icon('chevron')}</button></div>
    ${reviewControls(parent, regions)}
    ${assessmentJobPanel(parent)}
    <section class="panel workbench"><div class="document">
      <div class="viewer-toolbar"><label>Original page <input id="viewer-page" type="number" min="1" max="${pages}" value="${shownPage}"></label>
      <span class="muted">of ${parent.pages||knownPageCounts.get(parent.id)||(parent.mime==='application/pdf'?'…':1)}</span>
      <button class="button secondary" data-action="prev-page" ${shownPage<=1?'disabled':''}>Previous Page</button>
      <button class="button secondary" data-action="next-page" ${shownPage>=pages?'disabled':''}>Next Page</button>
      <button class="icon-button" data-action="expand" aria-label="Expand answer">${icon('expand')}</button></div>
      <div class="paper-scroll"><img id="review-image" class="original-image" src="/api/scripts/${parent.id}/pages/${shownPage}" alt="Original handwritten answer page"></div>
      <a class="sample-caption" href="/api/scripts/${parent.id}/document" target="_blank" rel="noopener">Download original document</a>
      ${regions.length?`<div class="document-tools">${regions.map((r,i)=>`<button class="button secondary" data-region="${i}">Region ${i+1} · Page ${r.page}</button>`).join('')}</div>`:''}
    </div><div class="transcription">
      <div class="tabs" role="tablist">${['OCR Text','Script OCR','Rubric & Score','History'].map(t=>`<button role="tab" aria-selected="${tab===t}" class="${tab===t?'active':''}" data-tab="${t}">${t}</button>`).join('')}</div>
      <textarea id="ocr-text" lang="bn" aria-label="Verified transcription" ${tab==='OCR Text'?'':'hidden'} placeholder="Verify against the original handwriting">${esc(s.ocr||'')}</textarea>
      <div id="tab-content">
        ${tab==='OCR Text'?`${ocrPanel(s)}${suggestionPanel(item)}`:tab==='History'?historyPanel(parent):tab==='Script OCR'?scriptOcrPanel(parent, shownPage):`<h3>Rubric &amp; Score</h3>
          ${state.criteria.map(c=>`<div class="evidence">${esc(c.text)}<strong>${c.marks} marks</strong></div>`).join('')}
          ${item.rubricVersion?.startsWith('RUB-')?`<p><a href="/api/rubrics/${item.rubricVersion}/signed">Download the signed rubric used for this answer</a></p>`:''}${suggestionPanel(item)}`}
      </div>
    </div><div class="decision">
      <h3>Marker's Decision</h3><p>${badge(parent.status)}</p>
      <form id="review-form">
        <label>Evidence page<input id="answer-page" type="number" min="1" max="${pages}" value="${s.page||1}"></label>
        ${criterionMarking(item)}
        <label>Question score<div class="score-input"><input id="final-score" ${s.scoringMode==='CRITERIA'?'readonly':''} type="number" step="0.01" min="0" max="${total()}" value="${score}"><span>/ ${total()}</span></div></label>
        <label class="check-label"><input id="manual-review" type="checkbox" ${s.manualReview?'checked':''}> Mark manually from the original</label>
        <label>Remarks<textarea id="remarks" lang="bn" maxlength="2000">${esc(s.remarks||'')}</textarea></label>
        <p class="small muted">Explain manual review and changes to suggested marks in Remarks.</p>
      </form></div>
    </section>${workflow()}`,'Scripts');
}
function historyPanel(parent){
  const rows=parent.history||[];
  return `<h3>Review History</h3>${rows.length?rows.map(h=>`<div class="history-item">${icon('check')}<div>${esc(h.action)}<small>${esc(h.time)} · ${esc(h.user||'')}</small><small>Score ${h.score??'—'} · ${esc(h.answers?.map(a=>`Q${a.question} ${a.score??'—'}/${a.maximum}`).join(' · ')||'')}</small></div></div>`).join(''):'<p class="muted">No saved revisions yet.</p>'}`;
}
function scriptOcrPanel(parent, shownPage){
  const pages=parent.automationPages||[];
  return pages.length?`<h3>Extracted Script Pages</h3><p>Use the page text to check which answer it belongs to.</p>
    ${pages.map(page=>`<details class="ocr-panel" data-ocr-page="${page.page}" ${page.page===shownPage?'open':''}><summary>Page ${page.page} · ${page.text?'OCR available':'Needs manual review'}</summary>
      ${page.text?`<textarea readonly lang="bn" aria-label="Page ${page.page} OCR text">${esc(page.text)}</textarea>`:''}
      <div class="document-tools"><button class="button secondary" data-source-page="${page.page}">View Page ${page.page}</button>
      ${writable()&&page.text?`<button class="button secondary" data-copy-page="${page.page}">Append to Current Answer</button>`:''}</div></details>`).join('')}`
    :'<p>Page transcriptions appear here as Auto OCR &amp; Assess processes the script.</p>';
}
function suggestionPanel(item){
  const result=item.suggestion;
  return `<section class="suggestion"><h3>Rubric Suggestions</h3>
    ${result?`<p>${badge(result.status==='ADVISORY'?(result.provisional?'Provisional OCR suggestions':'Advisory only'):'Needs marker review')}</p>
    <ul class="suggestion-list">${result.criteria.map(row=>`<li><strong>${esc(row.label)}</strong>${row.score!==undefined?` · ${row.score} suggested marks
      <blockquote lang="bn">${esc(row.evidence)}</blockquote><button class="text-button" data-source-page="${row.source.page}">View original page ${row.source.page}</button>`:' · No suggestion'}
      <p class="small">${esc(row.rationale)}</p></li>`).join('')}</ul>`:'<p>Run automatic assessment or save verified text to generate suggestions.</p>'}
    ${writable()?btn('Generate Suggestions','suggestion','rubric',true):''}
    ${writable()&&result?.status==='ADVISORY'&&item.scoringMode==='CRITERIA'?btn('Use Matched Suggestions','apply-suggestions','rubric',true):''}</section>`;
}
function exams(){return shell(`${heading('Exams','Organize examinations and their assessment criteria.',btn('Create Exam','create-exam','plus'))}<div class="exam-grid">${state.exams.map(e=>`<article class="panel exam-card"><span class="stat-icon lavender">${icon('file')}</span><h2 lang="bn">${esc(e.name)}</h2><p>${esc(e.subject)} · ${e.questions} questions</p><p class="muted">${esc(e.date)}</p><div class="form-actions"><button class="button secondary" data-edit-exam="${e.id}">Edit Exam</button>${link('Set Rubric','/rubrics?exam='+e.id,'rubric',true)}${link('Upload','/upload?exam='+e.id,'upload',true)}${link('View Scripts','/scripts?exam='+e.id,'file')}</div></article>`).join('')}</div>`,'Exams')}
function scripts(){return shell(`${heading('Answer Scripts','Upload, review and follow each script through assessment.',teacher()?link('Upload Scripts','/upload'+(routeParams.has('exam')?'?exam='+encodeURIComponent(routeParams.get('exam')):''),'upload'):'')}<section class="panel"><div class="filters"><label class="search-label">Search scripts<input id="search" type="search" placeholder="Search by script ID or candidate token"></label><label>Status<select id="status-filter"><option value="">All statuses</option>${['To Review','In Review','Pending Moderation','Approved','Appeal Review'].map(s=>`<option>${s}</option>`).join('')}</select></label></div><div id="script-table">${table(filteredScripts())}</div></section>`,'Scripts')}
function filteredScripts(){const exam=new URLSearchParams(location.search).get('exam');return state.scripts.filter(s=>(!exam||s.exam===exam)&&(!$('#search')||[s.id,s.candidateToken||''].some(v=>v.toLowerCase().includes($('#search').value.toLowerCase())))&&(!$('#status-filter')||!$('#status-filter').value||s.status===$('#status-filter').value))}
function assigneeSelect(role,value=''){
  const choices=(state.assignees||[]).filter(a=>a.role===role.toUpperCase());
  return `<label>${role}<select name="${role.toLowerCase()}" required><option value="">Select ${role.toLowerCase()}</option>
    ${choices.map(a=>`<option value="${esc(a.email)}" ${a.email===value||!value&&choices.length===1?'selected':''}>${esc(a.email)}</option>`).join('')}</select></label>`;
}
function examPicker(id,selected){
  return `<label>Exam<select id="${id}" name="exam" required>
    <option value="">Select exam</option>${state.exams.map(e=>`<option value="${e.id}" ${e.id===selected?'selected':''}>${esc(e.name)}${e.reference?' · '+esc(e.reference):''}</option>`).join('')}</select></label>`;
}
function rubricOptions(examId,selected){
  const exam=state.exams.find(e=>e.id===examId);
  if(!exam)return '<option value="">Select the exam first</option>';
  const pointers=exam.rubricPointers||{};
  const versionFor=q=>pointers[q]||(!exam.questionRubrics?.[q]&&pointers[0]);
  const options=[];
  if(Array.from({length:exam.questions},(_,i)=>versionFor(i+1)).every(Boolean))
    options.push({scope:'all',label:'Whole exam · all question rubrics'});
  if(pointers[0])options.push({scope:'0',label:'Default rubric only',version:pointers[0]});
  for(let q=1;q<=exam.questions;q++){
    const version=versionFor(q);
    if(version)options.push({scope:String(q),label:`Question ${q} only${exam.items?.[q-1]?.description?' · '+exam.items[q-1].description:''}`,version});
  }
  return `<option value="">${options.length?'Select a published rubric':'Publish a rubric for this exam first'}</option>`+
    options.map(option=>`<option value="${option.scope}" data-version="${option.version||''}"
      ${String(selected)===option.scope?'selected':''}>${esc(option.label)}</option>`).join('');
}
function rubricPicker(exam,selected){
  return `<label>Assessment rubric<select name="rubricScope" required>${rubricOptions(exam,selected)}</select></label>`;
}
function bindingPayload(form){
  const option=form.elements.rubricScope.selectedOptions[0];
  if(!option?.value)throw Error('Select a published rubric for the chosen exam.');
  return {exam:form.elements.exam.value,rubricQuestion:option.value==='all'?null:Number(option.value),
    rubricVersion:option.dataset.version||null};
}
function upload(){
  const selected=routeParams.get('exam')||'';
  return shell(`${heading('Upload Answer Scripts','Choose the exam and the published rubric that will be used for these files.')}
    <section class="panel narrow"><form id="upload-form">${examPicker('upload-exam',selected)}${rubricPicker(selected,scopeFromUrl())}
      <div class="form-grid">${assigneeSelect('Marker')}${assigneeSelect('Moderator')}</div>
      <label class="dropzone" id="dropzone">${icon('upload')}<strong>Drop answer scripts here or browse</strong><span class="muted">PDF, JPG or PNG · Up to 20 MB per file</span>
      <input id="files" type="file" accept=".pdf,.jpg,.jpeg,.png" multiple required></label>
      <div id="file-list"></div><p class="notice">Use candidate codes and remove student names from the scans.</p>
      <div class="form-actions">${link('Cancel','/scripts','',true)}<button class="button" type="submit">${icon('upload')} Upload Scripts</button></div>
    </form></section>`,'Scripts');
}
function changeBinding(id){
  const selected=state.scripts.find(script=>script.id===id);
  const modal=$('#modal');
  const scope=selected.rubricScope??-1;
  modal.innerHTML=`<form id="binding-form" data-script="${id}"><h2>Change Exam or Rubric</h2>
    <p>Saved OCR drafts and regions will be cleared so the script can be assessed against the selected rubric.</p>
    ${examPicker('binding-exam',selected.exam)}${rubricPicker(selected.exam,scope===-1?'all':scope)}
    <div class="form-actions"><button type="button" class="button secondary" data-action="close-modal">Cancel</button>
    <button class="button">Save Exam and Rubric</button></div></form>`;
  modal.showModal();
}
function assignment(id){
  const selected=state.scripts.find(s=>s.id===id);
  const modal=$('#modal');
  modal.innerHTML=`<form id="assignment-form" data-script="${id}"><h2>Assign Script</h2>
    <p>${esc(selected.candidateToken||id)}</p>${assigneeSelect('Marker',selected.marker)}${assigneeSelect('Moderator',selected.moderator)}
    <div class="form-actions"><button type="button" class="button secondary" data-action="close-modal">Cancel</button>
    <button class="button">Save Assignment</button></div></form>`;
  modal.showModal();
}
function moderation(){const pending=state.scripts.filter(s=>s.status==='Pending Moderation');return shell(`${heading('Moderation & Approval','Review submitted marks before approving the final result.')}<section class="panel">${pending.length?pending.map(s=>`<article class="moderation-row"><div><h2>${s.id}</h2><p>${examName(s.exam)} · Reviewed score: ${s.score} / ${s.maximum}</p><p>${esc(s.remarks)}</p></div>${link('Inspect Answer','/review?script='+s.id,'eye',true)}${state.role==='MODERATOR'?`<button class="button secondary" data-return="${s.id}">Return for Correction</button><button class="button" data-approve="${s.id}">${icon('shield')} Approve Final Marks</button>`:''}</article>`).join(''):'<div class="empty">'+icon('shield')+'<h2>No marks awaiting approval</h2><p>Submit a reviewed answer to see it here.</p>'+link('Review Answers','/review','rubric')+'</div>'}</section><p class="notice">Approval requires the moderator account. Submitted revisions are preserved and approved marks cannot be edited.</p>`,'Reports')}
function reports(){return shell(`${heading('Reports & Export','Download the approved marks for your records.',`<div class=export-actions>${btn('CSV','export','download',true)}${btn('PDF','export-pdf','file',true)}${btn('Signed Bundle','export-signed','shield')}</div>`)}<section class="panel"><div class="section-heading"><h2>Approved Mark Sheet</h2>${link('Moderation Queue','/moderation','shield',true)}</div>${table(state.scripts.filter(s=>s.status==='Approved'||s.approvedSnapshot))}<div class=appeals-list>${state.scripts.filter(s=>s.status==='Approved'||s.approvedSnapshot).map(s=>`<article class=moderation-row><div><h3>${esc(s.id)}</h3><p>Approved score: ${s.approvedSnapshot?.score??s.score} / ${s.approvedSnapshot?.maximum??s.maximum}</p>${(s.appeals||[]).map(a=>`<p class=small>Appeal ${esc(a.status)}: ${esc(a.reason)} ${a.decisionReason?'· '+esc(a.decisionReason):''}</p>`).join('')}</div>${s.status==='Approved'&&(teacher()||moderator())?`<button class="button secondary" data-appeal="${s.id}">Open Appeal</button>`:''}${moderator()&&s.activeAppeal?`<button class="button secondary" data-uphold="${s.id}">Uphold Prior Approval</button>`:''}</article>`).join('')}</div><p class="muted">The last approved result remains available while an appeal is under review. Use the signed ZIP to verify its mark sheet with your institution’s trusted key.</p></section>`,'Reports')}
function verifyPage(){
  return shell(`${heading('Verify Signed Artifact','Check a signed rubric or approved mark sheet against your institution’s trusted keys.')}
    <section class="panel narrow"><form id="verify-form"><label>Signed ZIP bundle<input name="file" type="file" accept=".zip,application/zip" required></label>
    <div class="form-actions"><button class="button">${icon('shield')} Verify Bundle</button></div>
    <p id="verify-result" role="status"></p></form></section>`,'Verify');
}
function login(){
  const accountLogin = `
      <form id="login-form" action="/login" method="post">
        ${csrfField()}
        <label for="login-email">Email address</label>
        <div class="input-icon">
          ${icon('mail')}
          <input id="login-email" name="username" type="email" autocomplete="username" autocapitalize="none" spellcheck="false" required>
        </div>
        <label for="password">Password</label>
        <div class="input-icon">
          ${icon('lock')}
          <input id="password" name="password" type="password" autocomplete="current-password" minlength="4" required>
          <button type="button" class="icon-button" data-action="toggle-password" aria-controls="password" aria-label="Show password">${icon('eye')}</button>
        </div>
        ${authenticatorEnabled ? `<label for="login-otp">Authenticator code (if assigned)</label>
        <input id="login-otp" name="otp" autocomplete="one-time-code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6">` : ''}
        <button class="button" type="submit">Sign In ${icon('arrow')}</button>
      </form>
      <button class="text-button" data-action="forgot">Forgot password?</button>`;
  return `<div class="login-page">
    <div class="login-brand">
      ${logo}
      <h1>Answer Assessment<br>Assistant</h1>
      <p>Bangla Handwritten Answer<br> Assessment for Schools<br> and Examinations</p>
      <div class="books"><div></div><div></div><div></div></div><span class="plant">☘</span>
    </div>
    <section class="login-card">
      <h1>${localDemoEnabled ? 'Demo Sign In' : 'Sign In'}</h1>
      <p>${localDemoEnabled ? 'Choose a role to open the test workspace.' : 'Access your academic assessment account'}</p>
      ${localDemoEnabled ? `
        <form id="demo-login-form" class="login-demo-roles" action="/login" method="post">
          ${csrfField()}
          <button class="button" type="submit" name="demoRole" value="TEACHER">Demo Teacher ${icon('arrow')}</button>
          <button class="button" type="submit" name="demoRole" value="MARKER">Demo Marker ${icon('arrow')}</button>
          <button class="button" type="submit" name="demoRole" value="MODERATOR">Demo Moderator ${icon('arrow')}</button>
        </form>
        <details class="login-account"${routeParams.has('error') ? ' open' : ''}>
          <summary>Use a school account</summary>
          ${accountLogin}
        </details>` : accountLogin}
      <p class="small muted">${localDemoEnabled ? 'Local demo' : 'Use your school account to sign in.'} · Build ${release}</p>
    </section>
  </div>`;
}
function profile(){return shell(`${heading('Your Profile','Manage your assessment workspace.')}<section class="panel narrow"><div class="profile-avatar">T</div><h2>${esc(state.user||"School account")}</h2><p class="muted">${esc(state.role||"")} · Academic assessment workspace</p><div class="form-actions">${btn('Sign Out','logout','',true)}</div></section>`,'')}
function render(){const p=location.pathname;$('#app').innerHTML=({'/login':login,'/rubrics':rubrics,'/review':review,'/exams':exams,'/scripts':scripts,'/upload':upload,'/moderation':moderation,'/reports':reports,'/profile':profile,'/verify':verifyPage}[p]||dashboard)();document.title=(`${p.slice(1)||'Dashboard'} · Answer Assessment Assistant`);if(p==='/review'&&!writable()){document.querySelectorAll('#review-form input,#review-form textarea,#review-form select,#ocr-text').forEach(el=>el.disabled=true)}}
async function api(path,body,extraHeaders={}){
  const c=await fetch('/api/csrf').then(r=>r.json());
  const headers={[c.headerName]:c.token,...extraHeaders};
  if(!(body instanceof FormData))headers['Content-Type']='application/json';
  const r=await fetch('/api'+path,{method:'POST',headers,body:body instanceof FormData?body:JSON.stringify({...body,version:state.version})});
  if(!r.ok){
    let data;
    try{data=await r.json()}catch{}
    throw Error(data?.message|| (r.status===409?'This record changed while you were working. Refresh its status and try again.':`Request failed (${r.status}).`));
  }
  return r.json();
}
function reasonDialog(id,action){const m=$('#modal');m.innerHTML=`<form id="reason-form" data-script="${esc(id)}" data-endpoint="${action}"><h2>${action==='appeals'?'Open an Appeal':action==='appeals/resolve'?'Uphold Prior Approval':'Return for Correction'}</h2><label>Reason<textarea name="reason" required maxlength="2000"></textarea></label><div class="form-actions"><button type="button" class="button secondary" data-action="close-modal">Cancel</button><button class="button">Confirm</button></div></form>`;m.showModal()}
async function refresh(preserveDraft=false){
  const response=await fetch('/api/workspace',{cache:'no-store'});
  if(response.status===401){location.href='/login';return}
  if(!response.ok)throw Error('Cannot load workspace. Check the database connection.');
  const latest=await response.json();
  if(latest.version<state.version)return;
  const draft=preserveDraft&&writable()&&$('#review-form')?reviewDraft():null;
  state=latest;
  render();
  if(draft&&writable()){
    const available=evidenceRegions(answer()).length;
    draft.awards?.forEach(award=>{if(award.region!==null&&award.region>=available)award.region=null});
    restoreDraft(draft);
  }
}
function reviewDraft(){
  return {question,page:Number($('#answer-page').value),ocr:$('#ocr-text').value,remarks:$('#remarks').value,
    score:$('#final-score').value===''?null:Number($('#final-score').value),
    awards:answer().scoringMode==='CRITERIA'?captureAwards():null,manualReview:$('#manual-review').checked};
}
async function saveReview(submit,notice=true){
  if(!$('#review-form').reportValidity())throw Error('Check the evidence page and the criterion marks.');
  const body=reviewDraft();
  if(submit&&body.score===null)throw Error('Enter the question score before submitting.');
  await api('/scripts/'+script().id+'/review',{...body,submit});
  await refresh(!submit);
  if(notice)toast(submit?'Submitted for moderation.':'Question draft saved.');
}
function createExam(exam=null){
  const modal=$('#modal');
  modal.innerHTML=`<form id="exam-form" data-exam="${exam?.id||''}"><div class="section-heading"><h2>${exam?'Edit Exam':'Create Exam'}</h2>
    <button type="button" class="icon-button" data-action="close-modal" aria-label="Close">×</button></div>
    <label>Exam name<input name="name" placeholder="বাংলা পরীক্ষা" required maxlength="120"></label>
    <div class="form-grid"><label>Subject<input name="subject" required maxlength="80"></label><label>Exam reference<input name="reference" pattern="[A-Za-z0-9][A-Za-z0-9_\\-]{0,63}" required placeholder="BAN-08"></label></div>
    <div class="form-grid"><label>Questions<input id="exam-questions" name="questions" type="number" min="1" max="100" value="1" required></label>
    <label>Exam date<input name="date" type="date" required></label></div><div id="exam-items"></div>
    <div class="form-actions"><button type="button" class="button secondary" data-action="close-modal">Cancel</button>
    <button class="button">${exam?'Save Exam Update':'Create Exam'}</button></div></form>`;
  const fields=$('#exam-form').elements;
  for(const name of ['name','subject','reference','questions','date']){
    if(exam)fields[name].value=exam[name]??(name==='reference'?exam.id:'');
  }
  showExamItems();
  if(exam&&!exam.items?.length){
    [...$('#exam-items').children].forEach((row,index)=>{
      const criteria=exam.questionRubrics?.[index+1]||exam.criteria;
      row.querySelector('[name=description]').value=criteria?.[0]?.text||('Question '+(index+1));
      if(criteria?.length)row.querySelector('[name=maximum]').value=sumMarks(criteria.map(item=>item.marks));
    });
  }
  if(exam?.items)exam.items.forEach((item,index)=>{
    const row=$('#exam-items').children[index];
    if(!row)return;
    row.querySelector('[name=description]').value=item.description;
    row.querySelector('[name=maximum]').value=item.maximum;
  });
  modal.showModal();
}
function showExamItems(){
  const list=$('#exam-items');
  const count=Number($('#exam-questions').value);
  if(!Number.isInteger(count)||count<1||count>100)return;
  while(list.children.length>count)list.lastElementChild.remove();
  for(let i=list.children.length;i<count;i++)list.insertAdjacentHTML('beforeend',`<div class="form-grid item-fields">
    <label>Question ${i+1} description<input name="description" maxlength="1000" required></label>
    <label>Maximum marks<input name="maximum" type="number" min="0.01" max="3000" step="0.01" value="10" required></label></div>`);
}
const assessmentJobs=new Map();
const activeStatus=status=>['PENDING','RUNNING'].includes(status);
const jobFor=parent=>assessmentJobs.get(parent.id)||state.jobs?.findLast(job=>job.script_id===parent.id);
let pollTimer;
function scheduleReviewPoll(){
  clearTimeout(pollTimer);
  if(location.pathname!=='/review'||!script())return;
  if(!activeStatus(jobFor(script())?.state)&&!activeStatus(answer()?.ocrJob?.status))return;
  pollTimer=setTimeout(async()=>{
    if($('#modal').open||document.activeElement?.matches('textarea,input,select')){scheduleReviewPoll();return}
    try{
      if(activeStatus(jobFor(script())?.state))await refreshJob(true);
      else await refresh(true);
    }catch(error){toast(error.message)}
    finally{scheduleReviewPoll()}
  },2500);
}
function showPage(page){
  const max=Number($('#viewer-page').max);
  if(!Number.isInteger(page)||page<1||page>max)return;
  viewerPage=page;
  $('#viewer-page').value=page;
  $('#review-image').src='/api/scripts/'+script().id+'/pages/'+page;
  $('.paper-scroll').scrollTop=0;
  const pageText=$('[data-ocr-page="'+page+'"]');
  if(pageText)pageText.open=true;
  $('[data-action=prev-page]').disabled=page<=1;
  $('[data-action=next-page]').disabled=page>=max;
}
function applySuggestions(){
  const item=answer();
  const fields=[...document.querySelectorAll('.criterion-award')];
  const text=$('#ocr-text');
  if(!text.value.trim())text.value=item.ocrJob?.candidate||'';
  let matched=0;
  for(const row of item.suggestion?.criteria||[]){
    if(row.score===undefined||!text.value.includes(row.evidence))continue;
    const field=fields[row.criterion-1];
    if(!field)continue;
    const region=evidenceRegions(item).findIndex(source=>sameRegion(source,row.source));
    if(region<0)continue;
    field.querySelector('.award-score').value=row.score;
    field.querySelector('.award-evidence').value=row.evidence;
    field.querySelector('.award-region').value=region;
    matched++;
  }
  updateReviewTotal();
  toast(matched?`Applied ${matched} suggestion(s) to the draft. Check the source before saving.`:'No suggestion matches the current draft.');
}
function assessmentJobPanel(parent){
  const job=jobFor(parent);
  const labels={PENDING:'Queued',RUNNING:'Processing',NEEDS_REVIEW:'Ready to verify',MANUAL_REVIEW:'Needs manual review',STALE:'Outdated',FAILED:'Failed'};
  return job?`<div class="notice assessment-progress" role="status"><p>${esc(labels[job.state]||job.state)} · ${job.progress}%</p>
    ${job.state==='MANUAL_REVIEW'?'<p>Check Script OCR for available page text and review any unmatched answers.</p>':job.state==='STALE'?'<p>The script changed during assessment. Start a new run.</p>':''}
    ${btn('Refresh Job','refresh-job','',true)}</div>`:'';
}
async function refreshJob(onlyChanges=false){
  const id=script().id,job=jobFor(script());
  if(!job)return;
  const response=await fetch('/api/v1/jobs/'+job.job_id,{cache:'no-store'});
  if(!response.ok)throw Error('Assessment job could not be loaded.');
  const latest=await response.json();
  if(onlyChanges&&job.state===latest.state&&job.progress===latest.progress)return;
  assessmentJobs.set(id,latest);
  await refresh(true);
}
document.addEventListener('click',async event=>{
  const target=event.target.closest('[data-action],[data-tab],[data-approve],[data-return],[data-appeal],[data-region],[data-assignment],[data-uphold],[data-edit-exam],[data-binding],[data-source-page],[data-copy-page]');
  if(!target)return;
  try{
    if(target.dataset.tab){
      const draft=writable()?reviewDraft():null;
      tab=target.dataset.tab;render();
      if(draft)restoreDraft(draft);
      return;
    }
    if(target.dataset.sourcePage){showPage(Number(target.dataset.sourcePage));return}
    if(target.dataset.copyPage){
      const draft=reviewDraft();
      const page=(script().automationPages||[]).find(p=>p.page===Number(target.dataset.copyPage));
      draft.ocr=[draft.ocr.trim(),page.text].filter(Boolean).join('\n');
      draft.page=page.page; viewerPage=page.page; tab='OCR Text'; render(); restoreDraft(draft);return;
    }
    if(target.dataset.region!==undefined){await openRegion(Number(target.dataset.region));return}
    if(target.dataset.editExam){createExam(state.exams.find(exam=>exam.id===target.dataset.editExam));return}
    if(target.dataset.binding){changeBinding(target.dataset.binding);return}
    if(target.dataset.assignment){assignment(target.dataset.assignment);return}
    if(target.dataset.return){reasonDialog(target.dataset.return,'return');return}
    if(target.dataset.uphold){reasonDialog(target.dataset.uphold,'appeals/resolve');return}
    if(target.dataset.appeal){reasonDialog(target.dataset.appeal,'appeals');return}
    if(target.dataset.approve){
      await api('/v1/reviews',{resource_id:target.dataset.approve,expected_version:state.version,decision:'APPROVE'});
      await refresh();toast('Final marks approved.');return;
    }
    switch(target.dataset.action){
      case 'create-exam': createExam();break;
      case 'close-modal': $('#modal').close();break;
      case 'toggle-password': {
        const password = $('#password');
        const showPassword = password.type === 'password';
        password.type = showPassword ? 'text' : 'password';
        target.setAttribute('aria-label', showPassword ? 'Hide password' : 'Show password');
        break;
      }
      case 'forgot': toast('Contact your institutional account operator.');break;
      case 'notifications': toast(`${state.scripts.filter(s=>s.status==='To Review').length} scripts are waiting for review.`);break;
      case 'add-criterion': $('#criteria').insertAdjacentHTML('beforeend',criterion({text:'',marks:1},$('#criteria').children.length));updateTotal();break;
      case 'delete-criterion':
        if($('#criteria').children.length>1){target.closest('.criterion').remove();updateTotal()}
        else toast('Keep at least one criterion.');
        break;
      case 'save-draft': await saveReview(false);break;
      case 'submit-review': await saveReview(true);break;
      case 'next-question':
      case 'prev-question': {
        const next=question+(target.dataset.action==='next-question'?1:-1);
        if(next<1||next>(script().answers?.length||1))break;
        if(writable()&&changedDraft())await saveReview(false,false);
        question=next;viewerPage=null;render();break;
      }
      case 'prev-page': showPage(Number($('#viewer-page').value)-1);break;
      case 'next-page': showPage(Number($('#viewer-page').value)+1);break;
      case 'apply-suggestions': applySuggestions();break;
      case 'expand': $('.document').classList.toggle('expanded');break;
      case 'select-region': await openRegion(regionList().length);break;
      case 'load-region-page': await loadRegionPage();break;
      case 'remove-region': await saveRegion(true);break;
      case 'run-ocr': {
        await api('/scripts/'+script().id+'/ocr',{question});
        await refresh(true);scheduleReviewPoll();toast('OCR queued.');break;
      }
      case 'suggestion':
        if(changedDraft())await saveReview(false,false);
        await api('/scripts/'+script().id+'/suggestion',{question});
        await refresh(true);break;
      case 'assess-script': {
        const parent=script();
        if(activeStatus(jobFor(parent)?.state)){toast('Assessment is already running.');break}
        const reference=parent.rubricSetVersion||'legacy';
        const key=crypto.randomUUID();
        const job=await api('/v1/p33/script-assessments',{script_id:parent.id,rubric_version:reference},{'Idempotency-Key':key});
        assessmentJobs.set(parent.id,job);await refresh(true);scheduleReviewPoll();toast('Assessment queued.');break;
      }
      case 'refresh-job': await refreshJob();break;
      case 'refresh-ocr': await refresh(true);break;
      case 'use-ocr': if($('#ocr-text')){$('#ocr-text').value=answer().ocrJob?.candidate||'';toast('OCR draft copied. Verify it against the original before saving.')}break;
      case 'export': location.href='/api/exports/marks.csv';break;
      case 'export-pdf': location.href='/api/exports/marks.pdf';break;
      case 'export-signed': location.href='/api/exports/marks.zip';break;
      case 'logout': {
        const c=await fetch('/api/csrf').then(r=>r.json());
        await fetch('/logout',{method:'POST',headers:{[c.headerName]:c.token}});
        location.href='/login';break;
      }
    }
  }catch(error){toast(error.message)}
});
function updateReviewTotal(){
  const awards=captureAwards();
  $('#final-score').value=awards.some(a=>a.marks===null)?'':sumMarks(awards.map(a=>a.marks));
}
function updateTotal(){
  if($('#rubric-total'))$('#rubric-total').textContent=sumMarks([...document.querySelectorAll('[name=marks]')].map(input=>input.value));
}
document.addEventListener('input',event=>{
  const target=event.target;
  if(target.name==='marks')updateTotal();
  if(target.classList.contains('award-score'))updateReviewTotal();
  if(target.id==='search')$('#script-table').innerHTML=table(filteredScripts());
});
document.addEventListener('change',event=>{
  const target=event.target;
  if(location.pathname==='/rubrics'&&target.name==='exam'){rubricExam=target.value;rubricQuestion=1;rememberRubric();render()}
  if(target.name==='rubricQuestion'){rubricQuestion=Number(target.value);rememberRubric();render()}
  if(target.id==='upload-exam'||target.id==='binding-exam')
    target.form.elements.rubricScope.innerHTML=rubricOptions(target.value,null);
  if(target.id==='exam-questions')showExamItems();
  if(target.id==='status-filter')$('#script-table').innerHTML=table(filteredScripts());
  if(target.id==='viewer-page'&&target.reportValidity())showPage(Number(target.value));
  if(target.id==='files')$('#file-list').innerHTML=[...target.files].map((f,i)=>`<div class="file-item">
    <span>${esc(f.name)} · ${(f.size/1024/1024).toFixed(2)} MB</span>
    <label>Candidate code for file ${i+1}<input name="candidate-token" maxlength="40" placeholder="Automatically generated if blank"></label></div>`).join('');
});
document.addEventListener('submit',async event=>{
  const form=event.target;
  if(form.id==='login-form'||form.id==='demo-login-form')return;
  event.preventDefault();
  try{
    switch(form.id){
      case 'region-form': await saveRegion(false);break;
      case 'reason-form': {
        const id=form.dataset.script,reason=form.elements.reason.value;
        if(form.dataset.endpoint==='return')await api('/v1/reviews',{resource_id:id,expected_version:state.version,decision:'RECHECK',reason});
        else await api('/scripts/'+id+'/appeals',{reason});
        $('#modal').close();await refresh();toast('Saved with history preserved.');break;
      }
      case 'assignment-form':
        await api('/scripts/'+form.dataset.script+'/assignment',{marker:form.elements.marker.value,moderator:form.elements.moderator.value});
        $('#modal').close();await refresh();toast('Assignment saved.');break;
      case 'exam-form': {
        const d=new FormData(form);
        const maxima=d.getAll('maximum');
        const items=d.getAll('description').map((description,i)=>({description,maximum:Number(maxima[i])}));
        const existing=form.dataset.exam;
        await api('/exams'+(existing?'/'+existing:''),{name:d.get('name'),subject:d.get('subject'),reference:d.get('reference'),questions:items.length,date:d.get('date'),items});
        $('#modal').close();await refresh();toast(existing?'Exam updated.':'Exam created.');break;
      }
      case 'rubric-form': {
        const criteria=[...form.querySelectorAll('.criterion')].map(row=>({
          text:row.querySelector('[name=criterion]').value,marks:Number(row.querySelector('[name=marks]').value),hint:row.querySelector('[name=hint]').value
        }));
        const exam=state.exams.find(item=>item.id===form.elements.exam.value);
        const scope=Number(form.elements.rubricQuestion.value);
        const updating=Boolean(exam?.rubricPointers?.[scope]);
        await api('/rubrics',{exam:exam.id,question:scope,criteria});
        rememberRubric();await refresh();toast(updating?'Rubric update saved and signed.':'Rubric saved and signed.');break;
      }
      case 'binding-form': {
        await api('/scripts/'+form.dataset.script+'/binding',bindingPayload(form));
        $('#modal').close();
        location.href='/review?script='+encodeURIComponent(form.dataset.script);break;
      }
      case 'upload-form': {
        const d=new FormData();
        const binding=bindingPayload(form);
        d.append('version',state.version);d.append('exam',binding.exam);
        if(binding.rubricQuestion!==null){
          d.append('rubricQuestion',binding.rubricQuestion);d.append('rubricVersion',binding.rubricVersion);
        }
        d.append('marker',form.elements.marker.value);d.append('moderator',form.elements.moderator.value);
        [...$('#files').files].forEach(file=>d.append('files',file));
        const tokens=[...form.querySelectorAll('[name=candidate-token]')].map(input=>input.value.trim());
        if(tokens.some(Boolean))tokens.forEach(token=>d.append('candidateTokens',token));
        await api('/uploads',d);location.href='/scripts?exam='+encodeURIComponent(binding.exam);break;
      }
      case 'verify-form': {
        const result=$('#verify-result');
        result.textContent='Checking bundle…';
        const verified=await api('/verify',new FormData(form));
        const labels={VALID:'Valid signature and current revision.',SUPERSEDED:'Valid signature. A newer revision is current.',
          ALTERED:'The bundle is altered, incomplete or unsupported.',UNKNOWN_KEY:'The issuer key is unknown to this institution.',
          REVOKED_KEY:'The issuer key has been revoked.',UNAVAILABLE:'Your account cannot verify this revision.'};
        result.textContent=labels[verified.status]||verified.status;break;
      }
    }
  }catch(error){if(form.id==='verify-form')$('#verify-result').textContent=error.message;toast(error.message)}
});
if(location.pathname==='/login'){
  render();
  if(location.search.includes('error')){
    toast(authenticatorEnabled ? 'Check your sign-in details and any required authenticator code.' : 'Check your email address and password.');
  }
}else refresh().then(()=>{
  if(location.pathname==='/review')loadLegacyPageCount().catch(error=>toast(error.message));
  scheduleReviewPoll();if(location.pathname==='/exams'&&location.search.includes('create=1'))createExam()}).catch(err=>{$('#app').innerHTML='<main><h1>Workspace unavailable</h1><p>'+esc(err.message)+'</p><a href="/login">Sign in</a></main>'});

// File selection and drag/drop share the same validated server upload path.
document.addEventListener('dragover', e=>{if(e.target.closest('#dropzone'))e.preventDefault()});
document.addEventListener('drop', e=>{if(e.target.closest('#dropzone')){e.preventDefault();const input=$('#files');input.files=e.dataTransfer.files;input.dispatchEvent(new Event('change',{bubbles:true}))}});

function ocrPanel(item){
  const job=item.ocrJob;
  if(!job)return '';
  const labels={PENDING:'Queued',RUNNING:'Running',NEEDS_REVIEW:'Ready for review',FAILED:'Could not read',MANUAL_REVIEW:'Manual review needed'};
  const actions=writable()?btn('Refresh OCR','refresh-ocr','',true)+(job.candidate?btn('Copy Draft for Correction','use-ocr','edit',true):''):'';
  return `<section class="ocr-panel"><h3>OCR · ${esc(labels[job.status]||job.status)}</h3>
    ${job.candidate?`<label>Unverified OCR draft<textarea readonly lang="bn">${esc(job.candidate)}</textarea></label>`:''}
    ${job.message?`<p>${esc(job.message)}</p>`:''}<div class="document-tools">${actions}</div></section>`;
}
const regionList=()=>answer().regions||(answer().region?[answer().region]:[]);
async function openRegion(index){
  const regions=regionList(),saved=regions[index];
  const region=saved||{x:0,y:0,width:1,height:1,page:viewerPage??answer().page??1};
  if(!canSegment()){showPage(region.page);return;}
  const modal=$('#modal');
  modal.innerHTML=`<form id="region-form" data-index="${index}"><h2>${saved?'Edit':'Add'} Answer Region</h2>
    <p class="small muted">Drag across the answer or enter coordinates relative to the page.</p>
    <label>Original page<input id="region-page" type="number" min="1" max="${pageCount(script())}" value="${region.page}" required></label>
    <button type="button" class="button secondary" data-action="load-region-page">Load Page</button>
    <canvas id="region-canvas" aria-label="Draw an answer region on the document"></canvas>
    <div class="region-fields">${['x','y','width','height'].map(name=>`<label>${name}<input name="${name}" type="number" step="0.0001" min="0" max="1" value="${region[name]}" required></label>`).join('')}</div>
    <div class="form-actions"><button type="button" class="button secondary" data-action="close-modal">Cancel</button>
    ${saved?'<button type="button" class="button secondary" data-action="remove-region">Remove Region</button>':''}
    <button class="button">Save Region</button></div></form>`;
  modal.showModal();
  await loadRegionPage();
}
async function loadRegionPage(){
  const input=$('#region-page'),page=Number(input.value);
  if(!input.reportValidity()||!Number.isInteger(page))return;
  const image=new Image();
  await new Promise((resolve,reject)=>{
    image.onload=resolve;image.onerror=()=>reject(Error('The original page could not be loaded.'));
    image.src='/api/scripts/'+script().id+'/pages/'+page;
  });
  const canvas=$('#region-canvas');
  if(!canvas)return;
  canvas.width=image.naturalWidth;canvas.height=image.naturalHeight;
  $('#modal').dataset.loadedPage=page;
  const ctx=canvas.getContext('2d'),fields=$('#region-form').elements;
  const draw=()=>{
    ctx.drawImage(image,0,0);ctx.strokeStyle='#5031e8';ctx.lineWidth=4;
    ctx.strokeRect(Number(fields.x.value)*canvas.width,Number(fields.y.value)*canvas.height,
      Number(fields.width.value)*canvas.width,Number(fields.height.value)*canvas.height);
  };
  let start=null;
  const point=e=>{
    const box=canvas.getBoundingClientRect();
    return {x:Math.max(0,Math.min(1,(e.clientX-box.left)/box.width)),y:Math.max(0,Math.min(1,(e.clientY-box.top)/box.height))};
  };
  canvas.onpointerdown=e=>{start=point(e);canvas.setPointerCapture(e.pointerId)};
  canvas.onpointermove=e=>{
    if(!start)return;
    const end=point(e),next={x:Math.min(start.x,end.x),y:Math.min(start.y,end.y),width:Math.abs(end.x-start.x),height:Math.abs(end.y-start.y)};
    for(const key of Object.keys(next))fields[key].value=next[key].toFixed(4);
    draw();
  };
  canvas.onpointerup=canvas.onpointercancel=()=>{start=null};
  for(const name of ['x','y','width','height'])fields[name].oninput=draw;
  draw();
}
async function saveRegion(remove){
  const form=$('#region-form'),page=Number($('#region-page').value);
  if(!remove&&page!==Number($('#modal').dataset.loadedPage))throw Error('Load this page before saving.');
  const body={question,page,index:Number(form.dataset.index),remove};
  for(const name of ['x','y','width','height'])body[name]=Number(form.elements[name].value);
  const draft=writable()?reviewDraft():null;
  await api('/scripts/'+script().id+'/region',body);
  $('#modal').close();
  await refresh();
  if(draft){draft.awards?.forEach(award=>award.region=null);restoreDraft(draft)}
  toast(remove?'Region removed.':'Answer region saved.');
}
function captureAwards(){
  return [...document.querySelectorAll('.criterion-award')].map(el=>{
    const value=el.querySelector('.award-score').value,region=el.querySelector('.award-region');
    return {marks:value===''?null:Number(value),evidence:el.querySelector('.award-evidence').value,region:region?Number(region.value):null};
  });
}
const sameRegion=(a,b)=>b&&['page','x','y','width','height'].every(key=>Number(a[key])===Number(b[key]));
function evidenceRegions(item){
  const regions=[...(item.regions||(item.region?[item.region]:[]))];
  for(const {source} of item.ocrJob?.sourceSpans||[]){
    if(!regions.some(region=>sameRegion(region,source)))regions.push(source);
  }
  return regions;
}
function criterionMarking(item){
  if(item.scoringMode!=='CRITERIA')return '';
  const regions=evidenceRegions(item);
  return `<div class="criterion-marking">${item.criteria.map((c,i)=>{
    const award=(item.awards||item.draftAwards)?.[i];
    const source=award?.source;
    const current=source?regions.findIndex(r=>sameRegion(r,source)):0;
    return `<div class="criterion-award"><label>${esc(c.text)} · Max ${c.marks}<input class="award-score" type="number" min="0" max="${c.marks}" step="0.01" value="${award?.marks??''}"></label>
      <label>Supporting excerpt<textarea class="award-evidence" maxlength="2000">${esc(award?.evidence||'')}</textarea></label>
      ${regions.length?`<label>Source region<select class="award-region">${regions.map((r,index)=>`<option value="${index}" ${index===current?'selected':''}>Region ${index+1} · Page ${r.page}</option>`).join('')}</select></label>`:''}</div>`;
  }).join('')}</div>`;
}
function restoreDraft(draft){
  $('#ocr-text').value=draft.ocr;$('#remarks').value=draft.remarks;
  $('#final-score').value=draft.score??'';$('#manual-review').checked=draft.manualReview;
  if(draft.page<=$('#answer-page').max)$('#answer-page').value=draft.page;
  if(draft.awards)document.querySelectorAll('.criterion-award').forEach((el,i)=>{
    el.querySelector('.award-score').value=draft.awards[i].marks??'';
    el.querySelector('.award-evidence').value=draft.awards[i].evidence;
    if(el.querySelector('.award-region')&&draft.awards[i].region!==null)el.querySelector('.award-region').value=draft.awards[i].region;
  });
}
function changedDraft(){
  const draft=reviewDraft(),saved=answer();
  const awards=saved.awards||saved.draftAwards;
  return draft.ocr!==(saved.ocr||'')||draft.remarks!==(saved.remarks||'')||draft.score!==(saved.score??null)
    ||draft.page!==(saved.page||1)||draft.manualReview!==Boolean(saved.manualReview)
    ||Boolean(draft.awards?.some((a,i)=>a.marks!==(awards?.[i]?.marks??null)||a.evidence!==(awards?.[i]?.evidence||'')||
      a.region!==null&&a.region!==Math.max(0,evidenceRegions(saved).findIndex(region=>sameRegion(region,awards?.[i]?.source)))));
}
