const results = [];
const test = async (name, action) => {
  try { if (!(await action())) throw new Error('assertion failed'); results.push({name, ok:true}); }
  catch (error) { results.push({name, ok:false, error:String(error)}); }
  document.getElementById('results').textContent = results.map(r => `${r.ok ? 'PASS' : 'FAIL'} ${r.name}${r.error ? ': '+r.error : ''}`).join('\n');
};
window.onJavaMessage = async payload => {
  document.getElementById('backend').textContent = payload.backend;
  await test('shared Java hello', async () => (await java.invoke('hello',{name:'World'})) === 'Hello World');
  await test('shared Java sum', async () => (await java.invoke('sum',{a:3,b:4})) === 7);
  await test('structured JSON', async () => {
    const sent={a:[1,2],b:{unicode:'中文'},c:null};
    return JSON.stringify(await java.invoke('echo',sent))===JSON.stringify(sent);
  });
  await test('virtual-thread handler', async () => { const r=await java.invoke('runtime',{});return r.virtual && !r.ui; });
  await test('slow handler does not block', async () => {
    const slow=java.invoke('slow',{}), start=performance.now();
    await java.invoke('hello',{name:'fast'});const elapsed=performance.now()-start;await slow;return elapsed<250;
  });
  await test('UI-thread blocking control', async () => {
    const slow=java.invoke('slowUi',{}), start=performance.now();
    await java.invoke('hello',{name:'blocked'});const elapsed=performance.now()-start;await slow;return elapsed>=300;
  });
  await test('cancellation interrupts Java', async () => {
    const before=await java.invoke('interrupts',{}), pending=java.invoke('interruptible',{});
    pending.catch(()=>{});await new Promise(r=>setTimeout(r,150));pending.cancel();
    await new Promise(r=>setTimeout(r,200));return (await java.invoke('interrupts',{}))===before+1;
  });
  await test('Java deadline', async () => {
    try {await java.invoke('deadline',{}, {deadlineMs:5000});return false;}
    catch(error){return /no answer within 150 ms/.test(String(error));}
  });
  await test('100 correlated replies', async () => {
    const all=await Promise.all(Array.from({length:100},(_,n)=>java.invoke('echo',{n})));
    return all.every((r,n)=>r.n===n);
  });
  await test('ES module', async () => (await import('./module.js')).value===42);
  await test('fetch local JSON', async () => (await (await fetch('./fixture.json')).json()).value===42);
  await test('Web Worker', () => new Promise((resolve,reject) => {
    const worker=new Worker('./worker.js'), timer=setTimeout(()=>{worker.terminate();reject(new Error('worker timeout'));},3000);
    worker.onmessage=e=>{clearTimeout(timer);worker.terminate();resolve(e.data===42);};
    worker.onerror=e=>{clearTimeout(timer);worker.terminate();reject(new Error(e.message));};worker.postMessage(41);
  }));
  await java.invoke('report',{passed:results.filter(r=>r.ok).length,failed:results.filter(r=>!r.ok).length,checks:results});
};
