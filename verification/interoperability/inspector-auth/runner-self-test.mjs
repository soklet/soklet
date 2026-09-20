import assert from 'node:assert/strict';
import test from 'node:test';
import {CASES,adjudicateCase,adjudicateMatrix,collectCases,configuration,forwardPaused,parseArguments} from './run.mjs';

const keys=['authorized','requestEnvelopeValid','protocolMetadataMatches','protocolHeaderMatches',
  'methodHeaderMatches','perRequestCapabilitiesPresent','noSessionState','requestSelectionValid',
  'responseJson','responseNoStore','responseCorrelated','responseEnvelopeValid','resultMatchesFixture'];
function example(profile) {
  const modes=profile.mode==='no-subscription'?['server/discover','tools/list','tools/list']
    :['server/discover','subscriptions/listen','tools/list','tools/list','subscriptions/listen'];
  const rows=modes.map((method,i)=>({surface:'inspector-auth',method,sequence:i+1,requestSequence:i+1,
    ...Object.fromEntries(keys.map(key=>[key,true])),requestBytes:128,responseBytes:160,
    responseStatus:method==='subscriptions/listen'&&profile.mode==='policy-403'?403:200,
    subscriptionDenied:method==='subscriptions/listen'}));
  const paths=['OAUTH_PROTECTED_RESOURCE_PATH','OAUTH_PROTECTED_RESOURCE_ROOT','OAUTH_AUTHORIZATION_SERVER_ROOT',
    'OPENID_CONFIGURATION_ROOT','OPENID_CONFIGURATION_ROOT','OAUTH_REGISTRATION_ROOT'];
  return {name:profile.name,mode:profile.mode,rows,rejections:profile.mode==='policy-403'?paths.map((path,i)=>({
    sequence:i+1,requestSequence:i+rows.length+1,path,method:path==='OAUTH_REGISTRATION_ROOT'?'POST':'GET',
    code:'AUTH_OAUTH_REFUSED',originAbsent:true})):[],browserExceptions:0,
  fixtureFailure:null,cdpFailure:null,browserShutdown:'CLEAN',hostShutdown:'CLEAN',
  ...Object.fromEntries(['readOnlyMemoryStore','hostAuthRequired','hostOriginRestricted','connectedViaDom',
    'acquisitionObserved','observationWindowCompleted','disconnectClicked','disconnectedViaDom',
    'configUnchanged','privateStateRemoved'].map(key=>[key,true])),
  cleanup:{cdp:true,browser:true,host:true,fixture:true},network:{requests:20,blockedFont:1,unexpected:0}};
}

test('explicit unique three-input CLI and exact named cases',()=>{
  const args=['--dependencies','a','--browser','b','--work-dir','c'];
  assert.equal(Object.keys(parseArguments(args)).length,3);
  for(const bad of [args.slice(0,-2),[...args,'--unknown','d'],[...args.slice(0,-2),'--browser','d']])
    assert.throws(()=>parseArguments(bad),/AUTH_ARGUMENTS/);
  const token='a'.repeat(64),url='http://127.0.0.1:12345/mcp';
  for(const profile of CASES) {
    const server=configuration(url,token,profile).mcpServers.soklet;
    assert.equal(server.headers.Authorization,`Bearer ${token}`);
    assert.deepEqual(server.advertisedExtensions,{'io.modelcontextprotocol/ui':false,'io.modelcontextprotocol/skills':false});
    assert.deepEqual(server.oauth,profile.onInsufficientScope?{onInsufficientScope:'throw'}:undefined);
    assert.equal(server.autoRefreshOnListChanged,profile.autoRefreshOnListChanged);
  }
  assert.throws(()=>configuration(url,token,{name:'arbitrary',mode:'policy-403'}),/AUTH_CASE/);
});

test('causal matrix records defect reproduction, never a host qualification PASS',()=>{
  const receipt={inputsUnchanged:true,runs:CASES.map(example)};
  assert.equal(adjudicateMatrix(receipt),'REPRODUCED_WITH_CONTROLS');
  for(const [i,profile] of CASES.entries())
    assert.equal(adjudicateCase(receipt.runs[i]),profile.mode==='policy-403'?'BUG_REPRODUCED':'CONTROL_PASSED');
  for(const changed of [{...receipt,inputsUnchanged:false},{...receipt,runs:receipt.runs.slice(1)},
    {...receipt,runs:receipt.runs.toReversed()}])assert.equal(adjudicateMatrix(changed),'FAILED');
});

