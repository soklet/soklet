import assert from 'node:assert/strict';
import test from 'node:test';
import {CONTROL_CASES,requestForControl,validControlResponse,adjudicateDirectControls,runDirectControls} from './direct-controls.mjs';
const UI='io.modelcontextprotocol/ui',MIME='text/html;profile=mcp-app',URI='ui://soklet/catalog-v1';
const shell='<html>static fixture shell</html>';
const meta={'io.modelcontextprotocol/serverInfo':{name:'soklet-apps-fixture',version:'fixture-v1'}};
const data={locale:'en-US',direction:'ltr',tenant:'alpha',title:'Catalog view',refreshLabel:'Refresh catalog',
  summary:'Catalog alpha: 1 item.',itemLabel:'Toy <img src=x onerror=alert(1)>',amount:1234.5,currency:'USD',
  updatedAt:'2026-09-19T12:00:00Z',timeZone:'UTC'};
const headers=()=>new Headers({'content-type':'application/json','cache-control':'no-store'});
function body(profile) {
  if(profile.method==='tools/call'&&!profile.apps)return {jsonrpc:'2.0',id:1,error:{code:-32021,
    message:'Missing required client capability',data:{requiredCapabilities:{extensions:{[UI]:{mimeTypes:[MIME]}}}}}};
  return {jsonrpc:'2.0',id:1,result:profile.method==='tools/call'
    ?{resultType:'complete',content:[{type:'text',text:data.summary}],structuredContent:data,_meta:{...meta,'example/view':'catalog-v1'}}
    :{resultType:'complete',ttlMs:0,cacheScope:'private',_meta:meta,contents:[{uri:URI,mimeType:MIME,text:shell,
      _meta:{ui:{csp:{connectDomains:[],resourceDomains:[],frameDomains:[],baseUriDomains:[]},prefersBorder:true}}}]}};
}
const status=profile=>profile.method==='tools/call'&&!profile.apps?400:200;
const examples=()=>CONTROL_CASES.map(profile=>({name:profile.name,appsAdvertised:profile.apps,status:status(profile),
  responseMatches:true,requestBytes:200,responseBytes:200}));

test('OFF/ON/OFF helper and ordinary resource controls use explicit per-request capabilities',()=>{
  for(const profile of CONTROL_CASES) {
    const request=requestForControl(profile),capabilities=request.params._meta['io.modelcontextprotocol/clientCapabilities'];
    assert.deepEqual(capabilities,profile.apps?{extensions:{[UI]:{mimeTypes:[MIME],elicitation:{}}}}:{});
    assert.equal(request.method,profile.method);
    assert.equal(request.params.name,profile.method==='tools/call'?'refresh_catalog':undefined);
    assert.equal(request.params.uri,profile.method==='resources/read'?URI:undefined);
  }
  assert.throws(()=>requestForControl({name:'arbitrary',apps:false,method:'tools/call'}),/APPS_HOST_CONTROL_PROFILE/);
});

test('exact missing capability response and authorized OFF resource read match existing semantics',()=>{
  for(const profile of CONTROL_CASES) {
    assert.equal(validControlResponse(profile,status(profile),headers(),body(profile),shell),true);
    for(const code of [200,400,401,403,500].filter(value=>value!==status(profile)))
      assert.equal(validControlResponse(profile,code,headers(),body(profile),shell),false);
    for(const [key,value] of [['id',2],['jsonrpc','1.0'],['private','secret']]) {
      const changed=structuredClone(body(profile));changed[key]=value;
      assert.equal(validControlResponse(profile,status(profile),headers(),changed,shell),false);
    }
    for(const [key,value] of [['cache-control','public'],['content-type','text/event-stream'],
      ['mcp-session-id','secret'],['www-authenticate','Bearer']]) {
      const changed=headers();changed.set(key,value);
      assert.equal(validControlResponse(profile,status(profile),changed,body(profile),shell),false);
    }
  }
  const profile=CONTROL_CASES[0];
  for(const change of [{code:-32603},{message:'wrong'},
    {data:{requiredCapabilities:{extensions:{[UI]:{mimeTypes:['text/html']}}}}}]) {
    const changed=body(profile);Object.assign(changed.error,change);
    assert.equal(validControlResponse(profile,400,headers(),changed,shell),false);
  }
  const leaked=body(CONTROL_CASES[1]);leaked.result.content=[{type:'text',text:'fixture-private-canary'}];
  assert.equal(validControlResponse(CONTROL_CASES[1],200,headers(),leaked,shell),false);
});

test('direct verdict requires all four cases in order and only safe exact bounded fields',()=>{
  assert.equal(adjudicateDirectControls(examples()),'PASSED');
  for(const rows of [null,[],examples().slice(1),examples().toReversed(),[...examples(),examples()[0]]])
    assert.equal(adjudicateDirectControls(rows),'FAILED');
  for(let i=0;i<4;i++)for(const change of [{name:'other'},{appsAdvertised:!CONTROL_CASES[i].apps},
    {status:401},{responseMatches:false},{requestBytes:0},{requestBytes:65537},
    {responseBytes:0},{responseBytes:1048577},{raw:'private'}]) {
    const rows=examples();Object.assign(rows[i],change);assert.equal(adjudicateDirectControls(rows),'FAILED');
  }
});

test('bounded direct transport records only fixed structural rows and uses same disposable credential',async()=>{
  const calls=[],token='c'.repeat(64);
  const rows=await runDirectControls({port:12345,token,shell,fetchImpl:async(url,options)=>{
    const profile=CONTROL_CASES[calls.length];calls.push({url,options});
    assert.equal(url,'http://127.0.0.1:12345/apps');
    assert.equal(options.headers.Authorization,`Bearer ${token}`);assert.equal(options.redirect,'error');
    assert.ok(options.signal instanceof AbortSignal);
    assert.deepEqual(JSON.parse(options.body),requestForControl(profile));
    return new Response(JSON.stringify(body(profile)),{status:status(profile),headers:headers()});
  }});
  assert.equal(calls.length,4);assert.equal(adjudicateDirectControls(rows),'PASSED');
  const serialized=JSON.stringify(rows);
  for(const secret of [token,'Bearer','fixture-private-canary',shell,URI,data.summary])assert.ok(!serialized.includes(secret));
});

test('input, response, malformed JSON and streaming overflow fail without continuing requests',async()=>{
  for(const input of [{port:0},{port:65536},{token:'bad'},{shell:''},{shell:'x'.repeat(524289)}])
    await assert.rejects(runDirectControls({port:12345,token:'c'.repeat(64),shell,...input}),/APPS_HOST_CONTROL_INPUT/);
  for(const response of [new Response('{}',{status:400,headers:headers()}),
    new Response('not-json',{status:400,headers:headers()}),
    new Response('x'.repeat(1048577),{status:400,headers:headers()})]) {
    let calls=0;
    await assert.rejects(runDirectControls({port:12345,token:'c'.repeat(64),shell,
      fetchImpl:async()=>{calls++;return response;}}),/APPS_HOST_CONTROL_(?:RESPONSE|JSON|BOUND)/);
    assert.equal(calls,1);
  }
});
