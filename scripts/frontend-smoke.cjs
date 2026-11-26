const fs=require('fs'),vm=require('vm'),assert=require('assert');
const app={innerHTML:''};const context={URLSearchParams,location:{pathname:'/login',search:''},document:{querySelector:s=>s==='#app'?app:s==='meta[name=csrf-token]'?{content:'test'}:null,addEventListener:()=>{},querySelectorAll:()=>[]},setTimeout:()=>{}};
vm.createContext(context);vm.runInContext(fs.readFileSync('src/main/resources/static/js/app.js','utf8'),context);
vm.runInContext(`state={exams:[{id:'EX-1',name:'বাংলা',subject:'Bangla',questions:1,date:'2026-11-24',criteria:[{text:'Criterion',marks:7}]}],scripts:[{id:'SCR-1',exam:'EX-1',status:'To Review',score:0,remarks:'',ocr:'',mime:'image/png',criteria:[{text:'Criterion',marks:7}],history:[]}],criteria:[{text:'Criterion',marks:7}],user:'teacher@school.local',role:'TEACHER',version:1};`,context);
for(const route of ['/dashboard','/exams','/rubrics','/scripts','/upload','/review','/moderation','/reports','/profile','/verify','/login']){context.location.pathname=route;vm.runInContext('render()',context);assert(app.innerHTML.includes('<h1>'),route);assert(!app.innerHTML.includes('localStorage'),route);console.log('Rendered '+route)}
assert(!app.innerHTML.includes('name="otp"'), 'Password-only sign-in should omit the authenticator field');
assert(!app.innerHTML.includes('name="demoRole"'), 'Normal sign-in should omit demo roles');
context.location.pathname='/review';vm.runInContext('state.scripts=[];render()',context);assert(app.innerHTML.includes('No answer scripts yet'));console.log('Empty review handled');

function renderLoginWithFlag(name) {
  vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/app.js', 'utf8'), {
    ...context,
    location: { pathname: '/login', search: '' },
    document: {
      ...context.document,
      querySelector: selector => selector === 'meta[name=' + name + ']'
        ? { content: 'true' }
        : context.document.querySelector(selector)
    }
  });
}
renderLoginWithFlag('authenticator-enabled');
assert(app.innerHTML.includes('name="otp"'), 'Configured sign-in should show the authenticator field');
renderLoginWithFlag('local-demo-enabled');
const demoForm = app.innerHTML.match(/<form id="demo-login-form"[\s\S]*?<\/form>/)?.[0];
assert(demoForm, 'Login should show the demo form');
for (const role of ['TEACHER', 'MARKER', 'MODERATOR']) assert(demoForm.includes('name="demoRole" value="' + role + '"'));
for (const field of ['username', 'password', 'otp']) assert(!demoForm.includes('name="' + field + '"'));
assert(app.innerHTML.includes('<details class="login-account">'), 'School account login should start collapsed');
