// usage (inside a worktree mid-merge): node i18n-3way.js <prHeadSha>
// merged = develop's file + the PR's own additions/changes/removals vs merge-base. Exact formatting.
const {execSync}=require('child_process');const fs=require('fs');
const sh=c=>execSync(c,{encoding:'utf8',maxBuffer:1<<28});
const pr=process.argv[2];const mb=sh('git merge-base origin/develop '+pr).trim();
const isObj=x=>x&&typeof x==='object'&&!Array.isArray(x);
function apply(dst,base,head){ // apply head-vs-base delta into dst
  for(const k of Object.keys(head)){
    if(isObj(head[k])){ if(!isObj(dst[k])) dst[k]={}; apply(dst[k],isObj(base&&base[k])?base[k]:{},head[k]); }
    else if(!base||!(k in base)||JSON.stringify(base[k])!==JSON.stringify(head[k])) dst[k]=head[k];
  }
  if(base) for(const k of Object.keys(base)) if(!(k in head)) delete dst[k];
}
for(const l of ['en','fr','es']){const f='hospital-portal/src/assets/i18n/'+l+'.json';
  const j=r=>JSON.parse(sh('git show '+r+':'+f));
  const out=j('origin/develop');apply(out,j(mb),j(pr));
  fs.writeFileSync(f,JSON.stringify(out,null,2)+'\n');console.log('wrote',f);}
