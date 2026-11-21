// usage: node verify-merge.js <prHeadSha> ; run inside worktree with merge in progress or done (HEAD = merge)
const {execSync}=require('child_process');const fs=require('fs');
const sh=c=>execSync(c,{encoding:'utf8',maxBuffer:1<<28});
const pr=process.argv[2];const mb=sh(`git merge-base origin/develop ${pr}`).trim();
const keys=s=>{const o=new Set();(function w(x,p){for(const k in x){x[k]&&typeof x[k]=='object'?w(x[k],p+k+'.'):o.add(p+k)}})(JSON.parse(s),'');return o};
const show=(r,f)=>{try{return sh(`git show ${r}:${f}`)}catch(e){return null}};
for(const l of ['en','fr','es']){const f=`hospital-portal/src/assets/i18n/${l}.json`;
 const d=keys(show('origin/develop',f)),p=keys(show(pr,f)),b=keys(show(mb,f)),m=keys(fs.readFileSync(f,'utf8'));
 const e=new Set(d);for(const k of p)if(!b.has(k))e.add(k);for(const k of b)if(!p.has(k))e.delete(k);
 console.log(l,'merged',m.size,'expected',e.size,'missing',[...e].filter(k=>!m.has(k)).length,'extra',[...m].filter(k=>!e.has(k)).length)}
const cl='hospital-core/src/main/resources/db/migration/changelog.xml';
const ids=s=>[...s.matchAll(/<changeSet\b[^>]*\bid="([^"]+)"/g)].map(x=>x[1]);
const files=s=>[...s.matchAll(/(?:path|file)="([^"]+\.sql)"/g)].map(x=>x[1]);
const cur=fs.readFileSync(cl,'utf8');
const {DOMParser}=(()=>{try{return require('@xmldom/xmldom')}catch(e){return {}}})();
const e2=new Set(ids(show('origin/develop',cl)));const pi=ids(show(pr,cl)),bi=new Set(ids(show(mb,cl)));for(const k of pi)if(!bi.has(k))e2.add(k);
const ci=ids(cur);const dup=ci.filter((x,i)=>ci.indexOf(x)!==i);
console.log('changelog changeSets',ci.length,'expected',e2.size,'missing',[...e2].filter(k=>!ci.includes(k)).length,'dups',dup.length,'sqlfiles',files(cur).length, 'distinct',new Set(files(cur)).size);
const opens=(cur.match(/<changeSet\b/g)||[]).length,closes=(cur.match(/<\/changeSet>/g)||[]).length;console.log('open/close',opens,closes,'last5',ci.slice(-5).join(','));
