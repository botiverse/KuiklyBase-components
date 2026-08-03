'use strict';

const assert = require('node:assert/strict');
const { Worker } = require('node:worker_threads');

if (process.argv.length !== 6) {
  throw new Error('expected addon, valid, missing-env, and missing-bridge paths');
}

const [addonPath, validPath, missingEnvPath, missingBridgePath] = process.argv.slice(2);
const addon = require(addonPath);
const expectedExports = [
  'create_function_waiter',
  'init',
  'notify_function_waiter',
  'setup',
  'wait_on_function_waiter',
];

assert.deepEqual(Object.getOwnPropertyNames(addon).sort(), expectedExports);
for (const name of expectedExports) {
  assert.equal(typeof addon[name], 'function', `${name} must be a function`);
}
assert.equal('JSBind' in addon, false, 'the global Aki JSBind class must stay private');

function expectCode(call, code) {
  assert.throws(call, (error) => {
    assert.equal(error && error.code, code);
    return true;
  });
}

assert.throws(() => addon.setup(), /Wrong number of arguments/);
expectCode(() => addon.setup(7, false), 'JSBind: Wrong type of arguments');
expectCode(() => addon.init(), 'KNOI_NOT_CONFIGURED');
assert.equal('knoi' in globalThis, false, 'failed init must not leave a partial global');
expectCode(
  () => addon.setup('/definitely/not/a/knoi/library.so', false),
  'KNOI_LIBRARY_OPEN_FAILED',
);
expectCode(() => addon.setup(missingEnvPath, false), 'KNOI_MISSING_INIT_ENV');
expectCode(() => addon.setup(missingBridgePath, false), 'KNOI_MISSING_INIT_BRIDGE');

assert.equal(addon.setup(validPath, true), undefined);
assert.equal(addon.setup(validPath, true), undefined);
assert.equal(addon.setup(validPath, false), undefined);
assert.equal(addon.setup(missingEnvPath, false), undefined);
assert.equal(addon.setup('', false), undefined);
assert.equal(addon.init(), undefined);
assert.equal(typeof globalThis.knoi, 'object');
assert.notEqual(globalThis.knoi, null);

const waiterId = addon.create_function_waiter();
assert.equal(Number.isSafeInteger(waiterId), true);
const unicodePayload = JSON.stringify({ message: '你好🙂', escaped: '\\n' });
assert.equal(
  addon.notify_function_waiter(waiterId, unicodePayload, unicodePayload.length),
  undefined,
);
assert.equal(addon.wait_on_function_waiter(waiterId), unicodePayload);
expectCode(
  () => addon.notify_function_waiter(waiterId, 'late', 4),
  'KNOI_WAITER_NOT_FOUND',
);

const timeoutId = addon.create_function_waiter();
expectCode(
  () => addon.wait_on_function_waiter(timeoutId),
  'KNOI_WAITER_TIMED_OUT',
);
expectCode(
  () => addon.notify_function_waiter(timeoutId, 'too late', 8),
  'KNOI_WAITER_NOT_FOUND',
);

assert.throws(
  () => addon.notify_function_waiter(waiterId, unicodePayload),
  /Wrong number of arguments/,
);
expectCode(
  () => addon.notify_function_waiter('not-an-id', unicodePayload, unicodePayload.length),
  'JSBind: Wrong type of arguments',
);

const crossEnvironmentWaiterId = addon.create_function_waiter();
const crossEnvironmentPayload = JSON.stringify({ worker: '跨环境你好🙂' });
let workerReady = false;
let workerNotified = false;
let crossEnvironmentWaitCompleted = false;
const worker = new Worker(
  `
    'use strict';
    const { parentPort, workerData } = require('node:worker_threads');
    const addon = require(workerData.addonPath);
    addon.setup(workerData.fallbackPath, false);
    addon.init();
    parentPort.postMessage({
      type: 'ready',
      exports: Object.getOwnPropertyNames(addon).sort(),
      hasGlobal: typeof globalThis.knoi === 'object' && globalThis.knoi !== null,
    });
    parentPort.once('message', ({ id, payload }) => {
      addon.notify_function_waiter(id, payload, payload.length);
      parentPort.postMessage({ type: 'notified' });
      parentPort.close();
    });
  `,
  {
    eval: true,
    workerData: { addonPath, fallbackPath: missingEnvPath },
  },
);
worker.on('message', (message) => {
  if (message.type === 'ready') {
    assert.deepEqual(message.exports, expectedExports);
    assert.equal(message.hasGlobal, true, 'worker init must install globalThis.knoi');
    workerReady = true;
    worker.postMessage({
      id: crossEnvironmentWaiterId,
      payload: crossEnvironmentPayload,
    });
    assert.equal(
      addon.wait_on_function_waiter(crossEnvironmentWaiterId),
      crossEnvironmentPayload,
      'main env waiter must be released by the Worker env notification',
    );
    crossEnvironmentWaitCompleted = true;
    return;
  }
  assert.equal(message.type, 'notified', 'unexpected Worker probe message');
  workerNotified = true;
});
worker.once('error', (error) => {
  throw error;
});
worker.once('exit', (code) => {
  assert.equal(code, 0, 'worker runtime probe failed');
  assert.equal(workerReady, true, 'worker runtime probe never reached ready');
  assert.equal(workerNotified, true, 'worker runtime probe did not complete notification');
  assert.equal(
    crossEnvironmentWaitCompleted,
    true,
    'main env waiter did not consume the Worker env notification',
  );
  console.log('KNOI addon host runtime contract PASS');
});
