// usage: node merge-3way-files.js <prHeadSha> <file>...   (inside a worktree mid-merge)
// .properties: develop's file + PR-added/changed keys (changed in place, added appended) - PR-removed keys.
// changelog.xml: develop's file + the PR's own new <changeSet> blocks (with their preceding comment) before </databaseChangeLog>.
// .java (enum lists): take conflict blocks as theirs+ours (both sides only add constants).
const {execSync}=require('child_process');const fs=require('fs');
const sh=c=>execSync(c,{encoding:'utf8',maxBuffer:1<<28});
const pr=process.argv[2];const mb=sh('git merge-base origin/develop '+pr).trim();
const show=(r,f)=>{try{return sh('git show '+r+':'+f).replace(/\r\n/g,'\n')}catch(e){return ''}};
for(const f of process.argv.slice(3)){
 const D=show('origin/develop',f),B=show(mb,f),P=show(pr,f);
 let out;
 if(f.endsWith('.properties')){
  const parse=s=>{const m=new Map();for(const l of s.split('\n')){const t=l.trim();if(!t||t[0]=='#'||t[0]=='!')continue;const i=l.search(/[=:]/);if(i<0)continue;m.set(l.slice(0,i).trim(),l)}return m};
  const pb=parse(B),pp=parse(P);let lines=D.split('\n');
  const dkeyIdx=new Map();lines.forEach((l,i)=>{const t=l.trim();if(!t||t[0]=='#')return;const j=l.search(/[=:]/);if(j>=0)dkeyIdx.set(l.slice(0,j).trim(),i)});
  const add=[];
  for(const [k,l] of pp){ if(pb.get(k)===l) continue; if(dkeyIdx.has(k)) lines[dkeyIdx.get(k)]=l; else add.push(l); }
  for(const [k] of pb){ if(!pp.has(k)&&dkeyIdx.has(k)) lines[dkeyIdx.get(k)]=null; }
  lines=lines.filter(l=>l!==null);while(lines.length&&lines[lines.length-1]==='')lines.pop();
  // also carry PR-added comment lines directly preceding its added keys: keep simple, keys only
  out=lines.concat(add).join('\n')+'\n';
 } else if(f.endsWith('changelog.xml')){
  const blocks=s=>{const r=new Map();const re=/(\s*<!--(?:(?!-->)[\s\S])*-->)?\s*<changeSet\b[^>]*\bid="([^"]+)"[\s\S]*?<\/changeSet>/g;let m;while((m=re.exec(s)))r.set(m[2],m[0]);return r};
  const bd=blocks(D),bb=blocks(B),bp=blocks(P);let add='';
  for(const [id,txt] of bp){ if(!bb.has(id)&&!bd.has(id)) add+=txt; }
  const end=D.lastIndexOf('</databaseChangeLog>');
  out=D.slice(0,end).replace(/\s*$/,'')+add+'\n\n'+D.slice(end);
 } else {
  let s=fs.readFileSync(f,'utf8').replace(/\r\n/g,'\n');
  s=s.replace(/<<<<<<< [^\n]*\n([\s\S]*?)=======\n([\s\S]*?)>>>>>>> [^\n]*\n/g,(m,o,t)=>t+o);
  out=s;
 }
 if(/^(<<<<<<<|=======|>>>>>>>)/m.test(out)) throw new Error('markers in '+f);
 fs.writeFileSync(f,out);console.log('resolved',f);
}