test('controls reject OAuth traffic and missing actual subscription discrimination',()=>{
  const no=example(CASES[0]),causal=example(CASES[1]),bug=example(CASES[2]);
  for(const control of [no,causal]) {
    control.rejections=bug.rejections;
    assert.equal(adjudicateCase(control),'FAILED');
  }
  bug.rejections=[];assert.equal(adjudicateCase(bug),'FAILED');
  const missing=example(CASES[1]);missing.rows=example(CASES[0]).rows;
  assert.equal(adjudicateCase(missing),'FAILED');
});

test('every traffic, isolation, observation, integrity and cleanup fact is mandatory',()=>{
  const row=example(CASES[2]);
  for(const [key,value] of Object.entries(row))if(value===true)
    assert.equal(adjudicateCase({...row,[key]:false}),'FAILED',key);
  for(const key of keys) {
    const changed=structuredClone(row);changed.rows[1][key]=false;
    assert.equal(adjudicateCase(changed),'FAILED',key);
  }
  for(const key of Object.keys(row.cleanup))assert.equal(adjudicateCase({...row,cleanup:{...row.cleanup,[key]:false}}),'FAILED');
  for(const change of [{browserShutdown:'NOT_PROVEN'},{hostShutdown:'NOT_PROVEN'},
    {failure:'AUTH_CDP_FAILURE'},{fixtureFailure:'AUTH_PATH'},{browserExceptions:-1},
    {network:{...row.network,unexpected:1}},{network:{...row.network,blockedFont:0}},
    {network:{...row.network,requests:257}}])assert.equal(adjudicateCase({...row,...change}),'FAILED');
  for(const change of [{sequence:0},{surface:'apps-web'},{method:'initialize'},
    {responseStatus:200},{subscriptionDenied:false},{requestBytes:0},{responseBytes:65537}]) {
    const changed=structuredClone(row);Object.assign(changed.rows[1],change);
    assert.equal(adjudicateCase(changed),'FAILED');
  }
});

test('only exact locally refused OAuth categories establish the observed bug',()=>{
  const good=example(CASES[2]);
  for(const change of [{path:'OTHER'},{method:'DELETE'},{code:'AUTH_REQUIRED'},{originAbsent:false},{sequence:0}]) {
    const bad=structuredClone(good);Object.assign(bad.rejections[0],change);
    assert.equal(adjudicateCase(bad),'FAILED');
  }
  good.rejections=good.rejections.filter(row=>row.path!=='OAUTH_REGISTRATION_ROOT');
  assert.equal(adjudicateCase(good),'FAILED');
});

test('matrix cancellation never schedules another host/browser case',async()=>{
  let interrupted=false,calls=0,rows=0;
  await collectCases(async()=>{calls++;interrupted=true;return {};},async()=>{rows++;},()=>interrupted);
  assert.equal(calls,1);assert.equal(rows,1);
  await collectCases(async()=>{calls++;},async()=>{rows++;},()=>true);
  assert.equal(calls,1);assert.equal(rows,1);
});

test('OAuth recovery must follow a denial in one gap-free bounded HTTP sequence',()=>{
  const good=example(CASES[2]);
  for(const value of [undefined,0,1,65,1.5]) {
    const bad=structuredClone(good);bad.rejections[0].requestSequence=value;
    assert.equal(adjudicateCase(bad),'FAILED');
  }
  const premature=structuredClone(good);
  [premature.rows[1].requestSequence,premature.rejections[0].requestSequence]=
    [premature.rejections[0].requestSequence,premature.rows[1].requestSequence];
  assert.equal(adjudicateCase(premature),'FAILED');
});

test('shutdown keeps late requests paused and only consumes intentional CDP_CLOSED',async()=>{
  const calls=[];
  const cdp={send:async(...args)=>{calls.push(args);}};
  await forwardPaused(cdp,'session',{requestId:'one'},true,()=>false);
  await forwardPaused(cdp,'session',{requestId:'two'},false,()=>false);
  assert.equal(calls[0][0],'Fetch.continueRequest');assert.equal(calls[1][0],'Fetch.failRequest');
  await forwardPaused(cdp,'session',{requestId:'three'},true,()=>true);
  assert.equal(calls.length,2);
  for(const code of ['CDP_CLOSED','CDP_COMMAND_ERROR','CDP_COMMAND_TIMEOUT']) {
    let closing=false;
    const failing={send:async()=>{closing=true;throw new Error(code);}};
    const pending=forwardPaused(failing,'session',{requestId:'four'},true,()=>closing);
    if(code==='CDP_CLOSED')await pending;else await assert.rejects(pending,new RegExp(code));
  }
  await assert.rejects(forwardPaused({send:async()=>{throw new Error('CDP_CLOSED');}},
    'session',{requestId:'five'},true,()=>false),/CDP_CLOSED/);
});
